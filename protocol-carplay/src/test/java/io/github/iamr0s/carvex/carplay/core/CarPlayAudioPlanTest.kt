package io.github.iamr0s.carvex.carplay.core

import io.github.iamr0s.carvex.pcm.PcmFormat

import org.junit.Assert.*
import org.junit.Test

class CarPlayAudioPlanTest {
    @Test fun refusedPromptRouteFallsThroughOnlyToAnotherAdvertisedRoute(){
        val entries=listOf(entry(100,"alert",0x8000L),entry(101,"default",0x8000L),entry(102,"media",0x800000L))
        val source=PcmFormat(48000,2)
        assertEquals(CarPlayAudioPlan.alert,CarPlayAudioPlan.selectPlayback(entries,2,source){false}!!.route)
        assertEquals(CarPlayAudioPlan.alternate,CarPlayAudioPlan.selectPlayback(entries,2,source,
            excludedRoutes=setOf(CarPlayAudioPlan.alert)){false}!!.route)
        assertNull(CarPlayAudioPlan.selectPlayback(entries.take(1),2,source,
            excludedRoutes=setOf(CarPlayAudioPlan.alert)){false})
        assertNull(CarPlayAudioPlan.selectPlayback(entries,2,source,
            excludedRoutes=setOf(CarPlayAudioPlan.alert,CarPlayAudioPlan.alternate)){false})
        assertEquals(CarPlayAudioPlan.media,CarPlayAudioPlan.selectPlayback(entries,1,source,
            excludedRoutes=setOf(CarPlayAudioPlan.alert)){true}!!.route)
    }
    @Test fun runtimeWireGateRetainsTheExistingCompressedWritersAndBoundsPcm(){
        for(format in CarPlayAudioPlan.formats){
            assertEquals(format.codec!=CarPlayAudioPlan.Codec.PCM16||format.rate%200==0,
                CarPlayAudioPlan.wireSupported(CarPlayAudioPlan.mediaLowLatency,format))
            assertEquals(format.codec!=CarPlayAudioPlan.Codec.PCM16,
                CarPlayAudioPlan.wireSupported(CarPlayAudioPlan.media,format))
            assertFalse(CarPlayAudioPlan.wireSupported(CarPlayAudioPlan.Route(1,106,"media"),format))
        }
        val unknown=CarPlayAudioPlan.Format(1L shl 60,CarPlayAudioPlan.Codec.OPUS,48000,1,960)
        assertFalse(CarPlayAudioPlan.wireSupported(CarPlayAudioPlan.media,unknown))
        val input=listOf(AudioCapabilities.Entry(100,"speechRecognition",0x10L or 0x20000000L,null))
        assertEquals(listOf(CarPlayAudioPlan.Codec.PCM16),CarPlayAudioPlan.pcmCaptureCandidates(input).map{it.codec})
        assertTrue(CarPlayAudioPlan.pcmCaptureCandidates(listOf(AudioCapabilities.Entry(100,"media",0x10L,null))).isEmpty())
    }
    private fun entry(type:Int,purpose:String,mask:Long)=AudioCapabilities.Entry(type,purpose,null,mask)
    @Test fun wirelessSelectsCompressedFormatsWithoutTreatingPcmAsStandardWireless(){
        val entries=listOf(entry(102,"media",0x800000L or 0x8000L),entry(101,"default",0x10000000L or 0x10L))
        assertEquals(CarPlayAudioPlan.Codec.AAC_LC,CarPlayAudioPlan.wireless(entries,CarPlayAudioPlan.media,PcmFormat(48000,2))!!.codec)
        assertEquals(CarPlayAudioPlan.Codec.OPUS,CarPlayAudioPlan.wireless(entries,CarPlayAudioPlan.alternate,PcmFormat(16000,1))!!.codec)
        assertNull(CarPlayAudioPlan.pcmCompatibility(entries,CarPlayAudioPlan.media,PcmFormat(48000,2)))
    }
    @Test fun directionsAndPurposesCannotBeSubstituted(){
        val entries=listOf(entry(102,"speechRecognition",0x800000L),AudioCapabilities.Entry(101,"default",0x8000L,null))
        assertNull(CarPlayAudioPlan.wireless(entries,CarPlayAudioPlan.media,PcmFormat(48000,2)))
        assertNull(CarPlayAudioPlan.pcmCompatibility(entries,CarPlayAudioPlan.alternate,PcmFormat(48000,2)))
        assertNull(CarPlayAudioPlan.pcmCompatibility(listOf(entry(100,"media",0x8000L)),CarPlayAudioPlan.alternate,PcmFormat(48000,2)))
    }
    @Test fun pcmPacketIntervalLimitIsLocalAndUnknownBitsRemainUnknown(){
        val entries=listOf(entry(102,"media",0x800L or (1L shl 60)))
        assertEquals(44100,CarPlayAudioPlan.advertised(entries,CarPlayAudioPlan.media).single().rate)
        assertNull(CarPlayAudioPlan.pcmCompatibility(entries,CarPlayAudioPlan.media,PcmFormat(44100,2)))
    }

