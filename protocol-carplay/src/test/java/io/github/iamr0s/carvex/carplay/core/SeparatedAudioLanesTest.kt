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
        // 102 同时广告 PCM 与 AAC：实车 0fbfe122 的那次只拿到 AAC，媒体因此一个字节都没推上来。
        AudioCapabilities.Entry(102,"media",null,pcm or aacLc),
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
        // 媒体在 102 上走 PCM：CarWith 只采 PCM，压缩编码会得到一条没有源的流。
        assertEquals(102,media.route.type)
        assertEquals(CarPlayAudioPlan.Codec.PCM16,media.format.codec)
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

    @Test fun mediaFallsBackToTheOldLaneWhen102AdvertisesNothingUsable() {
        // 102 一条格式都没广告：media(102) 交出 null，循环自然落到 mediaLowLatency(100)，
        // 行为与关闭分离时完全一致 —— 宁可"分不开但有声"。
        val entries=listOf(
            AudioCapabilities.Entry(102,"media",null,0L),
            AudioCapabilities.Entry(100,"media",null,pcm),
        )
        val media=pick(entries,1,separate=true)!!
        assertEquals(100,media.route.type)
        assertEquals(CarPlayAudioPlan.Codec.PCM16,media.format.codec)
    }

    /** 落回 102 的**实测教训**（session 0fbfe122）：媒体在 102 上被选成 AAC_LC，SETUP 200，
     *  可 CarWith 的 `music record` 采的是 PCM，编码不了就一个字节都不推 —— 车机上音乐直接哑了。
     *  所以 102 上有 PCM 时必须优先 PCM；没有 PCM 位就**根本别选 102**。 */
    @Test fun mediaUsesPcmOn102AndRefusesItOtherwise() {
        val withPcm=listOf(
            AudioCapabilities.Entry(102,"media",null,pcm or aacLc),
            AudioCapabilities.Entry(100,"media",null,pcm),
        )
        val media=pick(withPcm,1,separate=true)!!
        assertEquals(102,media.route.type)
        assertEquals(CarPlayAudioPlan.Codec.PCM16,media.format.codec)
        assertEquals(source,media.format.decodedPcm)

        // 102 只有压缩编码：不选它，落回 100 的 PCM。否则就是一条没有源的流。
        val encodedOnly=listOf(
            AudioCapabilities.Entry(102,"media",null,aacLc),
            AudioCapabilities.Entry(100,"media",null,pcm),
        )
        val fallback=pick(encodedOnly,1,separate=true)!!
        assertEquals(100,fallback.route.type)
        assertEquals(CarPlayAudioPlan.Codec.PCM16,fallback.format.codec)
    }

    @Test fun theProvenConfigurationNeverDependsOnALocalEncoder() {
        // 关掉分离时媒体走 100 的 PCM，本机没有编码器也一样出声 —— 这是目前实车跑通的那套，
        // 不能因为实验开关的存在而变得依赖编码器。
        val rejected=CarPlayAudioPlan.selectPlayback(full(),1,source,requireDuplex=false,
            preferPcm=true,preferPcmAlert=true,preferSpeechAlt=true,separateLanes=false){false}!!
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
