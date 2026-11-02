package io.github.iamr0s.carvex.carplay

import io.github.iamr0s.carvex.pcm.PcmFormat
import io.github.iamr0s.carvex.carplay.core.MediaAudioPackets
import io.github.iamr0s.carvex.carplay.core.ScreenResourceModes
import io.github.iamr0s.carvex.sessioncore.MainAudioOwnership
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AudioResourceOutputTest {
    @Test fun receiverModeTransfersActuallyGateMainAudioPackets():Unit=runBlocking {
        val peer=InetAddress.getLoopbackAddress()
        val receiver=DatagramSocket(0,peer).apply{soTimeout=150}
        val ownership=MainAudioOwnership({}){true}
        val modes=ScreenResourceModes()
        val format=PcmFormat(48000,2)
        val main=CarPlayMediaAudio(DatagramSocket(0,peer),peer,receiver.localPort,format,1,102,
            ByteArray(32),null,{},{},{},canSend=ownership::allowsMainSend)
        fun transfer(type:Int){
            val next=requireNotNull(modes.propose(mapOf("resources" to listOf(mapOf("resourceID" to 2,"transferType" to type)))))
            modes.commit(next);ownership.receiverDeclaredModes(next)
        }
        fun noPacket(){assertThrows(SocketTimeoutException::class.java){receiver.receive(DatagramPacket(ByteArray(2048),2048))}}
        fun packet(){receiver.receive(DatagramPacket(ByteArray(2048),2048))}
        try{
            transfer(1);main.send(1,ByteArray(960),format);noPacket()
            transfer(2);main.send(1,ByteArray(960),format);packet()
            transfer(3);main.send(1,ByteArray(960),format);noPacket()
            transfer(4);main.send(1,ByteArray(960),format);packet()
        }finally{main.close();receiver.close()}
    }
    @Test fun heldMainProducesNoPacketsAndAlternateStillSends():Unit=runBlocking {
        val peer=InetAddress.getLoopbackAddress()
        val mainReceiver=DatagramSocket(0,peer).apply{soTimeout=100}
        val alternateReceiver=DatagramSocket(0,peer).apply{soTimeout=1000}
        val held=AtomicBoolean(true);val leases=AtomicInteger()
        val format=PcmFormat(48000,2)
        val main=CarPlayMediaAudio(DatagramSocket(0,peer),peer,mainReceiver.localPort,format,1,102,
            ByteArray(32),null,{leases.incrementAndGet();Unit},{},{},canSend={!held.get()})
        val alternate=CarPlayMediaAudio(DatagramSocket(0,peer),peer,alternateReceiver.localPort,format,2,101,
            ByteArray(32),null,{},{},{},canSend={true})
        try{
            main.send(1,ByteArray(960),format)
            assertEquals(0,leases.get())
            assertThrows(SocketTimeoutException::class.java){mainReceiver.receive(DatagramPacket(ByteArray(2048),2048))}
            alternate.send(2,ByteArray(960),format)
            val alt=DatagramPacket(ByteArray(2048),2048);alternateReceiver.receive(alt)
            assertEquals(101,alt.data[1].toInt() and 127)
            held.set(false);main.send(1,ByteArray(960),format)
            val received=DatagramPacket(ByteArray(2048),2048);mainReceiver.receive(received)
            assertEquals(MediaAudioPackets.wirePayloadType(102),received.data[1].toInt() and 127);assertEquals(1,leases.get())
            held.set(true);main.send(1,ByteArray(960),format)
            assertThrows(SocketTimeoutException::class.java){mainReceiver.receive(DatagramPacket(ByteArray(2048),2048))}
        }finally{main.close();alternate.close();mainReceiver.close();alternateReceiver.close()}
    }
}
