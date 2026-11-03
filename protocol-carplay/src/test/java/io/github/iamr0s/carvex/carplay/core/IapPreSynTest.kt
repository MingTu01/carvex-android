package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.*

/** 24A446 pre-SYN vectors, including the FRONTLANDER 40/99/16/0/0 log.
 * Native oracle: research/connection/tools/probe_iap2_presyn.py.
 * Synthetic peers only; these do not establish real-vehicle compatibility. */
class IapPreSynTest {
    private val ack=IapWire.Frame(0x40,99,16,0,byteArrayOf())
    private fun syn(seq:Int=41,version:Int=1)=IapWire.Frame(0x80,seq,0,0,
        byteArrayOf(version.toByte(),5)+IapWire.be16(2048)+IapWire.be16(100)+IapWire.be16(10)+
            byteArrayOf(2,0)+(if(version==1)byteArrayOf() else byteArrayOf(0,1))+byteArrayOf(10,0,2))

    private class Peer(firstMs:Int=0,detectMs:Int=2000,failWrite:Boolean=false):AutoCloseable {
        val input=BoundedByteInput()
        val output=LinkedBlockingQueue<ByteArray>()
        val logs=LinkedBlockingQueue<String>()
        val failures=CopyOnWriteArrayList<Throwable>()
        val failureSignal=CountDownLatch(1)
        val executor=Executors.newCachedThreadPool()
        val link=IapLink(input,object:OutputStream(){
            override fun write(b:Int)=write(byteArrayOf(b.toByte()),0,1)
            override fun write(b:ByteArray,off:Int,len:Int){
                if(failWrite)throw IOException("synthetic pre-SYN write failure")
                output.add(b.copyOfRange(off,off+len))
            }
        },{logs.add(it)},{failures.add(it);failureSignal.countDown()},firstByteTimeoutMs=firstMs,detectTimeoutMs=detectMs)
        fun start(direct:Boolean=true)=executor.submit{link.start(allowDirectSyn=direct)}
        fun put(f:IapWire.Frame,version:Int=1){check(input.offer(IapWire.packet(f,version)))}
        fun detection(){check(input.offer(IapWire.detection))}
        fun bytes():ByteArray=checkNotNull(output.poll(2,TimeUnit.SECONDS)){"no output"}
        /** 本地静默探测的顺序：先 RST（清掉车机可能残留的旧链路状态），再 Detect。 */
        fun assertRstThenDetection(){
            val rst=bytes()
            assertEquals((-1).toByte(),rst[0]);assertEquals(0x5a.toByte(),rst[1])
            assertEquals("RST 的 flags 在本机默认 packetVersion=1 下位于第 5 字节",0x10,rst[4].toInt() and 0xff)
            assertArrayEquals(IapWire.detection,bytes())
        }
        fun read()=IapWire.read(ByteArrayInputStream(bytes()))
        fun logContains(text:String){
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(true){
                val line=logs.poll(maxOf(0,until-System.nanoTime()),TimeUnit.NANOSECONDS)
                assertNotNull("missing log: $text; failures=$failures",line)
                if(line!!.contains(text))return
            }
        }
        fun unready(){assertNull(link.negotiatedControlSession);assertNull(link.negotiatedSynchronizationSchema)}
        fun confirm(started:Future<*>?,response:IapWire.Frame){
            assertEquals(0xc0,response.flags)
            put(IapWire.Frame(0x40,response.ack,response.seq,0,byteArrayOf()))
            started?.get(2,TimeUnit.SECONDS)
            logContains("iAP synchronized")
        }
        fun failed(started:Future<*>):Throwable {
            val error=assertThrows(ExecutionException::class.java){started.get(3,TimeUnit.SECONDS)}
            // Timer closes input before publishing its callback outside writeLock;
            // the read future can finish first. Await the independent notification.
            assertTrue(failureSignal.await(1,TimeUnit.SECONDS))
            assertEquals(1,failures.size);unready()
            return checkNotNull(error.cause)
        }
        override fun close(){link.close();input.close();executor.shutdownNow()}
    }

    @Test(timeout=6000) fun loggedAckBeforeSynDoesNotFailOrPublishReadiness(){
        Peer().use{p->
            val started=p.start();p.put(ack);p.logContains("pre-SYN ACK ignored")
            p.unready();assertEquals(0,p.link.generation);assertNull(p.output.poll());assertFalse(started.isDone)
            p.put(syn());p.confirm(started,p.read());assertEquals(1,p.link.generation);assertTrue(p.failures.isEmpty())
        }
    }