    @Test fun mediaPrefersHighLatencyMediaStreamWhenAdvertised(){
        val low=listOf(entry(100,"media",0x8000L))
        assertEquals(100,CarPlayAudioPlan.mediaRoute(low).type)
        val high=listOf(entry(102,"media",0x800000L),entry(100,"media",0x8000L))
        assertEquals(102,CarPlayAudioPlan.mediaRoute(high).type)
        val highPcmOnly=listOf(entry(102,"media",0x8000L),entry(100,"media",0x8000L))
        assertEquals(100,CarPlayAudioPlan.mediaRoute(highPcmOnly).type)
    }

    /** 文档 §9.4 的优先序：AAC-ELD 是三家车机的交集首选，同一个流上同时广告 ELD 与 LC 时选 ELD。
     *  原实现按"媒体流固定 AAC-LC"选择，这条断言就是那个行为的反例。 */
    @Test fun aacEldOutranksAacLcWhereBothAreAdvertised(){
        val entries=listOf(entry(102,"media",0x2000000L or 0x800000L))   // ELD 48k/2 | LC 48k/2
        val chosen=CarPlayAudioPlan.wireless(entries,CarPlayAudioPlan.media,PcmFormat(48000,2))
        assertEquals(CarPlayAudioPlan.Codec.AAC_ELD,chosen!!.codec)
        assertEquals(48000,chosen.rate)
    }

    /** GM 系的 `audioFormats` 不含 AAC-LC（只有 AAC/AAC-ELD/OPUS）：固定 AAC-LC 的实现在这里交不出
     *  任何格式，而按优先序回退必须选出 ELD。 */
    @Test fun aMediaStreamWithoutAacLcStillYieldsAFormat(){
        val gm=listOf(entry(102,"media",0x2000000L or 0x40000000L))     // ELD 48k/2 | OPUS 48k/1
        assertEquals(CarPlayAudioPlan.Codec.AAC_ELD,
            CarPlayAudioPlan.wireless(gm,CarPlayAudioPlan.media,PcmFormat(48000,2))!!.codec)
        val opusOnly=listOf(entry(102,"media",0x40000000L))
        assertEquals(CarPlayAudioPlan.Codec.OPUS,
            CarPlayAudioPlan.wireless(opusOnly,CarPlayAudioPlan.media,PcmFormat(48000,1))!!.codec)
    }

    /** 老平台只有 AAC-LC 可用（无 ELD/OPUS 的位置）时仍要选出 LC；且语音流不再固定偏好 OPUS，
     *  同一个流里 ELD 与 OPUS 都在时按文档优先序取 ELD。 */
    @Test fun theOrderFallsThroughAndAppliesToEveryPurpose(){
        val legacy=listOf(entry(102,"media",0x800000L))
        assertEquals(CarPlayAudioPlan.Codec.AAC_LC,
            CarPlayAudioPlan.wireless(legacy,CarPlayAudioPlan.media,PcmFormat(48000,2))!!.codec)
        val speech=listOf(entry(101,"default",0x2000000L or 0x10000000L)) // ELD 48k/2 | OPUS 16k/1
        assertEquals(CarPlayAudioPlan.Codec.AAC_ELD,
            CarPlayAudioPlan.wireless(speech,CarPlayAudioPlan.alternate,PcmFormat(16000,1))!!.codec)
    }

    /** 同一个编码里，采样率/声道贴合源 PCM 的排前面 —— 优先序决定编码，匹配度决定同编码内的实例。 */
    @Test fun withinOneCodecTheClosestStreamStillWins(){
        val entries=listOf(entry(101,"default",0x8000000L or 0x1000000L)) // ELD 24k/1 | ELD 44.1k/2
        assertEquals(44100,CarPlayAudioPlan.wireless(entries,CarPlayAudioPlan.alternate,PcmFormat(44100,2))!!.rate)
        assertEquals(24000,CarPlayAudioPlan.wireless(entries,CarPlayAudioPlan.alternate,PcmFormat(24000,1))!!.rate)
    }

    @Test fun orderedIntersectionKeepsProfilePriorityAfterLocalFiltering(){
        val entries=listOf(entry(102,"media",0x2000000L or 0x800000L)) // ELD then LC
        val ordered=CarPlayAudioPlan.wirelessCandidates(entries,CarPlayAudioPlan.media,PcmFormat(48000,2))
        assertEquals(CarPlayAudioPlan.Codec.AAC_ELD,ordered.first().codec)
        assertEquals(CarPlayAudioPlan.Codec.AAC_ELD,
            CarPlayAudioPlan.orderedIntersection(ordered){it.codec==CarPlayAudioPlan.Codec.AAC_ELD}.first().codec)
        assertEquals(CarPlayAudioPlan.Codec.AAC_LC,
            CarPlayAudioPlan.orderedIntersection(ordered){it.codec==CarPlayAudioPlan.Codec.AAC_LC}.first().codec)
    }

