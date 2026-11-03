package io.github.iamr0s.carvex.carplay.core

import io.github.iamr0s.carvex.pcm.PcmFormat
import org.junit.Assert.*
import org.junit.Test

/**
 * 用真车 `/info` 的**真实** audioFormats 复现 2026-11-03 session `9829c6ba` 的选路。
 *
 * 车机广告（来自 carvex-log-20261103-d20035 第 197-204 行）：
 * ```
 * #0 streamType=100 audioType=compatibility     input=54     output=8054
 * #1 streamType=101 audioType=compatibility     input=unknown output=8000
 * #2 streamType=100 audioType=alert             input=unknown output=2008000
 * #3 streamType=100 audioType=default           input=c000050 output=c000050
 * #4 streamType=100 audioType=media             input=unknown output=8000
 * #5 streamType=100 audioType=telephony         input=c000054 output=c000054
 * #6 streamType=100 audioType=speechRecognition input=8000040 output=8000040
 * #7 streamType=101 audioType=default           input=unknown output=2008000
 * #8 streamType=102 audioType=media             input=unknown output=800000
 * ```
 * 掩码解读：`0x8000`=lpcm 48000/2，`0x2000000`=ELD 48000/2，`0x10000000`=OPUS 48000/1，
 * `0x8000000`=ELD 24000/1，`0x4000000`=ELD 16000/1。
 */
class CarRealAudioCapabilityReplayTest {
    private val entries=listOf(
        AudioCapabilities.Entry(100,"compatibility",0x54L,0x8054L),
        AudioCapabilities.Entry(101,"compatibility",null,0x8000L),
        AudioCapabilities.Entry(100,"alert",null,0x2008000L),
        AudioCapabilities.Entry(100,"default",0xc000050L,0xc000050L),
        AudioCapabilities.Entry(100,"media",null,0x8000L),
        AudioCapabilities.Entry(100,"telephony",0xc000054L,0xc000054L),
        AudioCapabilities.Entry(100,"speechRecognition",0x8000040L,0x8000040L),
        AudioCapabilities.Entry(101,"default",null,0x2008000L),
        AudioCapabilities.Entry(102,"media",null,0x800000L)
    )

    @Test fun categoryTwoCanTakePcmOnAlternateRoute(){
        // cat2 (alert) 的 100/alert 与 101/default 都广告 2008000，含 lpcm 48000/2。
        val onAlternate=CarPlayAudioPlan.pcmCompatibility(entries,CarPlayAudioPlan.alternate,PcmFormat(48000,2))
        assertNotNull("101/default 必须能拿到 PCM 48000/2",onAlternate)
        assertEquals(CarPlayAudioPlan.Codec.PCM16,onAlternate!!.codec)
        assertEquals(48000,onAlternate.rate)
        assertEquals(2,onAlternate.channels)
    }

    @Test fun categoryThreeCanTakePcmOnSpeechAlternateRoute(){
        // cat3 (speechRecognition) 的 100 号流广告 8000040 —— 含 bit 0x40，
        // 也就是 lpcm 24000/1。**车机 06:14:58 正是拿这条 SETUP 被 400 拒的**，
        // 所以 100/speechRecognition 上的 PCM 是不可用的。
        val onHundred=CarPlayAudioPlan.pcmCompatibility(entries,CarPlayAudioPlan.assistant,PcmFormat(48000,2))
        assertNotNull("100/speechRecognition 确实广告了 lpcm 24000/1",onHundred)
        assertEquals(24000,onHundred!!.rate)
        assertEquals(1,onHundred.channels)
        // 101/default 广告 2008000，含 lpcm 48000/2 —— 这才是 cat3 该走的路。
        val onAlternate=CarPlayAudioPlan.pcmCompatibility(entries,CarPlayAudioPlan.speechAlternate,PcmFormat(48000,2))
        assertNotNull("101/default 必须能拿到 PCM 48000/2",onAlternate)
        assertEquals(CarPlayAudioPlan.Codec.PCM16,onAlternate!!.codec)
        assertEquals(48000,onAlternate.rate)
        assertEquals(2,onAlternate.channels)
    }

    @Test fun categoryThreeSelectsPcmOnOneOhOneWhenPreferred(){
        val selection=CarPlayAudioPlan.selectPlayback(entries,3,PcmFormat(48000,2),
            requireDuplex=true,
            supportsCapture={true},
            preferSpeechAlt=true){true}
        assertNotNull("cat3 必须能选出格式",selection)
        assertEquals(101,selection!!.route.type)
        assertEquals(CarPlayAudioPlan.Codec.PCM16,selection.format.codec)
    }

    @Test fun categoryTwoSelectsPcmOnOneOhOneWhenAlertPcmPreferred(){
        val selection=CarPlayAudioPlan.selectPlayback(entries,2,PcmFormat(48000,2),
            requireDuplex=false,
            supportsCapture={true},
            preferPcmAlert=true){true}
        assertNotNull("cat2 必须能选出格式",selection)
        assertEquals(101,selection!!.route.type)
        assertEquals(CarPlayAudioPlan.Codec.PCM16,selection.format.codec)
    }

    @Test fun bootstrapOrderKeepsMainAudioOnTypeHundred(){
        // 主音频必须留在 100/media（type 100 只有一条流），这是 20032 的教训。
        val plan=CarPlayAudioPlan.bootstrapPlayback(entries,PcmFormat(48000,2)){true}
        val main=plan.single{it.route.purpose=="media"}
        assertEquals(100,main.route.type)
    }
}
