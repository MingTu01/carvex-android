package io.github.iamr0s.carvex.carplay.core

import io.github.iamr0s.carvex.pcm.PcmFormat
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

/** Regression for retiring the PCM override without deleting negotiated PCM support. */
class SpeechAudioSelectionTest {
    private val source=PcmFormat(24000,1)
    private val pcm=0x40L
    private val opus=0x20000000L
    private lateinit var previous:CarPlayAudioPlan.Transport
    @Before fun setup(){previous=CarPlayAudioPlan.currentTransport;CarPlayAudioPlan.useTransport(CarPlayAudioPlan.Transport.WIRELESS)}
    @After fun restore(){CarPlayAudioPlan.useTransport(previous)}
    private fun entries(mask:Long)=listOf(AudioCapabilities.Entry(100,"alert",null,mask),
        AudioCapabilities.Entry(100,"speechRecognition",mask,mask))

    @Test fun availableCompressedSpeechIsNotOverriddenByAdvertisedPcm() {
        // 关掉 PCM 优先时保持原有优先序：能编码就用压缩，不被广告的 PCM 抢走。
        for(category in listOf(2,3)){
            val selected=CarPlayAudioPlan.selectPlayback(entries(opus or pcm),category,source,
                requireDuplex=category==3,preferPcm=false){true}!!
            assertEquals(CarPlayAudioPlan.Codec.OPUS,selected.format.codec)
            assertEquals(opus,selected.format.bit)
        }
    }
    /** PCM 优先只覆盖主音频与（显式打开时的）告警音。
     *
     *  category 3 必须**始终**走压缩：车机在 `type 100` 上只有一个 PCM 上下文，
     *  让 speechRecognition 先用掉 24k/1 会把 `100/media` 的 48k/2 顶成 StatusException
     *  —— 真机 20032 就是这样丢掉主音频的。这条断言是那个回退的回归护栏。 */
    @Test fun pcmPreferenceNeverTakesTheSpeechStreamAwayFromItsEncoder() {
        val selected=CarPlayAudioPlan.selectPlayback(entries(opus or pcm),3,source,
            requireDuplex=true,preferPcm=true,preferPcmAlert=true){true}!!
        assertEquals(CarPlayAudioPlan.Codec.OPUS,selected.format.codec)
        assertEquals(opus,selected.format.bit)
        // 告警音只有显式打开 pcmAlert 时才走 PCM，而且必须落在 101/default 上 ——
        // 它自己的 100/alert 在真机 20032 上被 400 拒绝。
        val withAlternate=entries(opus or pcm)+AudioCapabilities.Entry(101,"default",null,opus or pcm)
        val alert=CarPlayAudioPlan.selectPlayback(withAlternate,2,source,
            preferPcm=true,preferPcmAlert=true){true}!!
        assertEquals(CarPlayAudioPlan.alternate,alert.route)
        assertEquals(CarPlayAudioPlan.Codec.PCM16,alert.format.codec)
        assertEquals(pcm,alert.format.bit)
        // 默认不开：仍然取压缩，避免踩那条 400。
        val alertOff=CarPlayAudioPlan.selectPlayback(withAlternate,2,source,
            preferPcm=true,preferPcmAlert=false){true}!!
        assertEquals(CarPlayAudioPlan.alert,alertOff.route)
        assertEquals(CarPlayAudioPlan.Codec.OPUS,alertOff.format.codec)
    }
    @Test fun pcmOnlyPeerStillHasNavigationAndDuplexAssistant() {
        for(category in listOf(2,3)){
            val selected=CarPlayAudioPlan.selectPlayback(entries(pcm),category,source,
                requireDuplex=category==3){true}!!
            assertEquals(CarPlayAudioPlan.Codec.PCM16,selected.format.codec)
            assertEquals(source,selected.format.decodedPcm)
            if(category==3)assertTrue(CarPlayAudioPlan.duplexSupported(entries(pcm),selected.route,selected.format))
        }
    }
    @Test fun unavailableEncoderUsesExistingPcmFallbackWithoutAUserSwitch() {
        for(category in listOf(2,3)){
            val selected=CarPlayAudioPlan.selectPlayback(entries(opus or pcm),category,source,
                requireDuplex=category==3){false}!!
            assertEquals(CarPlayAudioPlan.Codec.PCM16,selected.format.codec)
        }
    }
    @Test fun missingFormatsOrCaptureSupportCannotBeInvented() {
        assertNull(CarPlayAudioPlan.selectPlayback(entries(opus),2,source){false})
        assertNull(CarPlayAudioPlan.selectPlayback(entries(opus or pcm),3,source,
            requireDuplex=true,supportsCapture={false}){true})
        assertNull(CarPlayAudioPlan.selectPlayback(emptyList(),3,source){true})
    }
    @Test fun bootstrapAndOnDemandUseTheSameUnmodifiedCodecSelection() {
        val capabilities=entries(opus or pcm)+AudioCapabilities.Entry(102,"media",null,0x800000L)
        for(encoderAvailable in listOf(false,true)){
            val bootstrap=CarPlayAudioPlan.bootstrapPlayback(capabilities,source){encoderAvailable}
            val runtime=listOf(2,1).mapNotNull{category->
                CarPlayAudioPlan.selectPlayback(capabilities,category,source,requireDuplex=category==3){encoderAvailable}}
            assertEquals(runtime,bootstrap)
        }
    }
}
