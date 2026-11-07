package io.github.iamr0s.carvex.carplay.core

import io.github.iamr0s.carvex.pcm.PcmFormat
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** 三路音频分离：媒体 / 导航 / 语音各占一条流。
 *
 *  CarWith 的 `/info`（session 0fbfe122）共 9 条路由，能发 PCM 的只有 `100/media`、
 *  `101/default`、`101/compatibility` 三条 —— `102/media` 只收 AAC_LC，而 CarWith 只会采
 *  PCM，那条路 SETUP 200 但整场 0 个包。`type 100` 上又只能同时存在一条流，硬塞
 *  `100/speechRecognition` 会把主音频顶掉、并且**触屏跟着失灵**。
 *
 *  所以布局是：媒体留在 `100/media`、导航留在 `101/default`（两者都已被实车证明能出声），
 *  **只把从来没响过的语音挪到空着的 `101/compatibility`**。
 *
 *  这组用例钉住这个布局，以及三件不能坏的事：
 *  - 关掉开关时行为与实车已跑通的配置逐项一致；
 *  - 车机不广告 `101/compatibility` 时语音宁可没有独立流，也不能回头去偷 `100/speechRecognition`；
 *  - 任何档位都不许选 `102/media`。
 */
class SeparatedAudioLanesTest {
    private val source=PcmFormat(48000,2)
    private val pcm=0x8000L
    private val aacLc=0x800000L
    private lateinit var previous:CarPlayAudioPlan.Transport
    @Before fun setup(){previous=CarPlayAudioPlan.currentTransport;CarPlayAudioPlan.useTransport(CarPlayAudioPlan.Transport.WIRELESS)}
    @After fun restore(){CarPlayAudioPlan.useTransport(previous)}

    /** 与实车 /info 一致的关键几条。 */
    private fun full()=listOf(
        AudioCapabilities.Entry(102,"media",null,aacLc),
        AudioCapabilities.Entry(100,"media",null,pcm),
        AudioCapabilities.Entry(100,"alert",null,pcm),
        AudioCapabilities.Entry(101,"default",null,pcm),
        AudioCapabilities.Entry(101,"compatibility",null,pcm),
        AudioCapabilities.Entry(100,"speechRecognition",pcm,pcm),
    )

    private fun pick(entries:List<AudioCapabilities.Entry>,category:Int,separate:Boolean,
                     excluded:Set<CarPlayAudioPlan.Route> = emptySet())=
        CarPlayAudioPlan.selectPlayback(entries,category,source,requireDuplex=category==3,
            preferPcm=true,preferPcmAlert=true,preferSpeechAlt=true,separateLanes=separate,
            excludedRoutes=excluded){true}

    @Test fun separationLeavesTheTwoProvenRoutesAloneAndGivesSpeechTheFreeSlot() {
        val entries=full()
        val media=pick(entries,1,separate=true)!!
        val prompt=pick(entries,2,separate=true)!!
        val speech=pick(entries,3,separate=true,
            excluded=CarPlayAudioPlan.speechPlaybackExclusions(emptySet()))!!

        // 媒体留在已经跑通的 100/media 上，格式是 PCM —— CarWith 只采 PCM。
        assertEquals(100,media.route.type)
        assertEquals("media",media.route.purpose)
        assertEquals(CarPlayAudioPlan.Codec.PCM16,media.format.codec)

        // 导航也留在已经跑通的 101/default 上。分离模式不降级它：这是实车发过 3204 个包的那条。
        assertEquals(101,prompt.route.type)
        assertEquals("default",prompt.route.purpose)
        assertEquals(CarPlayAudioPlan.Codec.PCM16,prompt.format.codec)

        // 语音才被挪走，拿到 101 下空着的第二个 purpose。
        assertEquals(101,speech.route.type)
        assertEquals("compatibility",speech.route.purpose)
        assertEquals(CarPlayAudioPlan.Codec.PCM16,speech.format.codec)

        // 三条 (type,purpose) 互不相同才是真的三条流。
        assertEquals(3,setOf(media.route.type to media.route.purpose,
            prompt.route.type to prompt.route.purpose,speech.route.type to speech.route.purpose).size)
        // 谁都不许碰 type 100 的语音流：那是触屏失灵的老触发点。
        assertFalse(listOf(media,prompt,speech).any{it.route.type==100&&it.route.purpose=="speechRecognition"})
    }

    @Test fun withoutSeparationTheProvenLayoutIsUnchanged() {
        val entries=full()
        val media=pick(entries,1,separate=false)!!
        val prompt=pick(entries,2,separate=false)!!
        val speech=pick(entries,3,separate=false,
            excluded=CarPlayAudioPlan.speechPlaybackExclusions(emptySet()))!!
        assertEquals(100,media.route.type)
        assertEquals(CarPlayAudioPlan.Codec.PCM16,media.format.codec)
        assertEquals(101,prompt.route.type)
        assertEquals("default",prompt.route.purpose)
        // 语音也落在 101/default —— 和导航同一条，所以会被 HostAudioBridge 合并。
        // 这就是"分不开但现在有声音"的那套配置。
        assertEquals(101,speech.route.type)
        assertEquals("default",speech.route.purpose)
    }

