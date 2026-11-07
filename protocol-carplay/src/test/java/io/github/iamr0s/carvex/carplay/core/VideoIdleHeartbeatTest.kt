package io.github.iamr0s.carvex.carplay.core
import org.junit.Assert.*
import org.junit.Test
class VideoIdleHeartbeatTest {
    @Test fun nothingIsRepeatedBeforeTheFirstFrame(){
        val h=VideoIdleHeartbeat(1000)
        // A repeat only repeats what the codec already produced; it cannot start the stream.
        assertFalse(h.beat(0))
        assertFalse(h.beat(60_000))
    }
    @Test fun stillPictureRepeatsOncePerInterval(){
        val h=VideoIdleHeartbeat(1000)
        h.frame(0)
        assertFalse(h.beat(999))
        assertTrue(h.beat(1000))
        assertFalse(h.beat(1500))
        assertTrue(h.beat(2000))
        assertFalse(h.beat(2500))
        assertTrue(h.beat(3000))
    }
    @Test fun theRepeatItselfDoesNotStretchTheCadence(){
        // The still picture asks for a repeat every interval, and the codec answers a few
        // milliseconds later. That answer is the repeat coming back, not the picture moving, so it
        // must not push the next repeat a whole interval further out.
        val h=VideoIdleHeartbeat(1000)
        h.frame(0)
        assertTrue(h.beat(1000))
        h.frame(1060)
        assertTrue(h.beat(2000))
        h.frame(2075)
        assertTrue(h.beat(3000))
    }
    @Test fun aStillPictureProducesExactlyOneRepeatPerTickForAsLongAsItStaysStill(){
        val h=VideoIdleHeartbeat(1000)
        h.frame(0)
        for(tick in 1..10){
            val now=tick*1000L
            assertTrue("tick $tick must repeat",h.beat(now))
            // Encoder latency: the repeat comes back a few milliseconds after the request.
            h.frame(now+40)
        }
    }
    @Test fun missingRepeatIsRetriedOnTheNextIntervalOnly(){
        val h=VideoIdleHeartbeat(1000)
        h.frame(0)
        assertTrue(h.beat(1000))
        // The repeat never produced a frame: retry, but no faster than the interval.
        assertFalse(h.beat(1500))
        assertTrue(h.beat(2000))
    }
    @Test fun movingPictureStopsRepeating(){
        val h=VideoIdleHeartbeat(1000)
        h.frame(0)
        assertTrue(h.beat(1000))
        // The picture keeps producing frames, so nothing is due a second later.
        for(i in 1..30)h.frame(1000+i*33L)
        assertFalse(h.beat(2000))
        assertFalse(h.beat(2989))
    }
    @Test fun intervalMustBePositive(){
        assertThrows(IllegalArgumentException::class.java){VideoIdleHeartbeat(0)}
    }

    /** 静止的第一帧要在 stillAfterMs 就补出去：音频时间戳一直在走，视频这边拖一秒，
     *  恢复时就是一个 A/V 偏移，接收端只能靠丢帧追回来 —— 那正是"一动就卡一下"。 */
    @Test fun firstResendFiresAtTheStillnessThresholdNotTheInterval(){
        val h=VideoIdleHeartbeat(intervalMs=1000,stillAfterMs=400)
        h.frame(0)
        assertFalse("还没到静止阈值",h.beat(399))
        assertTrue("到阈值就该补",h.beat(400))
    }

    /** 补完第一帧之后回到每秒一包的续命节奏，不被更细的 tick 带快。 */
    @Test fun afterTheFirstResendTheCadenceSettlesAtOnePerInterval(){
        val h=VideoIdleHeartbeat(intervalMs=1000,stillAfterMs=400)
        h.frame(0)
        // tick 250 ms，模拟 4 倍密度的定时器。
        val ticks=(0..12).map{it*250L}
        val repeated=ticks.filter{h.beat(it)}
        // 500 补第一帧，之后每 1000 ms 一次且只能在整 tick 上落地：1500、2500。
        assertEquals(listOf(500L,1500L,2500L),repeated)
    }

    /** 早到一点点不算到点，但晚一整格要算 —— 固定周期定时器不可能每次都踩在整点上。 */
    @Test fun aSlightlyEarlyTickDoesNotDoubleTheCadence(){
        val h=VideoIdleHeartbeat(intervalMs=1000,stillAfterMs=1000)
        h.frame(0)
        assertTrue(h.beat(1000))
        assertFalse("早 60 ms，不该补",h.beat(1940))
        assertTrue("晚 10 ms，该补",h.beat(2010))
    }

    /** 判定窗口本身要能自洽：静止阈值比续命周期还长，或者回声窗盖住静止窗，都是写错了。 */
    @Test fun thresholdsMustBeSelfConsistent(){
        assertThrows(IllegalArgumentException::class.java){VideoIdleHeartbeat(intervalMs=500,stillAfterMs=600)}
        assertThrows(IllegalArgumentException::class.java){VideoIdleHeartbeat(intervalMs=1000,stillAfterMs=200,echoMs=200)}
    }
}