    @Test(timeout=6000) fun ackAfterLocalIdleDetectAlsoWaitsForSyn(){
        Peer(firstMs=50).use{p->
            val started=p.start();p.assertRstThenDetection()
            p.put(ack);p.logContains("pre-SYN ACK ignored");p.unready();assertNull(p.output.poll())
            p.put(syn());p.confirm(started,p.read());assertTrue(p.failures.isEmpty())
        }
    }

    @Test(timeout=6000) fun ackAfterPeerDetectionDoesNotSendRst(){
        Peer().use{p->
            val started=p.start(direct=false);p.detection();assertArrayEquals(IapWire.detection,p.bytes())
            p.put(ack);p.logContains("pre-SYN ACK ignored");assertNull(p.output.poll());p.unready()
            p.put(syn());p.confirm(started,p.read())
        }
    }

    @Test(timeout=6000) fun repeatedAckDoesNotConsumeRestartBudget(){
        Peer().use{p->
            val started=p.start()
            repeat(12){p.put(ack);p.logContains("pre-SYN ACK ignored")}
            assertNull(p.output.poll());p.unready();p.put(syn());p.confirm(started,p.read())
        }
    }

    @Test(timeout=6000) fun ackOnlyEventuallyFailsWithNoSynNotUnexpectedFrame(){
        Peer(detectMs=250).use{p->
            val started=p.start();p.put(ack)
            val failure=p.failed(started)
            assertTrue(failure is SocketTimeoutException);assertTrue(failure.message!!.contains("NO_SYN"))
            assertNull(p.output.poll());assertEquals(0,p.link.generation)
        }
    }

    @Test(timeout=6000) fun ackFloodDoesNotRefreshAbsoluteDeadline(){
        Peer(detectMs=350).use{p->
            val started=p.start()
            val flood=p.executor.submit{
                while(!started.isDone){
                    if(!p.input.offer(IapWire.packet(ack)))break
                    Thread.sleep(15)
                }
            }
            assertTrue(p.failed(started) is SocketTimeoutException)
            flood.get(1,TimeUnit.SECONDS);assertNull(p.output.poll())
        }
    }

    @Test(timeout=6000) fun bareRstBeforeSynIsIgnoredWithoutResponse(){
        Peer().use{p->
            val started=p.start();p.put(IapWire.Frame(0x10,99,0,0,byteArrayOf()))
            p.logContains("bare RST ignored");assertNull(p.output.poll());p.unready()
            p.put(syn());p.confirm(started,p.read())
        }
    }

    @Test(timeout=6000) fun dataBeforeSynGetsNativeResetAndIsNeverDelivered(){
        Peer().use{p->
            val started=p.start();p.put(ack.copy(session=10,payload=IapWire.csm(0x5703)))
            assertArrayEquals(byteArrayOf(-1,0x5a,0,9,0x10,0,0,0,0x8e.toByte()),p.bytes())
            p.unready();assertNull(p.link.poll(0));p.put(ack);p.logContains("pre-SYN ACK ignored")
            assertNull(p.output.poll());p.put(syn());p.confirm(started,p.read());assertNull(p.link.poll(0))
        }
    }

    @Test(timeout=6000) fun malformedSynAfterAckStillFails(){
        Peer().use{p->
            val started=p.start();p.put(ack);p.put(syn().copy(payload=byteArrayOf(1)))
            assertTrue(p.failed(started) is IllegalArgumentException);assertNull(p.output.poll())
        }
    }

    @Test(timeout=6000) fun wrongFinalAckRecoversPendingWithoutTreatingItAsConfirmation(){
        Peer().use{p->
            val started=p.start();p.put(ack);p.put(syn());val response=p.read()
            p.put(ack.copy(seq=response.ack,ack=(response.seq+1) and 255))
            val reset=p.read()
            assertEquals(0x10,reset.flags);assertEquals(0,reset.seq);assertEquals(0,reset.ack)
            p.unready();assertFalse(started.isDone)
            p.put(syn(seq=42));p.confirm(started,p.read())
        }
    }

    @Test(timeout=6000) fun unknownControlIsNotSilentlyAccepted(){
        Peer().use{p->
            val started=p.start();p.put(ack.copy(flags=0x08))
            assertTrue(p.failed(started) is IllegalArgumentException);assertNull(p.output.poll())
        }
    }

