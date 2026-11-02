package io.github.iamr0s.carvex.carplay

import io.github.iamr0s.carvex.carplay.core.AudioRtpTimestampClock
import io.github.iamr0s.carvex.carplay.core.CarPlayAudioPlan
import io.github.iamr0s.carvex.carplay.core.Crypto
import io.github.iamr0s.carvex.carplay.core.carplay.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real compressed packet writer at its encoder-event boundary.
 * No MediaCodec instance is required: codec/source-clock mapping has separate tests. */
class CompressedAudioTimestampTest {
    private val peer=InetAddress.getLoopbackAddress()
    // One 20 ms Opus frame; payload decoding is not part of this packet-writer test.
    private val accessUnit=byteArrayOf(0x08,0x12,0x34)

    private class RecordingSocket(private val failSend:Boolean=false):DatagramSocket(null as SocketAddress?) {
        val packets=CopyOnWriteArrayList<ByteArray>()
        val attempts=AtomicInteger()
        override fun send(packet:DatagramPacket) {
            attempts.incrementAndGet()
            if(failSend)throw IOException("injected UDP failure")
            packets+=packet.data.copyOfRange(packet.offset,packet.offset+packet.length)
        }
    }

    private fun field(target:Any,name:String):Any?=target.javaClass.getDeclaredField(name).let{
        it.isAccessible=true;it.get(target)
    }
    private fun timestamp(packet:ByteArray)=ByteBuffer.wrap(packet).getInt(4).toLong() and 0xffffffffL
    private fun sequence(packet:ByteArray)=ByteBuffer.wrap(packet).getShort(2).toInt() and 65535
    private fun nonce(packet:ByteArray)=ByteBuffer.wrap(packet,packet.size-8,8).order(ByteOrder.LITTLE_ENDIAN).long
    private fun cacheSize(retransmit:AudioRtpRetransmit)=synchronized(requireNotNull(field(retransmit,"lock"))){
        (field(retransmit,"history") as Map<*,*>).size
    }

    private inner class Writer(rate:Int=48000,type:Int=100,category:Int=3,
        highAccuracy:Boolean=false,red:Boolean=false,
        canSend:()->Boolean={true},failSend:Boolean=false,failPacketLog:Boolean=false) {
        val format=CarPlayAudioPlan.formats.single{it.codec==CarPlayAudioPlan.Codec.OPUS&&it.rate==rate}
        val socket=RecordingSocket(failSend)
        val key=ByteArray(32){it.toByte()}
        val retransmit=AudioRtpRetransmit(DatagramSocket(0,peer),peer,{})
        val stopped=CompletableDeferred<Unit>()
        private val logs=Channel<String>(Channel.UNLIMITED)
        private var barrier=0
        val audio:CarPlayCompressedAudio
        val clock:AudioRtpTimestampClock get()=field(audio,"rtpClock") as AudioRtpTimestampClock
        private val output:Channel<CompressedAudioEncoder.Event>
        init {
            val ownership:suspend ()->Unit={}
            val onStopped:()->Unit={stopped.complete(Unit);Unit}
            val logger:(String)->Unit={line->
                if(failPacketLog&&line.startsWith("Audio packet category="))error("injected diagnostic failure")
                logs.trySend(line);Unit
            }
            // Bypass only MediaCodec creation, not packetization, pacing, gates or UDP sends.
            // 15 个参数：新增的 sapCipher 传 null，本组用例走 HomeKit 路线。
            val constructor=CarPlayCompressedAudio::class.java.declaredConstructors.single{it.parameterCount==15}
            constructor.isAccessible=true
            audio=constructor.newInstance(socket,peer,12345,category,type,format,key,retransmit,
                ownership,onStopped,logger,red,canSend,null,highAccuracy) as CarPlayCompressedAudio
            @Suppress("UNCHECKED_CAST")
            output=field(audio,"output") as Channel<CompressedAudioEncoder.Event>
            CarPlayCompressedAudio::class.java.getDeclaredMethod("startWriter").also{
                it.isAccessible=true;it.invoke(audio)
            }
        }
        private fun configuration(marker:Int)=CompressedAudioEncoder.Event.Configuration(
            "audio/opus",format.rate,format.channels,null,emptyMap(),0,marker)

        /** A following configuration event is an ordered barrier, not a timing/polling guess. */
        suspend fun drain() {
            val marker=++barrier
            output.send(configuration(marker))
            withTimeout(5000){
                while(!logs.receive().contains(" paddingSamples=$marker rate=")){/* consume earlier diagnostics */}
            }
        }
        suspend fun initialize(){drain()}
        suspend fun unit(ptsUs:Long,sourceNanos:Long=System.nanoTime()) {
            // Deadlines are injected independently from logical source PTS, allowing a
            // large source discontinuity to be tested without sleeping for several seconds.
            output.send(CompressedAudioEncoder.Event.AccessUnit(accessUnit.copyOf(),ptsUs,false,
                sourceNanos,sourceNanos,ptsUs))
        }
        suspend fun close() {
            audio.close()
            val job=(field(audio,"scope") as CoroutineScope).coroutineContext[Job]
            withTimeout(5000){job?.join()}
            logs.cancel()
        }
    }