    @Test fun orderedIntersectionReturnsEmptyWhenTheSetsDoNotOverlap(){
        val entries=listOf(entry(102,"media",0x2000000L))
        val ordered=CarPlayAudioPlan.wirelessCandidates(entries,CarPlayAudioPlan.media,PcmFormat(48000,2))
        assertTrue(CarPlayAudioPlan.orderedIntersection(ordered){false}.isEmpty())
    }

    /** Anonymous /info shape from the disconnect log. v11 offered three independent streams;
     * route selection must preserve the advertised codec instead of applying a global OPUS ban. */
    @Test fun observedCapabilitiesKeepMedia102AndPromptAlert100SeparateFromVoice100(){
        val entries=listOf(entry(100,"media",0x8000L),entry(102,"media",0x800000L),entry(100,"alert",0x40008000L),
            entry(101,"default",0x40008000L),
            AudioCapabilities.Entry(100,"speechRecognition",0x20000040L,0x20000040L))
        val expected=listOf(Triple(1,102,CarPlayAudioPlan.Codec.AAC_LC),
            Triple(2,100,CarPlayAudioPlan.Codec.OPUS),Triple(3,100,CarPlayAudioPlan.Codec.OPUS))
        val source=PcmFormat(48000,2)
        val selected=expected.map{(category,type,codec)->
            val selection=CarPlayAudioPlan.selectPlayback(entries,category,source){true}!!
            assertEquals(type,selection.route.type)
            assertEquals(codec,selection.format.codec)
            selection.route to selection.format
        }
        assertEquals(listOf(102,100,100),selected.map{it.first.type})
        assertEquals(listOf(48000,48000,24000),selected.map{it.second.rate})
        val voice=selected.last().second
        // The same route-specific mask is used for output and capture; no global codec ban.
        assertEquals(CarPlayAudioPlan.Codec.OPUS,CarPlayAudioPlan.captureCandidates(entries,source).first().codec)

        // Exercise the real retained packet serializer and key direction, rather than reproducing
        // its implementation here. Codec generation itself remains a platform integration check.
        val key=ByteArray(32){it.toByte()}
        for((route,_) in selected){
            val accessUnit=byteArrayOf(0x78,1,2,3)
            val packet=MediaAudioPackets.accessUnit(accessUnit,key,7,960,0,route.type)
            // 线上 PT 必须等于流类型：接收端用这个字节判断 AAD 长度（见 CarPlaySourceIdentityTest）。
            assertEquals(MediaAudioPackets.wirePayloadType(route.type),packet[1].toInt() and 127)
            assertArrayEquals(accessUnit,Crypto.open(key,Crypto.nonce(0),
                packet.copyOfRange(12,packet.size-8),packet.copyOfRange(4,12)))
        }
    }

    @Test fun localEncoderIntersectionStillControlsCompressedFallback(){
        val entries=listOf(entry(102,"media",0x2000000L or 0x800000L),
            entry(101,"default",0x40008000L))
        val route=CarPlayAudioPlan.routeForCategory(entries,1)
        val candidates=CarPlayAudioPlan.playbackCandidates(entries,route,PcmFormat(48000,2))
        assertEquals(CarPlayAudioPlan.Codec.AAC_ELD,candidates.first().codec)
        assertEquals(CarPlayAudioPlan.Codec.AAC_LC,
            CarPlayAudioPlan.orderedIntersection(candidates){it.codec==CarPlayAudioPlan.Codec.AAC_LC}.single().codec)
        assertTrue(CarPlayAudioPlan.orderedIntersection(candidates){false}.isEmpty())
        assertNull(CarPlayAudioPlan.pcmCompatibility(entries,route,PcmFormat(48000,2)))
        assertNotNull(CarPlayAudioPlan.pcmCompatibility(entries,CarPlayAudioPlan.alternate,PcmFormat(48000,2)))
    }

    @Test fun playbackSelectionFallsBackToAnotherRouteWhenNoLocalEncoderMatchesType102(){
        val entries=listOf(entry(102,"media",0x800000L),entry(100,"media",0x8000L))
        val probed=mutableListOf<CarPlayAudioPlan.Format>()
        val selected=CarPlayAudioPlan.selectPlayback(entries,1,PcmFormat(48000,2)){
            probed+=it;false
        }!!
        assertEquals(listOf(CarPlayAudioPlan.Codec.AAC_LC),probed.map{it.codec})
        assertEquals(CarPlayAudioPlan.mediaLowLatency,selected.route)
        assertEquals(CarPlayAudioPlan.Codec.PCM16,selected.format.codec)
        assertEquals(0x8000L,selected.format.bit)
        assertNull(CarPlayAudioPlan.selectPlayback(entries.take(1),1,PcmFormat(48000,2)){false})
        assertNull(CarPlayAudioPlan.selectPlayback(emptyList(),1,PcmFormat(48000,2)){true})
    }