    @Test(timeout=6000) fun invalidChecksumBeforeAckIsNotTreatedAsValidFrame(){
        Peer().use{p->
            val started=p.start()
            val corrupt=IapWire.packet(ack).apply{this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()}
            check(p.input.offer(corrupt+IapWire.packet(ack)+IapWire.packet(syn())))
            p.confirm(started,p.read());assertTrue(p.failures.isEmpty())
        }
    }

    @Test(timeout=6000) fun detectionAfterAckCanRestartRecognitionBeforeSyn(){
        Peer().use{p->
            val started=p.start();p.put(ack);p.logContains("pre-SYN ACK ignored")
            p.detection();assertArrayEquals(IapWire.detection,p.bytes());p.put(ack)
            p.logContains("pre-SYN ACK ignored");p.put(syn());p.confirm(started,p.read())
        }
    }

    @Test(timeout=6000) fun establishedDetectionThenAckCannotReachOldControlSession(){
        Peer().use{p->
            val started=p.start();p.put(syn(version=2));p.confirm(started,p.read())
            val epoch=p.link.linkEpoch
            p.detection();assertArrayEquals(IapWire.detection,p.bytes())
            p.put(ack);p.logContains("pre-SYN ACK ignored");p.unready()
            assertTrue(p.link.linkEpoch>epoch);assertEquals(1,p.link.generation);assertNull(p.output.poll())
            p.put(syn(seq=52));p.confirm(null,p.read());assertEquals(2,p.link.generation)
        }
    }

    @Test(timeout=6000) fun detectionAfterPendingSynThenAckHasFreshBoundedWait(){
        Peer(detectMs=250).use{p->
            val started=p.start();p.put(syn());p.read()
            // The old initial deadline expires while pending SYNACK has its own timer.
            assertNull(p.output.poll(350,TimeUnit.MILLISECONDS))
            p.detection();assertArrayEquals(IapWire.detection,p.bytes());p.put(ack)
            assertTrue(p.failed(started) is SocketTimeoutException)
            assertNull(p.output.poll())
        }
    }

    @Test(timeout=6000) fun resetWriteFailureIsReportedOnce(){
        Peer(failWrite=true).use{p->
            val started=p.start();p.put(ack.copy(session=10,payload=IapWire.csm(0x5703)))
            assertEquals("synthetic pre-SYN write failure",p.failed(started).message)
        }
    }

    @Test(timeout=6000) fun repeatedDetectionDoesNotRenewPreSynDeadline(){
        Peer(detectMs=450).use{p->
            val started=p.start();p.put(ack);p.logContains("pre-SYN ACK ignored")
            repeat(4){
                p.detection();assertArrayEquals(IapWire.detection,p.bytes())
                assertNull(p.output.poll(70,TimeUnit.MILLISECONDS))
            }
            assertTrue(p.failed(started) is SocketTimeoutException)
        }
    }

    @Test(timeout=6000) fun committedHandshakeCancelsPreSynDeadline(){
        Peer(detectMs=150).use{p->
            val started=p.start();p.put(ack);p.put(syn());p.confirm(started,p.read())
            assertNull(p.output.poll(300,TimeUnit.MILLISECONDS))
            assertNotNull(p.link.negotiatedControlSession);assertEquals(1,p.link.generation)
            assertTrue(p.failures.isEmpty())
        }
    }

    @Test(timeout=6000) fun eofAfterOrphanAckReportsOneFailure(){
        Peer().use{p->
            val started=p.start();p.put(ack);p.logContains("pre-SYN ACK ignored");p.input.close()
            assertTrue(p.failed(started) is java.io.EOFException);assertNull(p.output.poll())
        }
    }

    @Test(timeout=6000) fun explicitCloseCancelsDeadlineWithoutFailureCallback(){
        Peer(detectMs=150).use{p->
            val started=p.start();p.put(ack);p.logContains("pre-SYN ACK ignored");p.link.close()
            assertThrows(ExecutionException::class.java){started.get(1,TimeUnit.SECONDS)}
            assertNull(p.output.poll(300,TimeUnit.MILLISECONDS));assertTrue(p.failures.isEmpty())
        }
    }

    @Test(timeout=6000) fun orphanAckWithSessionIdDoesNotBypassValidation(){
        Peer().use{p->
            val started=p.start();p.put(ack.copy(session=10))
            assertTrue(p.failed(started) is IllegalArgumentException);assertNull(p.output.poll())
        }
    }
}
