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
 *  [sapCipher] 非空时切换到 MFi/SAP 路线：同一套 12 字节头 + AES-CTR（等长、无 tag、无 nonce 尾），
 *  计数器由该 Cipher 自带并跨包连续。HAP 路线传 null，行为与此前完全一致。 */
class CarPlayCompressedAudio private constructor(private val socket:DatagramSocket,private val peer:InetAddress,
    private val port:Int,private val category:Int,private val streamType:Int,private val selected:CarPlayAudioPlan.Format,
    private val key:ByteArray,private val retransmit:AudioRtpRetransmit?,private val ownership:suspend ()->Unit,private val stopped:()->Unit,
    private val log:(String)->Unit,private val redEnabled:Boolean,
    private val canSend:()->Boolean,private val sapCipher:Cipher?,receiverHighAccuracyTimestamps:Boolean):NegotiatedAudioTransport {
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
        if(sapCipher!=null)log("Audio crypto category="+category+" mode=AES-CTR(MFi/SAP) redEnabled="+redEnabled+
            " payloadOnly=true tagBytes=0 nonceTail=0; MFi audio wire profile has no packet-capture evidence")
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
                        val packet=if(sapCipher!=null){
                            // CTR 没有 tag/nonce，ChaCha 的 nonce 计数在这里没有意义；
                            // 计数器由 sapCipher 自己推进，不需要每包换 IV。
                            check(!redEnabled){"SAP audio has no RED record implementation"}
                            MediaAudioPackets.sapPacket(event.bytes,sapCipher,sequence,timestamp,streamType)
                        }else{
                            check(nonce<Long.MAX_VALUE){"Audio nonce exhausted"}
                            redDiagnostic.packet(event.bytes,history,historyTimestamp,key,sequence,timestamp,nonce++,streamType)
                        }
                        val packetSequence=sequence
                        val writeBegan=System.nanoTime()
                        socket.send(DatagramPacket(packet,packet.size,peer,port))
                        rtpClock.sent(samples)
                        sequence=(sequence+1) and 65535;packets++;lastSentNanos=System.nanoTime()
                        if(redEnabled){history=event.bytes;historyTimestamp=timestamp}
                        retransmit?.record(packetSequence,packet)
                        maximumWriteNanos=maxOf(maximumWriteNanos,lastSentNanos-writeBegan)
                        // 该诊断要求包长 ≥ 12+24（含 16B tag 与 8B nonce 尾），CTR 记录达不到，
                        // 强行调用会抛 IllegalArgumentException 把整路音频关掉。
                        if(sapCipher==null)packetDiagnostics.sent(packet,event.bytes,samples,lastSentNanos)?.let{
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
            receiverHighAccuracyTimestamps:Boolean=false,sapCipher:Cipher?=null):CarPlayCompressedAudio {
            require(format.codec in setOf(CarPlayAudioPlan.Codec.AAC_LC,CarPlayAudioPlan.Codec.AAC_ELD,CarPlayAudioPlan.Codec.OPUS))
            val transport=CarPlayCompressedAudio(socket,peer,port,category,type,format,key,retransmit,ownership,stopped,log,redEnabled,canSend,sapCipher,receiverHighAccuracyTimestamps)
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