    @Test fun bootstrapPrimesSpeechThenAlertThenMedia(){
        val entries=listOf(entry(100,"media",0x8000L),entry(102,"media",0x800000L),entry(100,"alert",0x40008000L),
            entry(101,"default",0x40008000L),
            AudioCapabilities.Entry(100,"speechRecognition",0x20000040L,0x20000040L))
        val source=PcmFormat(48000,2)
        assertEquals(listOf(100,100,102),CarPlayAudioPlan.bootstrapPlayback(entries,source){true}.map{it.route.type})
        val pcm=CarPlayAudioPlan.bootstrapPlayback(entries,source){false}
        assertEquals(listOf(100,100,100),pcm.map{it.route.type})
        assertEquals(listOf(3,2,1),pcm.map{it.route.category})
    }

    @Test fun microphoneRetainsPcmWhenCompressedDecoderIsUnavailable(){
        val entries=listOf(AudioCapabilities.Entry(100,"speechRecognition",0x20000040L,null))
        val candidates=CarPlayAudioPlan.microphoneCandidates(entries,PcmFormat(48000,2))
        assertEquals(listOf(CarPlayAudioPlan.Codec.OPUS,CarPlayAudioPlan.Codec.PCM16),candidates.map{it.codec})
        assertEquals(CarPlayAudioPlan.Codec.PCM16,candidates.first{it.codec==CarPlayAudioPlan.Codec.PCM16}.codec)
        val pcmOnly=listOf(AudioCapabilities.Entry(100,"speechRecognition",0x40L,null))
        assertEquals(24000,CarPlayAudioPlan.microphoneCandidates(pcmOnly,PcmFormat(48000,2)).single().rate)
    }

    @Test fun playbackSelectionUsesLocalCodecIntersectionBeforeMovingToNextRoute(){
        val entries=listOf(entry(102,"media",0x2000000L or 0x800000L),entry(100,"media",0x8000L))
        val selected=CarPlayAudioPlan.selectPlayback(entries,1,PcmFormat(48000,2)){
            it.codec==CarPlayAudioPlan.Codec.AAC_LC
        }!!
        assertEquals(CarPlayAudioPlan.media,selected.route)
        assertEquals(CarPlayAudioPlan.Codec.AAC_LC,selected.format.codec)
    }

    @Test fun speechFormatUsesInputMaskAndDuplexRequiresLocalCaptureSupport(){
        val entries=listOf(AudioCapabilities.Entry(100,"speechRecognition",
            0x4000000L or 0x20000000L,0x2000000L or 0x4000000L or 0x20000000L))
        val source=PcmFormat(48000,2)
        val ordinary=CarPlayAudioPlan.selectPlayback(entries,3,source){true}!!
        // The output-only ELD 48k format must not override the firmware's input-mask choice.
        assertEquals(0x4000000L,ordinary.format.bit)
        val duplex=CarPlayAudioPlan.selectPlayback(entries,3,source,requireDuplex=true){true}!!
        assertEquals(0x4000000L,duplex.format.bit)
        assertEquals(CarPlayAudioPlan.Codec.AAC_ELD,duplex.format.codec)
        assertTrue(CarPlayAudioPlan.duplexSupported(entries,duplex.route,duplex.format))
        // If the local encoder only exposes OPUS, that advertised duplex format remains valid.
        val opusOnly=CarPlayAudioPlan.selectPlayback(entries,3,source,requireDuplex=true){
            it.codec==CarPlayAudioPlan.Codec.OPUS
        }
        assertNotNull(opusOnly)
        assertEquals(0x20000000L,opusOnly!!.format.bit)
        assertNull(CarPlayAudioPlan.selectPlayback(entries,3,source,requireDuplex=true,supportsCapture={false}){true})
        val inputOnly=listOf(AudioCapabilities.Entry(100,"speechRecognition",0x40L,null))
        assertEquals(0x40L,CarPlayAudioPlan.selectPlayback(inputOnly,3,source,requireDuplex=true){false}!!.format.bit)
        val outputOnly=listOf(entry(100,"speechRecognition",0x40L))
        assertNull(CarPlayAudioPlan.selectPlayback(outputOnly,3,source){false})
        // Alert and Alt/default continue to use the output mask.
        assertNull(CarPlayAudioPlan.selectPlayback(listOf(AudioCapabilities.Entry(100,"alert",0x8000L,null)),2,source){false})
    }
}
