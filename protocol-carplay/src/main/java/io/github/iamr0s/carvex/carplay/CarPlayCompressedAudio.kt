package io.github.iamr0s.carvex.carplay
import io.github.iamr0s.carvex.sessioncore.*

import io.github.iamr0s.carvex.carplay.core.*
import io.github.iamr0s.carvex.carplay.core.carplay.BuildConfig
import io.github.iamr0s.carvex.pcm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher

/** Actual HomeKit Main/Alt compressed sender, anchored to receiver v280.33.8 raw-AU contract.
 *  [sapCrypto] 非空时切换到 MFi/SAP 路线：同一套 12 字节头 + AES-CTR（等长、无 tag、无 nonce 尾），
 *  计数器由该 Cipher 自带并跨包连续；`sapCrypto.plaintext` 时是诊断用的明文直发。
 *  HAP 路线传 null，行为与此前完全一致。 */
class CarPlayCompressedAudio private constructor(private val socket:DatagramSocket,private val peer:InetAddress,
    private val port:Int,private val category:Int,private val streamType:Int,private val selected:CarPlayAudioPlan.Format,
    private val key:ByteArray,private val retransmit:AudioRtpRetransmit?,private val ownership:suspend ()->Unit,private val stopped:()->Unit,
    private val log:(String)->Unit,private val redEnabled:Boolean,
    private val canSend:()->Boolean,private val sapCrypto:SapAudioCrypto?,receiverHighAccuracyTimestamps:Boolean,
    /** 线上 PT 逃生阀，见 [MediaAudioPackets.sapPacket]。null = 规范映射（raw102→96）。 */
    private val wirePtOverride:Int?=null):NegotiatedAudioTransport {
    private val closed=AtomicBoolean(false)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val output=Channel<CompressedAudioEncoder.Event>(8)
    private lateinit var encoder:CompressedAudioEncoder
    override val ready get()=!closed.get()&&!socket.isClosed
    override val formats=mapOf(category to selected.decodedPcm)
    override val microphoneFormat:PcmFormat?=null
    private var sequence=Crypto.random.nextInt(65536)
    private val rtpClock=AudioRtpTimestampClock(streamType,receiverHighAccuracyTimestamps)
    private var nonce=0L
    @Volatile private var packets=0L
    @Volatile private var lastSentNanos=0L
    override val packetsSent:Long get()=packets
    override val lastPacketNanos:Long get()=lastSentNanos
    private var history:ByteArray?=null
    private var historyTimestamp=0L
    private var staleUnits=0L
    private var maximumQueueNanos=0L
    private var maximumWaitNanos=0L
    private var maximumWriteNanos=0L
    private var timingLoggedAt=0L
    private val redDiagnostic=AudioRedDiagnostic(BuildConfig.CARPLAY_PRIMARY_ONLY_RED,category,selected.codec,redEnabled)
    private val packetDiagnostics=AudioPacketDiagnostics(redEnabled,selected.codec)
    init {
        log("Audio RTP clock category="+category+" type="+streamType+
            " peerHighAccuracy="+receiverHighAccuracyTimestamps+" timestampMode="+rtpClock.mode.diagnosticName+
            "; source pacing independent; high-accuracy network-clock anchors not implemented")
        if(sapCrypto!=null)log("Audio crypto category="+category+" mode="+sapCrypto.modeName+"(MFi/SAP)"+
            (if(sapCrypto.plaintext)" diagnostic" else "")+
            " redEnabled="+redEnabled+" payloadOnly=true tagBytes=0 nonceTail=0; frame cipher follows A-44 type/G dispatch")
        // 线上 PT 与 SETUP 的 raw type 是两套编号（R-1.1 MUST NOT 互换）。
        // 这行必须打：没有它就分不清日志里的 type=102 到底是流类型还是线上 PT。
        val effectiveWirePt=MediaAudioPackets.wirePayloadType(streamType,wirePtOverride)
        log("Audio wire payload type category="+category+" rawType="+streamType+
            " wirePT="+effectiveWirePt+" override="+(wirePtOverride!=null)+" source="+
            (if(wirePtOverride!=null)"raw-escape" else "spec(R-1.1 raw102->gen 96)"))
    }
    private fun failed(t:Throwable){
        if(closed.get())return
        log("${"Compressed audio category="}${category}${" failed: "}${t.javaClass.simpleName}${" "}${t.message}${"; audible acceptance pending"}")
        close()
    }
    private fun startWriter(){scope.launch(start=CoroutineStart.UNDISPATCHED){
        var config=false;var ownershipTaken=false;var firstTiming=true
        val clock=AudioSendClock(selected.rate)
        fun clearHistory(){history=null;historyTimestamp=0L}
        fun timing(now:Long){
            if(timingLoggedAt!=0L&&now-timingLoggedAt<5_000_000_000L)return
            timingLoggedAt=now
            log("Audio sender category="+category+" outputQueueMaxMs="+maximumQueueNanos/1_000_000+
                " pacingWaitMaxMs="+maximumWaitNanos/1_000_000+" udpSendMaxMs="+maximumWriteNanos/1_000_000+
                " staleAU="+staleUnits+" firstAuStartupMs="+clock.startupDelayNanos/1_000_000+
                "; local timings only, receiver playout unmeasured")
            maximumQueueNanos=0;maximumWaitNanos=0;maximumWriteNanos=0
        }
        try{
            for(event in output){
                when(event){
                    is CompressedAudioEncoder.Event.Configuration->{
                        require(event.rate==selected.rate&&event.channels==selected.channels)
                        if(selected.codec in setOf(CarPlayAudioPlan.Codec.AAC_LC,CarPlayAudioPlan.Codec.AAC_ELD)){
                            val csd=event.csd["csd-0"]
                            if(csd!=null){AacConfiguration.validate(csd,selected);config=true}
                        }else if(selected.codec==CarPlayAudioPlan.Codec.OPUS)config=true
                        log("Audio encoder category="+category+" delaySamples="+event.delaySamples+
                            " paddingSamples="+event.paddingSamples+" rate="+event.rate)
                    }
                    is CompressedAudioEncoder.Event.CodecConfig->{
                        if(selected.codec in setOf(CarPlayAudioPlan.Codec.AAC_LC,CarPlayAudioPlan.Codec.AAC_ELD)){
                            AacConfiguration.validate(event.bytes,selected);config=true
                        }
                    }
                    is CompressedAudioEncoder.Event.AccessUnit->{
                        if(event.bytes.isEmpty())continue
                        check(config){"Compressed output before validated codec configuration"}
                        require(event.bytes.size<=1400){"Encoded packet exceeds local UDP payload budget; no guessed fragmentation"}
                        val samples=if(selected.codec==CarPlayAudioPlan.Codec.OPUS)CompressedAudioContract.opusSamples(event.bytes,selected.rate) else selected.framesPerPacket
                        require(samples==selected.framesPerPacket){"Encoded frame duration differs from receiver audioFormat"}
                        val plan=clock.plan(event.presentationTimeUs,event.sourceTimeNanos,event.encodedAtNanos,samples)
                        if(firstTiming){
                            firstTiming=false
                            log("Audio encoder timing category="+category+" codecPtsUs="+event.codecPresentationTimeUs+
                                " sourcePtsUs="+event.presentationTimeUs+" firstAuStartupMs="+clock.startupDelayNanos/1_000_000)
                        }
                        maximumQueueNanos=maxOf(maximumQueueNanos,System.nanoTime()-event.encodedAtNanos)
                        if(plan.discontinuity)clearHistory()
                        if(!canSend()){clearHistory();continue}
                        if(!ownershipTaken){ownership();ownershipTaken=true}
                        if(clock.expired(plan,System.nanoTime())){
                            staleUnits++;clearHistory();timing(System.nanoTime());continue
                        }
                        val wait=plan.dueNanos-System.nanoTime()
                        if(wait>0){maximumWaitNanos=maxOf(maximumWaitNanos,wait);delay((wait+999999)/1000000)}
                        check(ready)
                        if(!canSend()){clearHistory();continue}
                        // Coroutine scheduling or ownership may have stalled while this AU was held.
                        // Do not replay it late, and do not make every later AU inherit that delay.
                        if(clock.expired(plan,System.nanoTime())){
                            staleUnits++;clearHistory();timing(System.nanoTime());continue
                        }
                        // Keep source gaps for pacing/staleness/RED, but select wire timestamps
                        // from the SETUP reply. No unsent AU consumes the continuous RTP clock.
                        val timestamp=rtpClock.timestamp(plan.timestamp)
                        val packet=if(sapCrypto!=null){
                            // 两种帧密码都等长、无 tag、无 nonce 尾，所以 12 字节头之后只跟正文。
                            // CTR 的计数器由 sapCrypto 里的 Cipher 跨包连续推进；CBC-frame 反过来，
                            // 每个 AU 都用同一个初始 IV 重置（A-40），互不链接。IV 演进在 SapAudioCrypto 里。
                            check(!redEnabled){"SAP audio has no RED record implementation"}
                            MediaAudioPackets.sapPacket(event.bytes,sapCrypto,sequence,timestamp,streamType,wirePtOverride)
                        }else{
                            check(nonce<Long.MAX_VALUE){"Audio nonce exhausted"}
                            redDiagnostic.packet(event.bytes,history,historyTimestamp,key,sequence,timestamp,nonce++,streamType)
                        }
                        val packetSequence=sequence
                        // 首包把线上字节钉进日志：12 字节头 + 正文起始。
                        // 两种帧密码都出不了声之后，缺的就是"实际发出去长什么样"这一手证据
                        // （研究侧 OPEN-RUNTIME-1：没有 AirPlay 音频抓包）。只打一次，不进热路径。
                        if(packets==0L){
                            val shown=packet.copyOfRange(0,minOf(packet.size,28))
                            log("Audio first packet category="+category+" type="+streamType+
                                " wirePT="+(packet[1].toInt() and 127)+" bytes="+packet.size+
                                " head="+shown.joinToString(""){b->"%02x".format(b.toInt() and 255)})
                        }
                        val writeBegan=System.nanoTime()
                        socket.send(DatagramPacket(packet,packet.size,peer,port))
                        rtpClock.sent(samples)
                        sequence=(sequence+1) and 65535;packets++;lastSentNanos=System.nanoTime()
                        if(redEnabled){history=event.bytes;historyTimestamp=timestamp}
                        retransmit?.record(packetSequence,packet)
                        maximumWriteNanos=maxOf(maximumWriteNanos,lastSentNanos-writeBegan)
                        // 该诊断要求包长 ≥ 12+24（含 16B tag 与 8B nonce 尾），CTR 记录达不到，
                        // 强行调用会抛 IllegalArgumentException 把整路音频关掉。
                        if(sapCrypto==null)packetDiagnostics.sent(packet,event.bytes,samples,lastSentNanos)?.let{
                            log("Audio packet category="+category+" "+it.summary())
                        }
                        timing(lastSentNanos)
                        if(packets==1L||packets%200==0L)log("${"Compressed RTP category="}${category}${" type="}${streamType}${" codec="}${selected.codec}${" packets="}${packets}${"; audible acceptance pending"}")
                    }
                }
            }
        }catch(t:CancellationException){throw t}
        catch(t:Throwable){failed(t)}
        finally{key.fill(0)}
    }}
    override suspend fun send(category:Int,pcm:ByteArray,format:PcmFormat){
        require(category==this.category&&format==selected.decodedPcm&&format.accepts(pcm.size))
        check(ready){"Compressed stream closed"}
        if(!canSend())return
        if(!encoder.submit(pcm,format))log("${"Compressed audio category="}${category}${" source block dropped; encoder queue full"}")
    }
    override fun close(){
        if(!closed.compareAndSet(false,true))return
        socket.close();retransmit?.close();if(::encoder.isInitialized)encoder.close();output.cancel();scope.cancel();stopped()
    }
    companion object {
        suspend fun create(socket:DatagramSocket,peer:InetAddress,port:Int,category:Int,type:Int,
            format:CarPlayAudioPlan.Format,key:ByteArray,retransmit:AudioRtpRetransmit?,ownership:suspend ()->Unit,stopped:()->Unit,
            log:(String)->Unit,canSend:()->Boolean={true},redEnabled:Boolean=false,
            receiverHighAccuracyTimestamps:Boolean=false,sapCrypto:SapAudioCrypto?=null,
            wirePtOverride:Int?=null):CarPlayCompressedAudio {
            require(format.codec in setOf(CarPlayAudioPlan.Codec.AAC_LC,CarPlayAudioPlan.Codec.AAC_ELD,CarPlayAudioPlan.Codec.OPUS))
            val transport=CarPlayCompressedAudio(socket,peer,port,category,type,format,key,retransmit,ownership,stopped,log,redEnabled,canSend,sapCrypto,receiverHighAccuracyTimestamps,wirePtOverride)
            val bitrate=when(format.codec){CarPlayAudioPlan.Codec.AAC_LC->192000;CarPlayAudioPlan.Codec.AAC_ELD->if(format.rate<=24000)48000 else 96000;else->if(format.rate<=24000)48000 else 96000}
            try{
                log("Audio RED diagnostic category="+category+" codec="+format.codec+" negotiatedRED="+redEnabled+
                    " sendRED="+redEnabled+
                    " configuredPrimaryOnly="+transport.redDiagnostic.configured+" appliedPrimaryOnly="+transport.redDiagnostic.applied+
                    "; primary-only isolation; packet format fixed by validated SETUP negotiation")
                transport.startWriter()
                transport.encoder=CompressedAudioEncoder.create(format,bitrate,{event->
                    // Bounded, cancellable backpressure. Configuration and access units remain
                    // ordered; the encoder has already returned any MediaCodec output buffer.
                    transport.output.send(event)
                },transport::failed)
                check(transport.ready){"Encoder failed during startup"}
                log("${"Compressed encoder ready name="}${transport.encoder.encoderName}${" category="}${category}${" codec="}${format.codec}${"; network acceptance pending"}")
                return transport
            }catch(t:Throwable){transport.close();key.fill(0);throw t}
        }
    }
}
