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
    /** 默认（PCM 优先）打开后，广告了 PCM 就必须取 PCM —— 真车 20030 上压缩编码器
     *  对 alert/speechRecognition 都建不起来，取压缩等于整条流不可用。 */
    @Test fun pcmPreferenceWinsOverAnAvailableEncoderWhenPcmIsAdvertised() {
        for(category in listOf(2,3)){
            val selected=CarPlayAudioPlan.selectPlayback(entries(opus or pcm),category,source,
                requireDuplex=category==3,preferPcm=true){true}!!
            assertEquals(CarPlayAudioPlan.Codec.PCM16,selected.format.codec)
            assertEquals(pcm,selected.format.bit)
        }
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
            val runtime=listOf(3,2,1).mapNotNull{category->
                CarPlayAudioPlan.selectPlayback(capabilities,category,source,requireDuplex=category==3){encoderAvailable}}
            assertEquals(runtime,bootstrap)
        }
    }
}
