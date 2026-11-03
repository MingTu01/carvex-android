package io.github.iamr0s.carvex.carplay
import io.github.iamr0s.carvex.sessioncore.*

import io.github.iamr0s.carvex.carplay.core.*
import io.github.iamr0s.carvex.pcm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean

/** Recovered HomeKit type100/101/102 PCM UDP writer, selected from receiver announcement and real host PCM.
 * Legacy/MFiSAP, retransmission, compressed formats and microphone are deliberately not inferred. */
class CarPlayMediaAudio(private val socket:DatagramSocket,private val peer:InetAddress,private val port:Int,
    private val pcmFormat:PcmFormat,private val category:Int,private val streamType:Int,private val key:ByteArray,private val retransmit:AudioRtpRetransmit?,private val ownership:suspend ()->Unit,
    private val stopped:()->Unit,private val log:(String)->Unit,private val redEnabled:Boolean=false,
    private val canSend:()->Boolean={true},
    receiverHighAccuracyTimestamps:Boolean=false,
    /** MFi/SAP 路线：非空时改用 [MediaAudioPackets.sapPacket]（12 字节头 + 等长变形，无 tag、无 nonce 尾）。
     *  HAP 路线传 null，行为与此前完全一致。`key` 在 SAP 下是空数组（见 AirSession），
     *  所以这里**不能**回落到 ChaCha 的 [MediaAudioPackets.packet]。 */
    private val sapCrypto:SapAudioCrypto?=null,
    /** 线上 PT 逃生阀，见 [MediaAudioPackets.wirePayloadType]。null = 规范映射（100→100）。 */
    private val wirePtOverride:Int?=null):NegotiatedAudioTransport {
    private val closed=AtomicBoolean(false)
    private val lock=Mutex()
    override val ready:Boolean get()=!closed.get()&&!socket.isClosed
    override val formats=mapOf(category to pcmFormat)
    override val microphoneFormat:PcmFormat?=null
    private var sequence=Crypto.random.nextInt(65536)
    private val rtpClock=AudioRtpTimestampClock(streamType,receiverHighAccuracyTimestamps,
        Crypto.random.nextInt().toLong() and 0xffffffffL)
    private var nonce=0L
    private var began=0L
    private var pacingFrames=0L
    private var tookOwnership=false
    private var pending=byteArrayOf()
    @Volatile private var packets=0L
    @Volatile private var lastSentNanos=0L
    override val packetsSent:Long get()=packets
    override val lastPacketNanos:Long get()=lastSentNanos
    private var history:ByteArray?=null
    private var historyTimestamp=0L
    private fun clearHistory(){history=null;historyTimestamp=0L}
    init {
        log("Audio RTP clock category="+category+" type="+streamType+
            " peerHighAccuracy="+receiverHighAccuracyTimestamps+" timestampMode="+rtpClock.mode.diagnosticName+
            "; source pacing independent; high-accuracy network-clock anchors not implemented")
        log("Audio RED diagnostic category="+category+" codec=PCM16 negotiatedRED="+redEnabled+
            " sendRED="+redEnabled+"; packet format fixed by validated SETUP negotiation")
        if(sapCrypto!=null)log("Audio crypto category="+category+" mode="+sapCrypto.modeName+"(MFi/SAP)"+
            " type="+streamType+" payloadOnly=true tagBytes=0 nonceTail=0 wirePT="+
            MediaAudioPackets.wirePayloadType(streamType,wirePtOverride)+
            "; frame cipher follows A-44 type/G dispatch")
    }
    override suspend fun send(category:Int,pcm:ByteArray,format:PcmFormat){
        require(category==this.category&&format==pcmFormat&&format.accepts(pcm.size))
        lock.lock()
        try{
            check(ready){"Audio stream closed"}
            if(!canSend()){pending=byteArrayOf();clearHistory();return}
            if(!tookOwnership){ownership();tookOwnership=true}
            val framesPerPacket=pcmFormat.rate/200
            val packetBytes=framesPerPacket*pcmFormat.channels*2
            if(began==0L)began=System.nanoTime()
            val now=System.nanoTime()
            // Rebase only local pacing after a source pause. Ordinary main/alt RTP
            // advances by successfully sent samples, not by missing wall-clock time.
            if(now>began+pacingFrames*1_000_000_000L/pcmFormat.rate+100_000_000L){
                val elapsed=(now-began)*pcmFormat.rate/1_000_000_000L
                pacingFrames=elapsed;pending=byteArrayOf();clearHistory()
                log("Media audio source gap; local pacing rebased; timestampMode="+rtpClock.mode.diagnosticName)
            }
            pending+=pcm
            var offset=0
            while(pending.size-offset>=packetBytes){
                currentCoroutineContext().ensureActive();check(ready)
                val due=began+pacingFrames*1_000_000_000L/pcmFormat.rate
                val delayNs=due-System.nanoTime()
                if(delayNs>0)delay((delayNs+999999)/1000000)
                if(!canSend()){pending=byteArrayOf();clearHistory();return}
                val timestamp=rtpClock.timestamp(pacingFrames)
                try{
                    val data=MediaAudioPackets.networkPcm(pending.copyOfRange(offset,offset+packetBytes))
                    // SAP：帧密码由 SapAudioCrypto 决定（A-44 分派）。raw 100/101 在 G=0 下
                    // 没有密码器，所以这里的 PLAINTEXT 是**规范行为**而不是诊断降级；
                    // 12 字节头照旧，正文就是 BE PCM。RED 在 SAP 下不谈判（offeredRed=false）。
                    val wire=if(sapCrypto!=null){
                        check(!redEnabled){"SAP audio has no RED record implementation"}
                        MediaAudioPackets.sapPacket(data,sapCrypto,sequence,timestamp,streamType,wirePtOverride)
                    }else{
                        check(nonce<Long.MAX_VALUE){"Audio nonce exhausted; new stream needed"}
                        if(redEnabled)
                            MediaAudioPackets.redPacket(data,history,historyTimestamp,key,sequence,timestamp,nonce++,streamType)
                        else MediaAudioPackets.packet(data,key,sequence,timestamp,nonce++,streamType)
                    }
                    val packetSequence=sequence
                    socket.send(DatagramPacket(wire,wire.size,peer,port))
                    // Commit immediately after the local UDP write succeeds, before diagnostics.
                    // A used nonce is never rolled back, including a failed send.
                    rtpClock.sent(framesPerPacket)
                    sequence=(sequence+1) and 65535;packets++;lastSentNanos=System.nanoTime()
                    if(redEnabled){history=data;historyTimestamp=timestamp}
                    retransmit?.record(packetSequence,wire)
                    if(packets==1L)log("Audio first RTP category="+category+" type="+streamType+
                        " pt="+(wire[1].toInt() and 127)+" seq="+packetSequence+" timestamp="+(timestamp and 0xffffffffL)+
                        " frames="+framesPerPacket+" bytes="+wire.size+
                        (if(sapCrypto!=null)" crypto="+sapCrypto.modeName+" tagBytes=0 nonceTail=0"
                         else " aad=4-12 remotePort="+port)+
                        " head="+wire.take(24).joinToString(""){b->"%02x".format(b.toInt() and 0xff)})
                }catch(t:Throwable){
                    // Do not reuse an uncertain send, or replay pending PCM if a diagnostic
                    // throws after the packet has already been committed.
                    close();throw t
                }
                pacingFrames+=framesPerPacket;offset+=packetBytes
                if(packets==1L||packets%200==0L)log("${"CarPlay audio category="}${category}${" type="}${streamType}${" RTP sent packets="}${packets}${" format="}${pcmFormat.rate}${"/"}${pcmFormat.channels}${"; audible acceptance pending"}")
            }
            pending=pending.copyOfRange(offset,pending.size)
        }finally{lock.unlock()}
    }
    override fun close(){
        if(!closed.compareAndSet(false,true))return
        socket.close();retransmit?.close()
        // Key is wiped after any synchronous encrypt/send section has exited.
        CoroutineScope(Dispatchers.IO).launch{lock.lock();try{key.fill(0);pending=byteArrayOf()}finally{lock.unlock()}}
        stopped()
    }
}
