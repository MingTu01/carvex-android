package io.github.iamr0s.carvex.carplay

import io.github.iamr0s.carvex.carplay.core.AudioRedundancyPolicy
import io.github.iamr0s.carvex.carplay.core.AudioRtpTimestampClock
import io.github.iamr0s.carvex.carplay.core.AudioSetupDescription
import io.github.iamr0s.carvex.carplay.core.CarPlayAudioPlan
import io.github.iamr0s.carvex.carplay.core.Crypto
import io.github.iamr0s.carvex.carplay.core.MediaAudioPackets
import io.github.iamr0s.carvex.pcm.PcmFormat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioOutputTransportTest {
    private val peer=InetAddress.getLoopbackAddress()
    private val format=PcmFormat(48000,1)
    private val framesPerPacket=240
    private val packetBytes=480

    /** Runs the actual PCM writer, but records UDP writes without a receiving thread. */
    private class RecordingSocket:DatagramSocket(null as SocketAddress?) {
        val packets=mutableListOf<ByteArray>()
        override fun send(packet:DatagramPacket){
            packets+=packet.data.copyOfRange(packet.offset,packet.offset+packet.length)
        }
    }

    // Read-only snapshots verify internal clocks/caches that deliberately have no
    // public diagnostic API. No field is changed to make the writer pass.
    private fun field(target:Any,name:String):Any?=target.javaClass.getDeclaredField(name).let{
        it.isAccessible=true;it.get(target)
    }
    private fun rtpClock(audio:CarPlayMediaAudio)=field(audio,"rtpClock") as AudioRtpTimestampClock
    private fun timestamp(packet:ByteArray)=ByteBuffer.wrap(packet).getInt(4).toLong() and 0xffffffffL
    private fun cacheSize(retransmit:AudioRtpRetransmit):Int=synchronized(requireNotNull(field(retransmit,"lock"))){
        (field(retransmit,"history") as Map<*,*>).size
    }

    @Test fun navigationAndAssistantSendByDefaultWithPacingOwnershipAndCaching():Unit=runBlocking {
        for(category in listOf(2,3)){
            val socket=RecordingSocket()
            val retransmit=AudioRtpRetransmit(DatagramSocket(0,peer),peer,{})
            var ownershipCalls=0;var stoppedCalls=0
            val audio=CarPlayMediaAudio(socket,peer,12345,format,category,if(category==2)101 else 100,
                ByteArray(32),retransmit,{ownershipCalls++},{stoppedCalls++},{})
            val initialTimestamp=rtpClock(audio).timestamp(0)
            val initialSequence=field(audio,"sequence") as Int
            try{
                val started=System.nanoTime()
                audio.send(category,ByteArray(packetBytes*21),format)
                assertTrue("source pacing retained",System.nanoTime()-started>=50_000_000L)
                assertEquals(1,ownershipCalls);assertTrue(audio.ready);assertNull(audio.microphoneFormat)
                assertEquals(21L,audio.packetsSent);assertEquals(21,socket.packets.size)
                assertTrue(audio.lastPacketNanos!=0L);assertEquals(21,cacheSize(retransmit))
                assertEquals(21L*framesPerPacket,field(audio,"pacingFrames"))
                assertEquals((initialTimestamp+21L*framesPerPacket) and 0xffffffffL,rtpClock(audio).timestamp(0))
                assertEquals((initialSequence+21) and 65535,field(audio,"sequence"))
                assertEquals(21L,field(audio,"nonce"))
                assertEquals(0,(field(audio,"pending") as ByteArray).size)
                assertEquals(0,stoppedCalls)
            }finally{audio.close()}
            assertEquals(1,stoppedCalls)
        }
    }

    @Test fun completePcmPacketsAreSentAndOnlyPartialFrameRemains():Unit=runBlocking {
        val socket=RecordingSocket();var ownershipCalls=0
        val audio=CarPlayMediaAudio(socket,peer,12345,format,2,101,ByteArray(32),null,{ownershipCalls++},{},{})
        try{
            repeat(3){audio.send(2,ByteArray(packetBytes*2),format)}
            assertEquals(6L,audio.packetsSent);assertEquals(0,(field(audio,"pending") as ByteArray).size)
            audio.send(2,ByteArray(packetBytes+packetBytes/2),format)
            assertEquals(7L,audio.packetsSent);assertEquals(packetBytes/2,(field(audio,"pending") as ByteArray).size)
            audio.send(2,ByteArray(packetBytes/2),format)
            assertEquals(8L,audio.packetsSent);assertEquals(0,(field(audio,"pending") as ByteArray).size)
            assertEquals(1,ownershipCalls);assertEquals(8,socket.packets.size)
        }finally{audio.close()}
    }

    @Test fun unavailableOwnershipStillPreventsOutput():Unit=runBlocking {
        val socket=RecordingSocket();var ownershipCalls=0
        val audio=CarPlayMediaAudio(socket,peer,12345,format,3,100,ByteArray(32),null,{ownershipCalls++},{},{},canSend={false})
        try{
            audio.send(3,ByteArray(packetBytes),format)
            assertEquals(0,ownershipCalls);assertEquals(0L,audio.packetsSent)
            assertTrue(socket.packets.isEmpty());assertEquals(0L,field(audio,"nonce"))
        }finally{audio.close()}
    }

    @Test fun speechUsesTheExistingWireFormatAndCachesSuccessfulPackets():Unit=runBlocking {
        for(category in listOf(2,3)){
            val socket=RecordingSocket();val key=ByteArray(32){it.toByte()}
            val retransmit=AudioRtpRetransmit(DatagramSocket(0,peer),peer,{})
            val type=if(category==2)101 else 100
            val audio=CarPlayMediaAudio(socket,peer,12345,format,category,type,key,retransmit,{},{},{})
            val pcm=ByteArray(packetBytes*2){it.toByte()}
            try{
                audio.send(category,pcm,format)
                assertEquals(2,socket.packets.size);assertEquals(2L,audio.packetsSent)
                assertTrue(audio.lastPacketNanos!=0L);assertEquals(2,cacheSize(retransmit))
                val first=socket.packets[0];val second=socket.packets[1]
                fun sequence(packet:ByteArray)=ByteBuffer.wrap(packet).getShort(2).toInt() and 65535
                fun timestamp(packet:ByteArray)=ByteBuffer.wrap(packet).getInt(4).toLong() and 0xffffffffL
                assertEquals(1,(sequence(second)-sequence(first)) and 65535)
                assertEquals(240L,(timestamp(second)-timestamp(first)) and 0xffffffffL)
                for((index,packet) in socket.packets.withIndex()){
                    assertEquals(MediaAudioPackets.wirePayloadType(type),packet[1].toInt() and 127)
                    val nonce=ByteBuffer.wrap(packet,packet.size-8,8).order(ByteOrder.LITTLE_ENDIAN).long
                    assertEquals(index.toLong(),nonce)
                    val plain=Crypto.open(key,Crypto.nonce(nonce),packet.copyOfRange(12,packet.size-8),packet.copyOfRange(4,12))
                    assertArrayEquals(MediaAudioPackets.networkPcm(pcm.copyOfRange(index*packetBytes,(index+1)*packetBytes)),plain)
                }
            }finally{audio.close()}
        }
    }

    @Test fun mediaStillSendsByDefault():Unit=runBlocking {
        val socket=RecordingSocket()
        val audio=CarPlayMediaAudio(socket,peer,12345,format,1,102,ByteArray(32),null,{},{},{})
        try{
            audio.send(1,ByteArray(packetBytes),format)
            assertEquals(1,socket.packets.size);assertEquals(1L,audio.packetsSent)
            assertEquals(MediaAudioPackets.wirePayloadType(102),socket.packets.single()[1].toInt() and 127)
        }finally{audio.close()}
    }

    @Test fun sourcePauseRebasesPacingButMainAndAltRtpStayContinuousAndDiscardRedHistory():Unit=runBlocking {
        for(type in listOf(100,101)){
            val category=if(type==100)3 else 2
            val socket=RecordingSocket()
            val audio=CarPlayMediaAudio(socket,peer,12345,format,category,type,ByteArray(32),null,{},{},{},
                redEnabled=true,receiverHighAccuracyTimestamps=false)
            try{
                audio.send(category,ByteArray(packetBytes),format)
                assertNotNull(field(audio,"history"))
                delay(180) // A real source pause, beyond the writer's 100 ms jitter allowance.
                audio.send(category,ByteArray(packetBytes*2),format)
                assertEquals(3,socket.packets.size)
                val first=socket.packets[0];val resumed=socket.packets[1];val adjacent=socket.packets[2]
                assertEquals(240L,(timestamp(resumed)-timestamp(first)) and 0xffffffffL)
                assertEquals(240L,(timestamp(adjacent)-timestamp(resumed)) and 0xffffffffL)
                assertTrue((field(audio,"pacingFrames") as Long)>3L*framesPerPacket)
                assertEquals(type,resumed[12].toInt() and 255) // No history across the source pause.
                assertEquals(0x80 or type,adjacent[12].toInt() and 255)
                assertEquals(240,((adjacent[13].toInt() and 255) shl 6) or ((adjacent[14].toInt() and 255) ushr 2))
            }finally{audio.close()}
        }
    }

    @Test fun highAccuracyReplyAndType102RetainLegacySourceGapBehavior():Unit=runBlocking {
        for((type,highAccuracy) in listOf(100 to true,101 to true,102 to false,102 to true)){
            val category=if(type==102)1 else if(type==101)2 else 3
            val socket=RecordingSocket()
            val audio=CarPlayMediaAudio(socket,peer,12345,format,category,type,ByteArray(32),null,{},{},{},receiverHighAccuracyTimestamps=highAccuracy)
            try{
                audio.send(category,ByteArray(packetBytes),format)
                delay(180)
                audio.send(category,ByteArray(packetBytes),format)
                assertEquals(2,socket.packets.size)
                val delta=(timestamp(socket.packets[1])-timestamp(socket.packets[0])) and 0xffffffffL
                assertTrue("legacy source clock must retain the source gap",delta>240L)
                assertEquals(AudioRtpTimestampClock.Mode.LEGACY_SOURCE_PTS,rtpClock(audio).mode)
            }finally{audio.close()}
        }
    }

    @Test fun rejectedOwnershipClearsRedWithoutConsumingRtpSequenceOrNonce():Unit=runBlocking {
        val socket=RecordingSocket();var allowed=true
        val audio=CarPlayMediaAudio(socket,peer,12345,format,3,100,ByteArray(32),null,{},{},{},
            redEnabled=true,canSend={allowed})
        try{
            audio.send(3,ByteArray(packetBytes),format)
            val next=rtpClock(audio).timestamp(0)
            val sequence=field(audio,"sequence")
            allowed=false
            audio.send(3,ByteArray(packetBytes*3),format)
            assertNull(field(audio,"history"))
            assertEquals(next,rtpClock(audio).timestamp(0))
            assertEquals(sequence,field(audio,"sequence"));assertEquals(1L,field(audio,"nonce"))
            allowed=true
            audio.send(3,ByteArray(packetBytes),format)
            assertEquals(2,socket.packets.size)
            assertEquals(240L,(timestamp(socket.packets[1])-timestamp(socket.packets[0])) and 0xffffffffL)
            assertEquals(100,socket.packets[1][12].toInt() and 255)
        }finally{audio.close()}
    }

    @Test fun failedUdpSendDoesNotCommitSamplesOrCacheAndClosesTheTransport():Unit=runBlocking {
        val socket=object:DatagramSocket(null as SocketAddress?){
            override fun send(packet:DatagramPacket){throw java.io.IOException("simulated UDP failure")}
        }
        val retransmit=AudioRtpRetransmit(DatagramSocket(0,peer),peer,{})
        var stops=0
        val audio=CarPlayMediaAudio(socket,peer,12345,format,2,101,ByteArray(32),retransmit,{},{stops++},{},
            redEnabled=true)
        val initial=rtpClock(audio).timestamp(0);val sequence=field(audio,"sequence")
        try{
            try{audio.send(2,ByteArray(packetBytes),format);fail("send must fail")}
            catch(_:java.io.IOException){}
            assertEquals(initial,rtpClock(audio).timestamp(0));assertEquals(sequence,field(audio,"sequence"))
            assertEquals(1L,field(audio,"nonce")) // Encryption already used it; never reuse it.
            assertEquals(0L,audio.packetsSent);assertEquals(0L,audio.lastPacketNanos)
            assertEquals(0,cacheSize(retransmit));assertNull(field(audio,"history"))
            assertFalse(audio.ready);assertEquals(1,stops)
        }finally{audio.close()}
    }

    @Test fun postSendDiagnosticFailureKeepsTheCommitButCannotReplayPendingPcm():Unit=runBlocking {
        val socket=RecordingSocket()
        val audio=CarPlayMediaAudio(socket,peer,12345,format,2,101,ByteArray(32),null,{},{},{line->
            if(line.startsWith("Audio first RTP"))error("simulated diagnostic failure")
        })
        val initial=rtpClock(audio).timestamp(0);val sequence=field(audio,"sequence") as Int
        try{
            try{audio.send(2,ByteArray(packetBytes*2),format);fail("diagnostic must fail")}
            catch(_:IllegalStateException){}
            assertEquals(1,socket.packets.size);assertEquals(1L,audio.packetsSent)
            assertEquals(initial+framesPerPacket,rtpClock(audio).timestamp(0))
            assertEquals((sequence+1) and 65535,field(audio,"sequence"))
            assertEquals(1L,field(audio,"nonce"));assertFalse(audio.ready)
        }finally{audio.close()}
    }

    @Test fun everyRouteSendsUsingOnlyItsValidatedSetupResult():Unit=runBlocking {
        val selected=CarPlayAudioPlan.formats.single{it.bit==0x4000L}
        for(route in CarPlayAudioPlan.routes)for(peerRed in listOf<Boolean?>(null,false,true)){
            val category=route.category;val type=route.type
            val description=AudioSetupDescription.playback(route,selected,7,5001,
                offerRedundancy=AudioRedundancyPolicy.offerFor(route))
            val response=peerRed?.let{mapOf("supportsRTPPacketRedundancy" to it)}?:emptyMap()
            val negotiated=AudioRedundancyPolicy.responseEnables(route,response,
                offered=description["supportsRTPPacketRedundancy"]==true)
            val socket=RecordingSocket();val key=ByteArray(32){it.toByte()}
            val audio=CarPlayMediaAudio(socket,peer,12345,format,category,type,key,null,{},{},{},redEnabled=negotiated)
            val pcm=ByteArray(packetBytes){it.toByte()}
            try{
                audio.send(category,pcm,format)
                assertEquals(1L,audio.packetsSent);assertEquals(1,socket.packets.size)
                val packet=socket.packets.single()
                val red=type!=102&&peerRed==true
                assertEquals(if(red)MediaAudioPackets.redType(type) else MediaAudioPackets.wirePayloadType(type),packet[1].toInt() and 127)
                if(red)assertEquals(type,packet[12].toInt() and 255)
                val payloadOffset=if(red)13 else 12
                val nonce=ByteBuffer.wrap(packet,packet.size-8,8).order(ByteOrder.LITTLE_ENDIAN).long
                assertEquals(0L,nonce)
                assertArrayEquals(MediaAudioPackets.networkPcm(pcm),Crypto.open(key,Crypto.nonce(nonce),
                    packet.copyOfRange(payloadOffset,packet.size-8),packet.copyOfRange(4,12)))
            }finally{audio.close()}
        }
    }

    @Test fun newNegotiationCannotChangeAnExistingStreamsPacketFormat():Unit=runBlocking {
        val plainSocket=RecordingSocket();val redSocket=RecordingSocket()
        fun newSpeech(socket:RecordingSocket,peerRed:Boolean):CarPlayMediaAudio {
            val route=CarPlayAudioPlan.alternate
            val negotiated=AudioRedundancyPolicy.responseEnables(route,
                mapOf("supportsRTPPacketRedundancy" to peerRed),AudioRedundancyPolicy.offerFor(route))
            return CarPlayMediaAudio(socket,peer,12345,format,2,101,ByteArray(32),null,{},{},{},redEnabled=negotiated)
        }
        val plain=newSpeech(plainSocket,false);val red=newSpeech(redSocket,true)
        try{
            plain.send(2,ByteArray(packetBytes),format);red.send(2,ByteArray(packetBytes),format)
            plain.send(2,ByteArray(packetBytes),format)
            assertTrue(plainSocket.packets.all{(it[1].toInt() and 127)==101})
            assertEquals(105,redSocket.packets.single()[1].toInt() and 127)
            assertEquals(2L,plain.packetsSent);assertEquals(1L,red.packetsSent)
        }finally{plain.close();red.close()}
    }

    /** T2 线上格式：raw 100/101 在 A-44 的 G=0 分派表里**不建密码器**，所以 SAP 下 PCM 的
     *  线上就是 `0x80` + PT + (seq BE16 / timestamp BE32 / 0 BE32) + BE PCM。
     *  没有 16 字节 tag、没有 8 字节 nonce 尾 —— 这两样是 ChaCha 路线的，少一个字节车机都会
     *  按错长度切帧。这条测试把长度关系钉死，避免以后有人"顺手"把非对称封包接进来。 */
    @Test fun sapPlainPcmCarriesHeaderAndNetworkByteOrderPcmOnly():Unit=runBlocking {
        val socket=RecordingSocket()
        val pcm=PcmFormat(48000,2)
        val bytes=240*2*2
        val audio=CarPlayMediaAudio(socket,peer,12345,pcm,1,100,ByteArray(0),null,{},{},{},
            sapCrypto=io.github.iamr0s.carvex.carplay.core.SapAudioCrypto.plain())
        try{
            // 0x1234 的低字节在前 = 源侧小端；线上必须是 0x12,0x34。
            val source=ByteArray(bytes){if(it%2==0)0x34 else 0x12}
            audio.send(1,source,pcm)
            val packet=socket.packets.single()
            assertEquals("12 bytes of header plus the PCM body, nothing else",12+bytes,packet.size)
            assertEquals(0x80,packet[0].toInt() and 0xff)
            assertEquals(100,packet[1].toInt() and 127)
            // 正文整体等于 networkPcm 的字节序翻转结果，逐字节可比。
            assertArrayEquals(MediaAudioPackets.networkPcm(source),packet.copyOfRange(12,packet.size))
            assertEquals(0x12,packet[12].toInt() and 0xff)
            assertEquals(0x34,packet[13].toInt() and 0xff)
        }finally{audio.close()}
    }
}