    @Test fun mainAndAltOpusSourceGapsAdvanceOnlyActuallySentSamplesAtEveryRate():Unit=runBlocking {
        for(type in listOf(100,101))for(rate in listOf(16000,24000,48000)){
            val writer=Writer(rate,type,if(type==101)2 else 3)
            try{
                writer.initialize()
                writer.unit(0);writer.drain()
                writer.unit(3_000_000);writer.drain()
                assertEquals(2,writer.socket.packets.size)
                assertEquals(2L,writer.audio.packetsSent)
                val first=writer.socket.packets[0];val second=writer.socket.packets[1]
                assertEquals(0L,timestamp(first))
                assertEquals(writer.format.framesPerPacket.toLong(),timestamp(second))
                assertEquals(1,(sequence(second)-sequence(first)) and 65535)
                for((index,packet) in writer.socket.packets.withIndex()){
                    assertEquals(type,packet[1].toInt() and 127)
                    assertEquals(index.toLong(),nonce(packet))
                    assertArrayEquals(accessUnit,Crypto.open(writer.key,Crypto.nonce(nonce(packet)),
                        packet.copyOfRange(12,packet.size-8),packet.copyOfRange(4,12)))
                }
            }finally{writer.close()}
        }
    }

    @Test fun sourceGapDropsRedHistoryEvenThoughWireTimestampsStayAdjacent():Unit=runBlocking {
        // Category-1 low-latency audio uses normal RED, not the speech primary-only diagnostic.
        val writer=Writer(category=1,red=true)
        try{
            writer.initialize()
            writer.unit(0);writer.drain()
            writer.unit(20_000);writer.drain()
            writer.unit(3_000_000);writer.drain()
            val packets=writer.socket.packets
            assertEquals(3,packets.size)
            assertEquals(0,packets[0][12].toInt() and 128)
            assertEquals(128,packets[1][12].toInt() and 128)
            assertEquals(0,packets[2][12].toInt() and 128)
            assertEquals(960L,timestamp(packets[2])-timestamp(packets[1]))
            assertEquals(1920L,timestamp(packets[2]))
        }finally{writer.close()}
    }

    @Test fun staleAndOwnershipRejectedUnitsConsumeNoRtpSamplesOrHistory():Unit=runBlocking {
        val allowed=AtomicBoolean(true)
        val writer=Writer(category=1,red=true,canSend={allowed.get()})
        try{
            writer.initialize()
            writer.unit(0);writer.drain()
            writer.unit(20_000,System.nanoTime()-1_000_000_000);writer.drain()
            assertEquals(1L,field(writer.audio,"staleUnits"))
            assertEquals(1,cacheSize(writer.retransmit))
            assertNull(field(writer.audio,"history"))
            allowed.set(false)
            writer.unit(40_000);writer.drain()
            assertEquals(1,writer.socket.packets.size)
            assertEquals(960L,writer.clock.timestamp(0))
            allowed.set(true)
            writer.unit(60_000);writer.drain()
            assertEquals(2,writer.socket.packets.size)
            assertEquals(2,cacheSize(writer.retransmit))
            val resumed=writer.socket.packets[1]
            assertEquals(960L,timestamp(resumed))
            assertEquals(0,resumed[12].toInt() and 128)
            assertEquals(1L,nonce(resumed))
        }finally{writer.close()}
    }