    @Test fun navigationStaysOnDefaultWhenTheSecondPurposeIsNotAdvertised() {
        // 车机只广告了 101/default，没有 101/compatibility。
        val entries=listOf(
            AudioCapabilities.Entry(102,"media",null,aacLc),
            AudioCapabilities.Entry(100,"media",null,pcm),
            AudioCapabilities.Entry(101,"default",null,pcm),
        )
        val prompt=pick(entries,2,separate=true)!!
        assertEquals(101,prompt.route.type)
        assertEquals("default",prompt.route.purpose)
        assertEquals(CarPlayAudioPlan.Codec.PCM16,prompt.format.codec)
    }

    @Test fun speechHasNoStreamWhenTheOnlyFreeSlotIsNotAdvertised() {
        // 没有 101/compatibility 就没有第三条流的位置。这时语音必须返回 null，
        // 由 HostAudioBridge 并进活着的通道 —— 分不开，但既没声音问题也没有触屏问题。
        val entries=listOf(
            AudioCapabilities.Entry(102,"media",null,aacLc),
            AudioCapabilities.Entry(100,"media",null,pcm),
            AudioCapabilities.Entry(101,"default",null,pcm),
            AudioCapabilities.Entry(100,"speechRecognition",pcm,pcm),
        )
        val excluded=CarPlayAudioPlan.speechPlaybackExclusions(emptySet())
        assertNull(pick(entries,3,separate=true,excluded=excluded))
    }

    @Test fun mediaNeverPicksType102BecauseItsOnlyFormatIsOneTheHostCannotFill() {
        // 102 上只有 AAC_LC：CarWith 采 PCM、编码不了就不推，那条流永远没有数据。
        // 所以无论哪个档位都不能选它。
        for(separate in listOf(false,true)){
            val media=pick(full(),1,separate)!!
            assertNotEquals(102,media.route.type)
        }
    }

    @Test fun speechNeverStealsTheType100SpeechSlot() {
        // 101/default 被车机 400 拒掉之后，宁可不给语音建流，也不能换成 `100/speechRecognition`
        // —— 那是"用完小爱触屏失灵"的触发点。

        // 先证明这个坑是真的：不施加排除，协议层确实会退到 type 100 的语音流上。
        val naive=pick(full(),3,separate=false,excluded=setOf(CarPlayAudioPlan.speechAlternate))
        assertNotNull("若这条不再成立，说明 choices 的顺序已经变了，下面的断言失去意义",naive)
        assertEquals(100,naive!!.route.type)
        assertEquals("speechRecognition",naive.route.purpose)

        // 再证明策略生效：同一组输入，加上 speechPlaybackExclusions 之后语音不再有独立流。
        val excluded=CarPlayAudioPlan.speechPlaybackExclusions(setOf(CarPlayAudioPlan.speechAlternate))
        assertTrue(CarPlayAudioPlan.assistant in excluded)
        assertNull("语音宁可没有独立流，也不能建 type 100 的播放流",
            pick(full(),3,separate=false,excluded=excluded))
        // 分离模式同理：别的备位都没了也不会退到 type 100。
        assertNull("分离模式下也不许退到 type 100",
            pick(full(),3,separate=true,
                excluded=CarPlayAudioPlan.speechPlaybackExclusions(
                    setOf(CarPlayAudioPlan.speechAlternate,CarPlayAudioPlan.speechCompat))))
    }

    @Test fun theExclusionOnlyTargetsTheSpeechPlaybackSlot() {
        // 这条策略只针对**播放**方向的语音：车机采集麦克风走另一条 SETUP，
        // `pcmCaptureCandidates` 不能被它影响，否则麦克风会跟着没了。
        val excluded=CarPlayAudioPlan.speechPlaybackExclusions(emptySet())
        assertEquals(setOf(CarPlayAudioPlan.assistant),excluded)
        assertEquals(setOf(CarPlayAudioPlan.assistant),
            CarPlayAudioPlan.speechPlaybackExclusions(setOf(CarPlayAudioPlan.assistant)))

        val entries=full()
        assertTrue(CarPlayAudioPlan.pcmCaptureCandidates(entries).isNotEmpty())
        // 媒体与导航的选择完全不受这条策略影响。
        for(category in listOf(1,2)){
            assertEquals(pick(entries,category,separate=true),
                pick(entries,category,separate=true,excluded=excluded))
        }
    }
}
