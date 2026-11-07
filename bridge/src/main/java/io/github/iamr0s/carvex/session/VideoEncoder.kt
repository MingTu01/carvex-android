package io.github.iamr0s.carvex.session
import io.github.iamr0s.carvex.sessioncore.*

import android.media.*
import android.os.Bundle
import android.view.Surface
import io.github.iamr0s.carvex.carplay.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.*

class VideoEncoder(val width:Int,val height:Int,fps:Int,bitrate:Int,hostFormat:Bundle?,
    private val resources:Resources,private val send:(ByteArray,Boolean,Long?)->Unit,private val metric:(Int)->Unit,
    private val failure:(Throwable)->Unit,private val diagnostic:(String,Long)->Unit={_,_->},
    private val stopSend:()->Unit={},
    /** Builds the GPU front surface. It is the only way a still picture gets back into the encoder
     *  when the source is not ours to repaint. It costs a full-screen blit per frame and one extra
     *  buffer hop, which is why it is a switch at all - but the default is on, because a video
     *  timeline that stands still while audio keeps advancing comes back as an A/V offset the
     *  receiver has to reconcile by dropping frames. See [IDLE_STILL_AFTER_MS] for the cadence. */
    private val frontSurface:Boolean):AutoCloseable {
    companion object {
        /** Cadence of the still-picture media heartbeat once the picture is known to be still.
         *  A picture can stay still for a long time, and the silence that follows is what a
         *  receiver reports as a lost video, so one repeat a second keeps packets flowing at the
         *  cost of a single tiny P frame. */
        const val IDLE_REPEAT_INTERVAL_MS=1_000L
        /** 静默多久就认定画面停了。
         *
         *  比 [IDLE_REPEAT_INTERVAL_MS] 短是**故意的**：补帧的意义不只是"别让流看起来断了"，
         *  更是"别让视频时间戳掉队"。静止期间音频的 RTP 时间戳一直在往前走，视频这边要是
         *  整秒不动，恢复播放时接收端面对的就是一个已经攒下来的 A/V 偏差，只能靠丢帧追回来
         *  —— 表现就是画面一动先卡一下。半个秒够区分"一次短暂的停顿"和"真的停住了"：
         *  30 fps 的正常出帧间隔是 33 ms，任何小于 500 ms 的空档都不会触发补帧。 */
        const val IDLE_STILL_AFTER_MS=500L
        /** 心跳检查周期。
         *
         *  必须明显短于 [IDLE_STILL_AFTER_MS]，否则 500 ms 这个阈值根本落不到地——定时器只有
         *  每秒才醒一次，最早也只能在 1 s 补帧。这次检查只是读两个 Long 做比较，不开线程、
         *  不分配对象，四次每秒的代价可以忽略。 */
        const val IDLE_TICK_MS=250L
        /** `outputFormat` 报的 profile 既可能是 [MediaCodecInfo.CodecProfileLevel] 的位标志
         *  （1/2/8…），也可能是原始 `profile_idc`（66/77/100）—— 不同厂商各写各的。
         *
         *  两种都认，并且**把原始数字一起打出来**：认错名字最多难看，把 66 说成 Baseline、
         *  把 100 说成 High 都还能对上，但把 32 当成"Level 2"当"Level 3.2"就会误导判断。
         *  所以标签只做辅助，括号里的数字才是判据。 */
        fun avcProfileLabel(value:Int):String {
            val name=when(value){
                0x01,66->"Baseline";0x02,77->"Main";0x04,88->"Extended"
                0x08,100->"High";0x10,110->"High10";0x20,122->"High422";0x40,244->"High444"
                else->null
            }
            return when{
                value<=0->"未报"
                name!=null->name+"("+value+")"
                value in 10..62->(value/10).toString()+"."+(value%10)+"(idc)"
                else->"#"+value
            }
        }
        fun avcLevelLabel(value:Int):String {
            val name=when(value){
                0x01->"1";0x02->"1b";0x04->"1.1";0x08->"1.2";0x10->"1.3"
                0x20->"2";0x40->"2.1";0x80->"2.2"
                0x100->"3";0x200->"3.1";0x400->"3.2"
                0x800->"4";0x1000->"4.1";0x2000->"4.2"
                0x4000->"5";0x8000->"5.1";0x10000->"5.2"
                0x20000->"6";0x40000->"6.1";0x80000->"6.2"
                else->null
            }
            return when{
                value<=0->"未报"
                name!=null->name+"("+value+")"
                value in 10..62->(value/10).toString()+"."+(value%10)+"(idc)"
                else->"#"+value
            }
        }
        fun bitrateModeLabel(value:Int):String = when(value){
            -1->"未报";0->"VBR";1->"CBR";2->"CQ"
            else->"#"+value
        }
        private data class Candidate(val info:MediaCodecInfo,val caps:MediaCodecInfo.CodecCapabilities)
        private fun candidates(hostFormat:Bundle?):List<Candidate> {
            return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter{it.isEncoder&&it.supportedTypes.any{type->type.equals(MediaFormat.MIMETYPE_VIDEO_AVC,true)}}
                .mapNotNull{info->runCatching{
                    val caps=info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    if(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats)return@runCatching null
                    val profile=hostFormat?.takeIf{it.containsKey("profile")}?.getInt("profile")
                    val level=hostFormat?.takeIf{it.containsKey("level")}?.getInt("level")
                    if(profile!=null&&!caps.profileLevels.any{it.profile==profile&&(level==null||it.level>=level)})return@runCatching null
                    if(profile==null&&level!=null&&!caps.profileLevels.any{it.level>=level})return@runCatching null
                    val mode=hostFormat?.takeIf{it.containsKey("bitrate-mode")}?.getInt("bitrate-mode")
                    if(mode!=null&&caps.encoderCapabilities?.isBitrateModeSupported(mode)!=true)return@runCatching null
                    Candidate(info,caps)
                }.getOrNull()}
                .sortedByDescending{it.info.isHardwareAccelerated}
        }
        /** Probe all AVC Surface encoders, bounded by actual size/rate and host configuration. */
        fun supportedFps(hostFormat:Bundle?=null):(Int,Int,Int)->Int? {
            val available=candidates(hostFormat)
            return {width,height,limit->
                available.mapNotNull{candidate->runCatching{
                    val video=candidate.caps.videoCapabilities?:return@runCatching null
                    if(!video.isSizeSupported(width,height))return@runCatching null
                    val range=video.getSupportedFrameRatesFor(width,height)
                    val upper=minOf(limit,kotlin.math.floor(range.upper).toInt())
                    val lower=maxOf(1,kotlin.math.ceil(range.lower).toInt())
                    if(upper<lower)null else (upper downTo lower).firstOrNull{video.areSizeAndRateSupported(width,height,it.toDouble())}
                }.getOrNull()}.maxOrNull()
            }
        }
        /** Widest bitrate range the AVC Surface encoders here offer. The settings screen sizes its
         *  slider from this instead of an invented ceiling, so a device that can do more is not held
         *  back and one that can do less is visible before a session starts. Null when none is
         *  usable, which leaves the caller its own fallback. */
        fun supportedBitrate(hostFormat:Bundle?=null):IntRange? {
            val ranges=candidates(hostFormat).mapNotNull{it.caps.videoCapabilities?.bitrateRange}
            if(ranges.isEmpty())return null
            return maxOf(1,ranges.minOf{it.lower})..ranges.maxOf{it.upper}
        }
        private fun createCompatibleCodec(width:Int,height:Int,fps:Int,hostFormat:Bundle?):MediaCodec {
            val errors=mutableListOf<Exception>()
            for(candidate in candidates(hostFormat)){
                if(runCatching{candidate.caps.videoCapabilities?.areSizeAndRateSupported(width,height,fps.toDouble())==true}.getOrDefault(false)){
                    try{return MediaCodec.createByCodecName(candidate.info.name)}catch(t:Exception){errors.add(t)}
                }
            }
            throw IllegalStateException("${"No available AVC Surface encoder for "}${width}${"x"}${height}${"@"}${fps}${" with host profile/level/mode"}").also{error->errors.forEach{error.addSuppressed(it)}}
        }
    }
    private val codec=createCompatibleCodec(width,height,fps,hostFormat)
    private val closed=AtomicBoolean(false)
    private val producing=AtomicBoolean(true)
    private val failureReported=AtomicBoolean(false)
    private val writeDeadline=WriteDeadline(TimeUnit.SECONDS.toNanos(8))
    private var watchdog:ScheduledExecutorService?=null
    private val keyRequested=AtomicBoolean(false)
    private val refreshGeneration=AtomicLong(1)
    private val refreshLock=Any()
    private val rateLock=Any()
    private val pendingBitrate=AtomicInteger(0)
    private val ratePolicy:VideoRatePolicy
    val initialBitrate:Int
    val encoderName:String get()=codec.name
    val hardwareAccelerated:Boolean get()=codec.codecInfo.isHardwareAccelerated
    /** 实际生效的编码参数，只有 [start] 之后才拿得到 —— MediaCodec 在校验通过前不给 outputFormat。
     *
     *  为什么单独采这三项：车机没有显式要求 `profile`/`level`/`bitrate-mode` 时，它们取编码器的
     *  默认值，而**同一个码率下 Baseline 和 High 的差距是肉眼可见的**（CABAC、8×8 变换、更强的
     *  运动矢量预测全都没有）。所以"画面糊"必须拆成两个变量看：码率给够没有、给的是什么 profile。
     *  以前这两件事在诊断里只有一个答案，等于没法判断。 */
    private fun outputInt(key:String):Int=runCatching{codec.outputFormat.getInteger(key)}.getOrDefault(-1)
    val avcProfile:Int get()=outputInt(MediaFormat.KEY_PROFILE)
    val avcLevel:Int get()=outputInt(MediaFormat.KEY_LEVEL)
    val avcBitrateMode:Int get()=outputInt(MediaFormat.KEY_BITRATE_MODE)
    /** 编码参数的短标签，给诊断面板和 logcat 用。取不到就写「未报」，不猜。 */
    fun avcParams():String = "profile="+avcProfileLabel(avcProfile)+
        " level="+avcLevelLabel(avcLevel)+" mode="+bitrateModeLabel(avcBitrateMode)
    @Volatile private var bitrateSupported=true
    private var producer:Job?=null
    private var consumer:Job?=null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private data class Packet(val bytes:ByteArray,val config:Boolean,val key:Boolean,val generation:Long,val ptsMonoUs:Long?=null,val queuedAt:Long=System.nanoTime())
    /** Encoded packets waiting for the socket. The capacity is how much a slow socket may
     *  accumulate before the encoder is asked to wait: at 60fps twelve packets is about 200ms of
     *  latency, which is the trade made here - bursts are tolerated, and a backlog that persists is
     *  paid for in delay. */
    private val queue=Channel<Packet>(12)
    private val heartbeat=VideoIdleHeartbeat(IDLE_REPEAT_INTERVAL_MS,IDLE_STILL_AFTER_MS)
    /** Resubmits the current picture while the encoder is quiet, for a source that can repaint on
     *  request. With no front surface built, this is the only repeat available. */
    @Volatile var repeatFrame:(()->Unit)?=null
    @Volatile private var recover=true
    private val codecSurface:Surface
    /** Null when EGL is unavailable here; the codec surface then goes straight to the picture source. */
    private val front:EncoderFrontSurface?
    val surface:Surface
    init {
        try {
            val caps=codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            require(requireNotNull(caps.videoCapabilities).areSizeAndRateSupported(width,height,fps.toDouble())){"Encoder cannot support negotiated size/FPS"}
            val supportedRange=requireNotNull(caps.videoCapabilities).bitrateRange
            val minimum=maxOf(1,supportedRange.lower)
            val maximum=supportedRange.upper
            require(minimum<=maximum){"No AVC bitrate intersection with configured range"}
            ratePolicy=VideoRatePolicy(bitrate,minimum,maximum)
            initialBitrate=ratePolicy.configure(bitrate)
            val format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height).apply{
                setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE,initialBitrate);setInteger(MediaFormat.KEY_FRAME_RATE,fps)
                // Real-time projection: avoid reference reordering and B-frame latency.
                setInteger(MediaFormat.KEY_MAX_B_FRAMES,0)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER,fps.toFloat())
                // Repeat the previous frame after one frame interval, not after the heartbeat
                // interval: this is what holds the output cadence at the configured frame rate when
                // the picture source submits less often than that. Widening it to a second silently
                // drops the output to whatever the source happens to produce. The still-picture
                // heartbeat is a separate path ([repeatFrame]) and needs no stretched hint.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,(1_000_000L/fps).coerceAtLeast(1))
            }
            if(hostFormat!=null){
                for(key in listOf("profile","level","bitrate-mode","i-frame-interval")){
                    if(hostFormat.containsKey(key)){
                        val v=hostFormat.getInt(key)
                        when(key){
                            "profile"->require(caps.profileLevels.any{it.profile==v}){"Unsupported host AVC profile"}
                            "level"->require(caps.profileLevels.any{it.level>=v}){"Unsupported host AVC level"}
                            "bitrate-mode"->require(requireNotNull(caps.encoderCapabilities).isBitrateModeSupported(v))
                        };format.setInteger(key,v)
                    }
                }
            }
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
            codecSurface=codec.createInputSurface();codec.start()
        }catch(t:Throwable){codec.release();throw t}
        // Only built on request: the front surface is what lets a still picture be resubmitted, but it
        // also costs a full-screen blit per frame and a few frames of added latency, and a source that
        // repaints on request needs neither.
        front=if(frontSurface)EncoderFrontSurface.create(width,height,codecSurface){message->
            // 前置画布把"源提交了多少帧"用 source-frames=N 报上来，单独映射成一条可读日志：
            // 有心跳但源一直是 0，说明宿主根本没画，问题不在我们这一侧。
            if(message.startsWith("source-frames="))diagnostic("front-source",message.removePrefix("source-frames=").toLongOrNull()?:0L)
            else diagnostic(message,1)
        } else null
        front?.setMinInterval(1_000_000_000L/fps)
        surface=front?.surface?:codecSurface
    }
    /** True when a still picture has a way back into the encoder. */
    private fun canRepeat()=front!=null||repeatFrame!=null
    /** Resubmits the current picture through whichever path owns it. */
    private fun repeatPicture(){
        val frontSurface=front
        if(frontSurface!=null)frontSurface.repeat() else repeatFrame?.invoke()
    }
    private fun reportFailure(t:Throwable){
        if(!closed.get()&&failureReported.compareAndSet(false,true)){
            // Cleanup joins workers; never invoke it from a worker that it must join.
            Thread({if(!closed.get())failure(t)},"video-failure").apply{isDaemon=true;start()}
        }
    }
    @Synchronized fun start(){
        check(!closed.get()&&producer==null&&consumer==null){"Video encoder closed or already started"}
        var stallStarted=0L
        watchdog=Executors.newSingleThreadScheduledExecutor{r->Thread(r,"video-write-deadline").apply{isDaemon=true}}.also{timer->
            timer.scheduleAtFixedRate({
                val now=System.nanoTime()
                val age=writeDeadline.ageNanos(now)
                if(!closed.get()&&age!=null&&age>8_000_000_000L){
                    // A blocked write alone does not distinguish a slow receiver from a failed
                    // link. Report it while independent control/audio workers continue running.
                    if(stallStarted==0L){
                        stallStarted=now
                        diagnostic(VideoDiagnosticLog.WRITE_STALLED_MS,age/1_000_000L)
                    }
                    if(now-stallStarted>60_000_000_000L){
                        reportFailure(java.net.SocketTimeoutException("Video socket write stalled for 60 seconds"))
                        runCatching{stopSend()}
                    }
                }else stallStarted=0L
            },500,500,TimeUnit.MILLISECONDS)
            // Still-picture heartbeat, on a fixed tick so the packet gap cannot drift with the
            // encoder output. The repeat itself is cheap: the picture did not change, so the codec
            // codes it as a skip P frame.
            //
            // 周期是 IDLE_TICK_MS 而不是补帧间隔：阈值定在 500 ms，定时器就得比它醒得更勤，
            // 否则最早的补帧也只能落在一秒后，阈值等于没写。真正决定节奏的是 heartbeat.beat()，
            // 这里只负责按时去问一句。
            timer.scheduleAtFixedRate({
                // Nothing to repeat means no heartbeat to run: reporting one would claim work that
                // nobody performs.
                if(!closed.get()&&producing.get()&&canRepeat()&&heartbeat.beat(System.nanoTime()/1_000_000)){
                    // A refused repeat must not cancel the timer task itself.
                    runCatching{diagnostic("idle-repeat",1);repeatPicture()}.onFailure{runCatching{diagnostic("idle-repeat-failed",1)}}
                }
            },IDLE_TICK_MS,IDLE_TICK_MS,TimeUnit.MILLISECONDS)
        }
        consumer=scope.launch {
            // Queue latency remains controlled by backpressure. The gate here is solely the
            // config/actual-IDR dependency gate, so it does not add a second age-drop policy.
            val delivery=VideoDeliveryGate(Long.MAX_VALUE)
            var generation=0L
            try {
                for(packet in queue){
                    if(packet.generation!=refreshGeneration.get()){diagnostic("dropped",1);continue}
                    if(generation!=packet.generation){delivery.reset();generation=packet.generation}
                    val age=(System.nanoTime()-packet.queuedAt)/1_000_000
                    diagnostic("queue-ms",age)
                    if(delivery.decide(packet.config,packet.key,age)!=VideoDeliveryGate.Decision.SEND){diagnostic("dropped",1);continue}
                    // Keep the complete reference chain, including the animation's final frame.
                    // Bounded backpressure applies upstream instead of abandoning pending pictures.
                    val started=System.nanoTime()
                    val ticket=writeDeadline.begin(started)
                    try{send(packet.bytes,packet.config,packet.ptsMonoUs)}finally{writeDeadline.complete(ticket)}
                    val writeMs=(System.nanoTime()-started)/1_000_000
                    diagnostic("write-ms",writeMs)
                    if(!packet.config){
                        metric(packet.bytes.size)
                        synchronized(rateLock){if(bitrateSupported)ratePolicy.sample(System.nanoTime()/1_000_000,age,writeMs)?.let{pendingBitrate.set(it)}}
                    }
                }
            }catch(t:Exception){if(!closed.get())reportFailure(t)}
        }
        producer=scope.launch {
            val configuration=AvcConfiguration()
            val timeline=SurfaceFrameTimeline()
            // Builds the configuration packet and arms recovery without ever blocking, so it is safe
            // to call while a codec output buffer is still held. The caller sends it afterwards.
            fun configurationPacket():Packet?{
                val changed=configuration.takeChanged()?:return null
                recover=true;requestIdr()
                return Packet(VideoWire.config(changed,width,height),true,false,refreshGeneration.get())
            }
            try {
                val info=MediaCodec.BufferInfo()
                while(isActive&&!closed.get()&&producing.get()){
                    val requestedRate=pendingBitrate.getAndSet(0)
                    if(requestedRate>0&&bitrateSupported){
                        try{
                            codec.setParameters(Bundle().apply{putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE,requestedRate)})
                            diagnostic("bitrate-applied",requestedRate.toLong())
                        }catch(t:IllegalArgumentException){bitrateSupported=false;diagnostic("bitrate-rejected",requestedRate.toLong())}
                    }
                    val requestedGeneration=synchronized(refreshLock){
                        if(keyRequested.getAndSet(false))refreshGeneration.get() else null
                    }
                    if(requestedGeneration!=null){
                        // A force-keyframe/source refresh invalidates queued dependencies. Re-send
                        // this encoder's current configuration before accepting an actual IDR.
                        configuration.current()?.let{queue.send(Packet(VideoWire.config(it,width,height),true,false,requestedGeneration))}
                        try{codec.setParameters(Bundle().apply{putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0)})}
                        catch(t:IllegalArgumentException){diagnostic("idr-rejected",1)}
                    }
                    val outputGeneration=refreshGeneration.get()
                    val i=codec.dequeueOutputBuffer(info,10000)
                    if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                        val f=codec.outputFormat
                        fun bytes(buffer:ByteBuffer)=ByteArray(buffer.remaining()).also{buffer.duplicate().get(it)}
                        for(name in listOf("csd-0","csd-1"))f.getByteBuffer(name)?.let{configuration.ingest(bytes(it))}
                        configurationPacket()?.let{queue.send(it)}
                    }else if(i>=0){
                        // Nothing below the release may block: holding a codec output buffer while
                        // waiting on a slow socket stalls the encoder for no reason. The frame bytes
                        // are already copied out, so hand the buffer back first, queue afterwards.
                        var configPacket:Packet?=null
                        var framePacket:Packet?=null
                        try {
                            if(info.size>0){
                                val buf=requireNotNull(codec.getOutputBuffer(i)).duplicate().apply{position(info.offset);limit(info.offset+info.size)}
                                val data=ByteArray(info.size).also{buf.get(it)}
                                val type=data[0].toInt() and 31
                                val units=if(type==7||type==8)listOf(data) else VideoWire.splitNal(data)
                                configuration.ingestUnits(units)
                                configPacket=configurationPacket()
                                // One pass, one NAL type read per unit, no intermediate list:
                                // this runs for every encoded frame.
                                val frameUnits=ArrayList<ByteArray>(units.size)
                                var picture=false;var key=false
                                for(unit in units){
                                    val unitType=unit[0].toInt() and 31
                                    if(unitType==7||unitType==8)continue
                                    frameUnits+=unit
                                    if(unitType in 1..5)picture=true
                                    if(unitType==5)key=true
                                }
                                if(picture){
                                    val ptsMonoUs=timeline.map(info.presentationTimeUs,System.nanoTime()/1000)
                                    diagnostic("encoded",1)
                                    if(configuration.ready()&&(!recover||key)){
                                        framePacket=Packet(VideoWire.lengthPrefixed(frameUnits),false,key,configPacket?.generation?:outputGeneration,ptsMonoUs)
                                    }else diagnostic("dropped",1)
                                }
                            }
                        }finally{codec.releaseOutputBuffer(i,false)}
                        configPacket?.let{queue.send(it)}
                        framePacket?.let{queue.send(it);if(it.key&&it.generation==refreshGeneration.get())recover=false;heartbeat.frame(System.nanoTime()/1_000_000)}
                    }
                }
            }catch(t:Exception){if(!closed.get())reportFailure(t)}
        }
    }
    fun setFrameRate(value:Int):Boolean {
        require(value>0)
        if(closed.get()||!producing.get())return false
        return runCatching{
            codec.setParameters(Bundle().apply{
                putFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER,value.toFloat())
                putInt(MediaFormat.KEY_FRAME_RATE,value)
                // The repeat hint is an absolute time, so it has to follow the frame rate as well:
                // leaving it at the value derived from the original rate keeps the old cadence alive
                // and contradicts the input limit set just above.
                putLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,(1_000_000L/value).coerceAtLeast(1))
            })
            // The codec is free to ignore the frame-rate parameters above - this device does - so the
            // front surface enforces the rate where it can be enforced: by not submitting frames
            // faster than this.
            front?.setMinInterval(1_000_000_000L/value)
            requestIdr()
            true
        }.getOrDefault(false)
    }

    fun setBitrate(value:Int):Boolean {
        require(value>0)
        if(closed.get()||!producing.get()||!bitrateSupported)return false
        synchronized(rateLock){pendingBitrate.set(ratePolicy.configure(value))}
        return true
    }
    fun requestIdr():Boolean {
        if(closed.get()||!producing.get())return false
        synchronized(refreshLock){
            if(!keyRequested.get()){
                recover=true;refreshGeneration.incrementAndGet();keyRequested.set(true);diagnostic("idr",1)
            }
        }
        return true
    }
    @Synchronized override fun close(){
        if(!closed.compareAndSet(false,true))return
        producing.set(false);watchdog?.shutdownNow()
        // A normal stop/failure must unblock a socket write before joining its writer.
        runCatching{stopSend()}
        scope.cancel();queue.cancel()
        runBlocking{producer?.join();consumer?.join()}
        runCatching{front?.close()}
        runCatching{codec.stop()};runCatching{codec.release()};runCatching{codecSurface.release()}
    }
}