    @Test fun explicitHighAccuracyReplyAndType102RetainLegacySourcePositions():Unit=runBlocking {
        for((type,highAccuracy) in listOf(100 to true,101 to true,102 to false,102 to true)){
            val writer=Writer(type=type,category=if(type==102)1 else 2,highAccuracy=highAccuracy)
            try{
                writer.initialize()
                writer.unit(0);writer.drain()
                writer.unit(3_000_000);writer.drain()
                assertEquals(2,writer.socket.packets.size)
                assertEquals(144000L,timestamp(writer.socket.packets[1]))
                assertEquals(AudioRtpTimestampClock.Mode.LEGACY_SOURCE_PTS,writer.clock.mode)
            }finally{writer.close()}
        }
    }

    @Test fun speechSendsByDefaultInPlainAndNegotiatedRedModes():Unit=runBlocking {
        for(category in listOf(2,3))for(red in listOf(false,true)){
            val type=if(category==2)101 else 100
            val writer=Writer(category=category,type=type,red=red)
            val initialSequence=field(writer.audio,"sequence") as Int
            try{
                writer.initialize()
                writer.unit(0);writer.drain()
                writer.unit(20_000);writer.drain()
                assertEquals(2,writer.socket.packets.size);assertEquals(2L,writer.audio.packetsSent)
                assertEquals(1920L,writer.clock.timestamp(0))
                assertEquals((initialSequence+2) and 65535,field(writer.audio,"sequence"))
                assertEquals(2L,field(writer.audio,"nonce"));assertEquals(2,cacheSize(writer.retransmit))
                for((index,packet) in writer.socket.packets.withIndex()){
                    assertEquals(if(red)type+4 else type,packet[1].toInt() and 127)
                    assertEquals(index.toLong(),nonce(packet));assertEquals(index*960L,timestamp(packet))
                    // Respect the separately retained build option in both build variants.
                    val history=red&&index>0&&!BuildConfig.CARPLAY_PRIMARY_ONLY_RED
                    val offset=if(history)17 else if(red)13 else 12
                    if(red)assertEquals(type or if(history)128 else 0,packet[12].toInt() and 255)
                    assertArrayEquals(if(history)accessUnit+accessUnit else accessUnit,Crypto.open(writer.key,Crypto.nonce(nonce(packet)),
                        packet.copyOfRange(offset,packet.size-8),packet.copyOfRange(4,12)))
                }
            }finally{writer.close()}
        }
    }

    @Test fun failedUdpSendStopsTransportWithoutCommittingSamplesOrRollingBackNonce():Unit=runBlocking {
        val writer=Writer(category=1,red=true,failSend=true)
        val initialSequence=field(writer.audio,"sequence")
        try{
            writer.initialize()
            writer.unit(0)
            withTimeout(5000){writer.stopped.await()}
            assertFalse(writer.audio.ready)
            assertEquals(1,writer.socket.attempts.get())
            assertTrue(writer.socket.packets.isEmpty())
            assertEquals(0L,writer.audio.packetsSent)
            assertEquals(0L,writer.clock.timestamp(0))
            assertEquals(initialSequence,field(writer.audio,"sequence"))
            assertEquals(1L,field(writer.audio,"nonce"))
            assertNull(field(writer.audio,"history"))
            assertEquals(0,cacheSize(writer.retransmit))
        }finally{writer.close()}
    }

    @Test fun postSendDiagnosticFailureCannotUndoTheCommittedPacket():Unit=runBlocking {
        val writer=Writer(failPacketLog=true)
        val initialSequence=field(writer.audio,"sequence") as Int
        try{
            writer.initialize()
            writer.unit(0)
            withTimeout(5000){writer.stopped.await()}
            assertFalse(writer.audio.ready)
            assertEquals(1,writer.socket.packets.size)
            assertEquals(1L,writer.audio.packetsSent)
            assertEquals(960L,writer.clock.timestamp(0))
            assertEquals((initialSequence+1) and 65535,field(writer.audio,"sequence"))
            assertEquals(1L,field(writer.audio,"nonce"))
        }finally{writer.close()}
    }
}
