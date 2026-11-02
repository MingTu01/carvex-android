package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class AudioRedDiagnosticTest {
    private val key=ByteArray(32){it.toByte()}
    private val opus=byteArrayOf(0xf8.toByte(),0xff.toByte(),0xfe.toByte())
    private val history=byteArrayOf(0xf8.toByte(),1,2,3)
    private fun packet(policy:AudioRedDiagnostic,sequence:Int=7,timestamp:Long=1960,nonce:Long=9,type:Int=101)=
        policy.packet(opus,history,1000L,key,sequence,timestamp,nonce,type)

    @Test fun compressedSpeechPacketizerUsesTheValidatedSetupResultWithoutAnotherOverride(){
        val format=CarPlayAudioPlan.formats.single{it.bit==0x40000000L}
        for(route in listOf(CarPlayAudioPlan.alert,CarPlayAudioPlan.alternate,CarPlayAudioPlan.assistant))
            for(peerRed in listOf(false,true)){
            val description=AudioSetupDescription.playback(route,format,7,5001,
                offerRedundancy=AudioRedundancyPolicy.offerFor(route))
            val offered=description["supportsRTPPacketRedundancy"]==true
            val response=mapOf("supportsRTPPacketRedundancy" to peerRed)
            if(!offered&&peerRed){
                assertThrows(UnsupportedOperationException::class.java){
                    AudioRedundancyPolicy.responseEnables(route,response,offered)
                }
                continue
            }
            val negotiated=AudioRedundancyPolicy.responseEnables(route,response,offered)
            val policy=AudioRedDiagnostic(true,route.category,CarPlayAudioPlan.Codec.OPUS,negotiated)
            val actual=packet(policy,type=route.type)
            val expected=if(peerRed)
                MediaAudioPackets.redPacket(opus,null,null,key,7,1960,9,route.type)
            else MediaAudioPackets.accessUnit(opus,key,7,1960,9,route.type)
            assertArrayEquals(expected,actual)
            assertEquals(peerRed,policy.applied)
            val offset=if(peerRed)13 else 12
            assertArrayEquals(opus,Crypto.open(key,Crypto.nonce(9),
                actual.copyOfRange(offset,actual.size-8),actual.copyOfRange(4,12)))
        }
    }

    @Test fun isolationOnlyAppliesToNegotiatedNavigationAndAssistantOpus(){
        for(category in listOf(2,3))assertTrue(AudioRedDiagnostic(true,category,CarPlayAudioPlan.Codec.OPUS,true).applied)
        assertFalse(AudioRedDiagnostic(false,2,CarPlayAudioPlan.Codec.OPUS,true).applied)
        assertFalse(AudioRedDiagnostic(true,2,CarPlayAudioPlan.Codec.OPUS,false).applied)
        for(codec in CarPlayAudioPlan.Codec.entries)for(category in listOf(0,1,4))
            assertFalse(AudioRedDiagnostic(true,category,codec,true).applied)
        for(codec in listOf(CarPlayAudioPlan.Codec.PCM16,CarPlayAudioPlan.Codec.AAC_LC,CarPlayAudioPlan.Codec.AAC_ELD))
            for(category in listOf(2,3))assertFalse(AudioRedDiagnostic(true,category,codec,true).applied)
    }

    @Test fun targetPacketKeepsRedPtAadAndNonceButOmitsHistory(){
        val actual=packet(AudioRedDiagnostic(true,2,CarPlayAudioPlan.Codec.OPUS,true))
        assertArrayEquals(MediaAudioPackets.redPacket(opus,null,null,key,7,1960,9,101),actual)
        assertEquals(105,actual[1].toInt() and 127)
        assertEquals(101,actual[12].toInt() and 255)
        assertEquals(12+1+opus.size+24,actual.size)
        assertArrayEquals(opus,Crypto.open(key,Crypto.nonce(9),actual.copyOfRange(13,actual.size-8),actual.copyOfRange(4,12)))
        assertArrayEquals(Crypto.nonce(9).copyOfRange(4,12),actual.takeLast(8).toByteArray())
    }

    @Test fun assistantPacketUsesItsOriginalMainRedPayloadType(){
        val actual=packet(AudioRedDiagnostic(true,3,CarPlayAudioPlan.Codec.OPUS,true),type=100)
        assertArrayEquals(MediaAudioPackets.redPacket(opus,null,null,key,7,1960,9,100),actual)
        assertEquals(104,actual[1].toInt() and 127)
        assertEquals(100,actual[12].toInt() and 255)
    }

    @Test fun baselineRetainsExactlyThePreviousHistoryPacket(){
        assertArrayEquals(MediaAudioPackets.redPacket(opus,history,1000L,key,7,1960,9,101),
            packet(AudioRedDiagnostic(false,2,CarPlayAudioPlan.Codec.OPUS,true)))
    }

    @Test fun mediaAndNonOpusPacketsRemainByteIdentical(){
        val expected=MediaAudioPackets.redPacket(opus,history,1000L,key,7,1960,9,101)
        assertArrayEquals(expected,packet(AudioRedDiagnostic(true,1,CarPlayAudioPlan.Codec.OPUS,true)))
        for(codec in listOf(CarPlayAudioPlan.Codec.PCM16,CarPlayAudioPlan.Codec.AAC_LC,CarPlayAudioPlan.Codec.AAC_ELD))
            assertArrayEquals(expected,packet(AudioRedDiagnostic(true,2,codec,true)))
        assertArrayEquals(MediaAudioPackets.accessUnit(opus,key,7,1960,9,102),
            packet(AudioRedDiagnostic(true,1,CarPlayAudioPlan.Codec.AAC_LC,false),type=102))
    }

    @Test fun unnegotiatedRedIsNotEnabledByTheDiagnosticSwitch(){
        assertArrayEquals(MediaAudioPackets.accessUnit(opus,key,7,1960,9,101),
            packet(AudioRedDiagnostic(true,2,CarPlayAudioPlan.Codec.OPUS,false)))
    }

    @Test fun metadataReadsActualPrimaryOnlyPacketWithoutPayloadOrKeys(){
        val actual=packet(AudioRedDiagnostic(true,2,CarPlayAudioPlan.Codec.OPUS,true))
        val info=requireNotNull(AudioPacketDiagnostics(true,CarPlayAudioPlan.Codec.OPUS).sent(actual,opus,960,0))
        assertEquals(105,info.outerPt);assertEquals(101,info.innerPt);assertEquals(actual.size,info.packetBytes)
        assertEquals(opus.size,info.auBytes);assertEquals(248,info.opusToc);assertEquals(960,info.samples)
        assertEquals(7,info.sequence);assertEquals(1960L,info.timestamp);assertEquals(9L,info.nonce)
        assertNull(info.historyOffset);assertEquals(0,info.historyBytes)
        assertNull(info.sequenceDelta);assertNull(info.timestampDelta)
        assertTrue(info.summary().contains("opusTOC=0xf8"))
        assertFalse(info.summary().contains("payload"));assertFalse(info.summary().contains("key"))
    }

    @Test fun metadataReadsActualHistoryFields(){
        val actual=packet(AudioRedDiagnostic(false,2,CarPlayAudioPlan.Codec.OPUS,true))
        val info=requireNotNull(AudioPacketDiagnostics(true,CarPlayAudioPlan.Codec.OPUS).sent(actual,opus,960,0))
        assertEquals(105,info.outerPt);assertEquals(101,info.innerPt)
        assertEquals(960,info.historyOffset);assertEquals(history.size,info.historyBytes)
    }

    @Test fun plainMetadataHasNoRedOrOpusFieldsForAac(){
        val actual=packet(AudioRedDiagnostic(true,1,CarPlayAudioPlan.Codec.AAC_LC,false),type=102)
        val info=requireNotNull(AudioPacketDiagnostics(false,CarPlayAudioPlan.Codec.AAC_LC).sent(actual,opus,1024,0))
        assertEquals(MediaAudioPackets.wirePayloadType(102),info.outerPt);assertNull(info.innerPt);assertNull(info.opusToc)
        assertNull(info.historyOffset);assertEquals(0,info.historyBytes)
    }

    @Test fun firstThreeThenLowFrequencyAndDeltasUsePreviousSentPacket(){
        val policy=AudioRedDiagnostic(true,2,CarPlayAudioPlan.Codec.OPUS,true)
        val diagnostics=AudioPacketDiagnostics(true,CarPlayAudioPlan.Codec.OPUS)
        for(index in 0..2)assertNotNull(diagnostics.sent(packet(policy,index,index*960L,index.toLong()),opus,960,index*20_000_000L))
        assertNull(diagnostics.sent(packet(policy,3,2880,3),opus,960,60_000_000))
        val later=requireNotNull(diagnostics.sent(packet(policy,4,3840,4),opus,960,5_040_000_000))
        assertEquals(5L,later.packetNumber);assertEquals(1,later.sequenceDelta);assertEquals(960L,later.timestampDelta)
        assertNull(diagnostics.sent(packet(policy,5,4800,5),opus,960,5_060_000_000))
    }

    @Test fun metadataHandlesSequenceTimestampAndNonceWireByteOrder(){
        val policy=AudioRedDiagnostic(true,2,CarPlayAudioPlan.Codec.OPUS,true)
        val diagnostics=AudioPacketDiagnostics(true,CarPlayAudioPlan.Codec.OPUS)
        diagnostics.sent(packet(policy,65535,0xfffffff0L,255),opus,960,0)
        val next=requireNotNull(diagnostics.sent(packet(policy,0,0x1000003b0L,256),opus,960,20_000_000))
        assertEquals(1,next.sequenceDelta);assertEquals(960L,next.timestampDelta)
        assertEquals(0x3b0L,next.timestamp);assertEquals(256L,next.nonce)
    }
}
