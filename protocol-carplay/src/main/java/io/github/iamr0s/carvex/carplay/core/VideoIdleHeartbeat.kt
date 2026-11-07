package io.github.iamr0s.carvex.carplay.core
/** Decides when a quiet surface-input encoder must have its last frame resubmitted.
 *
 * MediaCodec produces output only while buffers arrive on its input surface, so a picture that
 * stops changing turns into a media stream that stops as well: the control channel keeps its
 * OPTIONS heartbeat while the RTP flow goes silent, and a receiver then reports the video as lost.
 * Resubmitting the current picture is the only surface-input way to make the codec emit a packet
 * at all, and because the picture did not change the codec produces a tiny repeat P frame - never
 * an I frame - whose timestamp advances like any other frame's, since it travels through the codec
 * instead of being patched into the stream afterwards.
 *
 * **Two-speed cadence.** A still picture is the case that matters, but it is not the case that
 * needs a whole second to be recognised: audio keeps advancing its own timestamps through the
 * whole pause, so a video timeline that stands still for seconds comes back as an A/V offset the
 * receiver has to reconcile by dropping frames - which is exactly the hitch seen when the picture
 * starts moving again. [stillAfterMs] therefore decides "this picture has stopped", and it is
 * deliberately shorter than [intervalMs]; the steady cadence after that first nudge is
 * [intervalMs], because a picture that stays still only needs one packet per interval to hold its
 * place in the timeline.
 *
 * The caller owns the tick: it asks [beat] on a period shorter than both intervals and repeats
 * when told to. A repeat that comes back as an encoded frame is recognised as the answer to the
 * last request rather than as the picture moving again, which is what keeps that cadence from
 * collapsing into one packet every other interval; a picture that really moves keeps the stream
 * to itself again immediately. */
class VideoIdleHeartbeat(
    private val intervalMs:Long=1_000,
    /** 静默多久就认定"画面停了"，并补第一帧。
     *
     *  必须小于等于 [intervalMs]：它定的是**发现静止**的速度，[intervalMs] 定的是静止之后
     *  的续命节奏。默认相等 —— 没有更细的 tick 时，两者本来就是一回事。 */
    private val stillAfterMs:Long=intervalMs,
    /** 补帧请求发出后多久之内回来的帧算"那次补发的回声"，不算画面重新动起来。
     *
     *  默认取 [stillAfterMs] 的四分之一：回声窗必须明显短于静止判定窗，否则一次补发的回声
     *  会把静止计时重置，节奏就塌了。 */
    private val echoMs:Long=stillAfterMs/4,
    /** 允许早于 [intervalMs] 多少毫秒就算"到点了"。
     *
     *  定时器按固定周期跑，触发点不可能刚好落在整点上：没有这点余量，一次早 1 ms 的触发会被
     *  拒掉，再等就是整整一个周期，节奏直接变成两倍。留 50 ms 既容忍抖动，又不会把节奏压快。 */
    private val earlyToleranceMs:Long=50) {
    init {
        require(intervalMs>0){"Heartbeat interval must be positive"}
        require(stillAfterMs in 1..intervalMs){"Stillness threshold must be positive and no longer than the interval"}
        require(echoMs in 0 until stillAfterMs){"Echo window must be shorter than the stillness threshold"}
        require(earlyToleranceMs in 0 until intervalMs){"Early tolerance must be shorter than the interval"}
    }
    private var lastFrameMs:Long?=null
    private var lastBeatMs:Long?=null
    /** Records an encoded frame, whether it came from the picture source or from a repeat. */
    @Synchronized fun frame(nowMs:Long){
        val beat=lastBeatMs
        // A frame this close behind a repeat request is that repeat coming back, not the picture
        // moving, so it must not restart the quiet period.
        if(beat==null||nowMs-beat>echoMs)lastFrameMs=nowMs
    }
    /** Whether the still picture needs another repeat now. */
    @Synchronized fun beat(nowMs:Long):Boolean {
        // Nothing encoded yet means nothing to repeat, and a repeat cannot start the stream.
        val frame=lastFrameMs?:return false
        // 第一帧补发看的是"停了多久"（stillAfterMs），之后的续命按 intervalMs 走。
        val quietSince=nowMs-frame
        val beat=lastBeatMs
        if(beat==null){if(quietSince<stillAfterMs)return false}
        else if(quietSince<intervalMs)return false
        // 上一次补发到现在还没满一个周期（留一点提前量）就不再补：节奏由这里定，不由 tick 定。
        if(beat!=null&&nowMs-beat<intervalMs-earlyToleranceMs)return false
        lastBeatMs=nowMs
        return true
    }
}
