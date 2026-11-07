package io.github.iamr0s.carvex.carplay.core

import io.github.iamr0s.carvex.pcm.PcmFormat
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** 三路音频分离：媒体让出 `type 100`，语音才有属于自己的那一条。
 *
 *  背景是实测出来的：CarWith 的分音量是媒体/小爱/导航三路，但 `type 100` 上只容忍一条流 ——
 *  媒体在 `100/media` 建了 PCM 上下文之后，`100/speechRecognition` 的 SETUP 一律 400，
 *  于是小爱只能并进导航那条通道，两路声音就再也分不开了。
 *
 *  这组用例钉住两件事，缺一条都可能在车上把已经能响的声音弄哑：
 *  1. 打开分离时，三条路落在三个**不同**的 type 上；
 *  2. 102 上没有可用编码时**必须**回退到原来的 `100/media` PCM —— 宁可回到"分不开但有声"，
 *     也不能因为一次实验把音乐变成静音。 */
class SeparatedAudioLanesTest {
    private val source=PcmFormat(48000,2)
    private val pcm=0x8000L
    private val aacLc=0x800000L
    private lateinit var previous:CarPlayAudioPlan.Transport
    @Before fun setup(){previous=CarPlayAudioPlan.currentTransport;CarPlayAudioPlan.useTransport(CarPlayAudioPlan.Transport.WIRELESS)}
    @After fun restore(){CarPlayAudioPlan.useTransport(previous)}

    private fun full()=listOf(
        AudioCapabilities.Entry(102,"media",null,aacLc),
        AudioCapabilities.Entry(100,"media",null,pcm),
        AudioCapabilities.Entry(101,"default",null,pcm),
        AudioCapabilities.Entry(100,"speechRecognition",pcm,pcm),
    )

    private fun pick(entries:List<AudioCapabilities.Entry>,category:Int,separate:Boolean)=
        CarPlayAudioPlan.selectPlayback(entries,category,source,requireDuplex=category==3,
            preferPcm=true,preferPcmAlert=true,preferSpeechAlt=true,separateLanes=separate){true}

    @Test fun separationPutsEachCategoryOnItsOwnType() {
        val entries=full()
        val media=pick(entries,1,separate=true)!!
        val prompt=pick(entries,2,separate=true)!!
        val speech=pick(entries,3,separate=true)!!
        // 媒体在 102 上只能走压缩 —— PCM 在 102 上不被本端允许（wireSupported 只放 100/101）。
        assertEquals(102,media.route.type)
        assertEquals(CarPlayAudioPlan.Codec.AAC_LC,media.format.codec)
        assertEquals(101,prompt.route.type)
        assertEquals(CarPlayAudioPlan.Codec.PCM16,prompt.format.codec)
        assertEquals(100,speech.route.type)
        assertEquals("speechRecognition",speech.route.purpose)
        // 三条互不相同的 type，才是车机上三个能分别调的音量。
        assertEquals(3,setOf(media.route.type,prompt.route.type,speech.route.type).size)
    }

    @Test fun withoutSeparationTheOldSingleLaneLayoutIsUnchanged() {
        val entries=full()
        val media=pick(entries,1,separate=false)!!
        // 回归护栏：媒体仍然在 100 上用 PCM —— 这是目前实车能出声的那套配置。
        assertEquals(100,media.route.type)
        assertEquals(CarPlayAudioPlan.Codec.PCM16,media.format.codec)
        val speech=pick(entries,3,separate=false)!!
        // 语音落在 101/default，和导航同一条 —— 就是会被 HostAudioBridge 合并的那种情形。
        assertEquals(101,speech.route.type)
    }

    @Test fun mediaFallsBackToTheOldLaneWhen102HasNoUsableEncoder() {
        // 102 只广告了 PCM：本端不允许在 102 上发 PCM，所以 media(102) 交出 null，
        // 循环自然落到 mediaLowLatency(100)，行为与关闭分离时完全一致。
        val entries=listOf(
            AudioCapabilities.Entry(102,"media",null,pcm),
            AudioCapabilities.Entry(100,"media",null,pcm),
        )
        val media=pick(entries,1,separate=true)!!
        assertEquals(100,media.route.type)
        assertEquals(CarPlayAudioPlan.Codec.PCM16,media.format.codec)
    }

    @Test fun mediaFallsBackWhenTheLocalEncoderIsMissing() {
        val entries=full()
        val rejected=CarPlayAudioPlan.selectPlayback(entries,1,source,requireDuplex=false,
            preferPcm=true,preferPcmAlert=true,preferSpeechAlt=true,separateLanes=true){false}!!
        assertEquals(100,rejected.route.type)
        assertEquals(CarPlayAudioPlan.Codec.PCM16,rejected.format.codec)
    }

    @Test fun speechKeepsAWorkingRouteWhenItsOwnTypeIsRefused() {
        // 100/speechRecognition 被车机 400 拒掉之后，语音必须还能落到 101/default，
        // 也就是"分不开但有声"，而不是彻底没有声音。
        val entries=full()
        val retry=CarPlayAudioPlan.selectPlayback(entries,3,source,requireDuplex=true,
            preferPcm=true,preferPcmAlert=true,preferSpeechAlt=true,separateLanes=true,
            excludedRoutes=setOf(CarPlayAudioPlan.assistant)){true}!!
        assertEquals(101,retry.route.type)
    }
}
