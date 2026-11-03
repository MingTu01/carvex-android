package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Synthetic protocol peers exercise the boundaries established by docs/02 and the Audi vector.
 * No recorded authentication material is needed by these tests. */
class IapLinkSynchronizationTest {
    private fun sync(id:Int=10,version:Int=1,maxPacket:Int=2048,retries:Int=1,retryMs:Int=100,
                     window:Int=5,extra:ByteArray=byteArrayOf(),ackMs:Int=73,maxAck:Int=3) =
        byteArrayOf(1,window.toByte())+IapWire.be16(maxPacket)+IapWire.be16(retryMs)+IapWire.be16(ackMs)+
            byteArrayOf(retries.toByte(),maxAck.toByte(),id.toByte(),0,version.toByte())+extra

    /** 固件 `iap2-mainline.md`：version 2/3 的 SYN 在 10 处是扩展数量+扩展字节+session 数量。 */
    private fun syncMulti(link:Int,id:Int=10,version:Int=2,maxPacket:Int=2048,retries:Int=1,
                          retryMs:Int=100,window:Int=5,extensions:ByteArray=byteArrayOf()) =
        byteArrayOf(link.toByte(),window.toByte())+IapWire.be16(maxPacket)+IapWire.be16(retryMs)+IapWire.be16(73)+
            byteArrayOf(retries.toByte(),1)+byteArrayOf(extensions.size.toByte())+extensions+
            byteArrayOf(1,id.toByte(),0,version.toByte())

    private class Peer(localTx:Int=2048,localRx:Int=2048,localWindow:Int=32,
        eak:IapLink.EakSchema=IapLink.EakSchema.EAK_RECEIVED_LIST_V1,
        localAckMs:Int=10,localMaxAck:Int=0):AutoCloseable {
        val phoneInput=BoundedByteInput()
        val carInput=BoundedByteInput()
        val phoneOutput=java.io.ByteArrayOutputStream()
        val logs=CopyOnWriteArrayList<String>()
        val failures=CopyOnWriteArrayList<Throwable>()
        val failureSignal=java.util.concurrent.CountDownLatch(1)
        val executor=Executors.newCachedThreadPool()
        val link=IapLink(phoneInput,object:OutputStream(){
            override fun write(b:Int){phoneOutput.write(b);check(carInput.offer(byteArrayOf(b.toByte())))}
            override fun write(b:ByteArray,off:Int,len:Int){
                phoneOutput.write(b,off,len);check(carInput.offer(b.copyOfRange(off,off+len)))
            }
        },{logs.add(it)},{failures.add(it);failureSignal.countDown()},localTx,localRx,eak,localWindow,
            localCumulativeAckTimeoutMs=localAckMs,localMaxCumulativeAck=localMaxAck)
        fun put(frame:IapWire.Frame,packetVersion:Int=1){check(phoneInput.offer(IapWire.packet(frame,packetVersion)))}
        fun begin(payload:ByteArray,seq:Int=254):Pair<Future<*>,IapWire.Frame>{
            val started=executor.submit{link.start(allowDirectSyn=true)}
            put(IapWire.Frame(0x80,seq,0,0,payload))
            val response=IapWire.read(carInput)
            assertEquals(0xc0,response.flags)
            assertEquals(seq,response.ack)
            return started to response
        }
        fun establish(payload:ByteArray,seq:Int=254):IapWire.Frame{
            val (started,response)=begin(payload,seq)
            put(IapWire.Frame(0x40,seq,response.seq,0,byteArrayOf()))
            started.get(3,TimeUnit.SECONDS)
            return response
        }
        override fun close(){link.close();phoneInput.close();carInput.close();executor.shutdownNow()}
    }

    /** Pending ResendSYNACK uses active default retries=30, not the uncommitted
     * proposal's retries=2. Timer and duplicate-triggered sends share that budget. */
    @Test(timeout=6000) fun repeatedIdenticalSynIsAnsweredUntilTheActiveRetryBudgetIsSpent(){
        Peer().use{peer->
            val (started,response)=peer.begin(sync(retries=2))
            repeat(30){attempt->
                peer.put(IapWire.Frame(0x80,254,0,0,sync(retries=2)))
                val again=IapWire.read(peer.carInput)
                assertEquals(0xc0,again.flags)
                assertEquals("retransmit #${attempt+1} must reuse the same PSN",response.seq,again.seq)
                assertArrayEquals(response.payload,again.payload)
            }
            peer.put(IapWire.Frame(0x80,254,0,0,sync(retries=2)))
            assertThrows(ExecutionException::class.java){started.get(3,TimeUnit.SECONDS)}
            assertEquals(1,peer.failures.size)
            assertTrue(peer.failures.single().message!!.contains("link dead"))
        }
    }

    @Test(timeout=6000) fun linkVersion3SynIsAcceptedAndAnsweredInVersion3(){
        Peer().use{peer->
            val (started,response)=peer.begin(syncMulti(link=3,id=10,version=2))
            assertEquals("response link version",3,response.payload[0].toInt() and 255)
            // v3 正文 = 10 字节固定头 + 扩展数量(0) + session 数量(1) + 一个 3 字节描述符
            assertEquals(15,response.payload.size)
            peer.put(IapWire.Frame(0x40,254,response.seq,0,byteArrayOf()))
            started.get(3,TimeUnit.SECONDS)
            assertTrue(peer.failures.isEmpty())
            assertEquals(10,peer.link.negotiatedControlSession!!.id)
            assertEquals(2,peer.link.negotiatedControlSession!!.version)
            // A captured 23G90 session has link version 3 with control version 2;
            // v3 still uses the 9-byte/type0 packet framing.
            peer.put(IapWire.Frame(0x40,255,response.seq,10,IapWire.csm(0x5703)))
            val ack=IapWire.read(peer.carInput,packetVersion=1)
            assertEquals(0x40,ack.flags)
            assertEquals(255,ack.ack)
            assertEquals(0x5703,peer.link.poll(1000)?.id)
        }
    }

    /** 固件 FRAME24-01/03：SYNACK 仍用协商前版本；最终纯 ACK 后，version 2 才切换到
     *  10 字节头 + 2 字节校验。后续数据和 ACK 都必须使用该格式。 */
    @Test(timeout=6000) fun linkVersion2SwitchesPacketFramingAfterPureAck(){
        Peer().use{peer->
            val (started,response)=peer.begin(syncMulti(link=2))
            assertEquals(2,response.payload[0].toInt() and 255)
            assertEquals(15,response.payload.size)
            peer.put(IapWire.Frame(0x40,254,response.seq,0,byteArrayOf()))
            started.get(3,TimeUnit.SECONDS)
            assertTrue(peer.failures.isEmpty())

            // The first post-handshake frame is v2 on the wire, and the link's ACK is v2 too.
            val inbound=IapWire.Frame(0x40,255,response.seq,10,IapWire.csm(0x5703))
            peer.put(inbound,packetVersion=2)
            val ack=IapWire.read(peer.carInput,packetVersion=2)
            assertEquals(0x40,ack.flags)
            assertEquals(255,ack.ack)
            assertEquals(0,ack.session)
            assertEquals(0x5703,peer.link.poll(1000)?.id)

            // Outbound application data also uses v2 and remains reliable.
            val sending=peer.executor.submit{peer.link.send(0x1d00)}
            val outbound=IapWire.read(peer.carInput,packetVersion=2)
            assertEquals(10,outbound.session)
            peer.put(IapWire.Frame(0x40,0,outbound.seq,0,byteArrayOf()),packetVersion=2)
            sending.get(3,TimeUnit.SECONDS)
        }
    }

    @Test(timeout=6000) fun unsupportedLinkVersionIsRejected(){
        Peer().use{peer->
            val started=peer.executor.submit<Any?>{peer.link.start(allowDirectSyn=true)}
            peer.put(IapWire.Frame(0x80,254,0,0,syncMulti(link=4)))
            assertThrows(ExecutionException::class.java){started.get(3,TimeUnit.SECONDS)}
            assertEquals(1,peer.failures.size)
        }
    }

    @Test(timeout=6000) fun unsupportedControlVersionsProduceNoSynAckOrCommittedSession(){
        for(linkVersion in 1..3)for(controlVersion in listOf(0,3,255))Peer().use{peer->
            val payload=if(linkVersion==1)sync(version=controlVersion)
                else syncMulti(link=linkVersion,version=controlVersion)
            val started=peer.executor.submit{peer.link.start(allowDirectSyn=true,detectOnIdle=false)}
            peer.put(IapWire.Frame(0x80,254,0,0,payload))
            val error=assertThrows(ExecutionException::class.java){started.get(2,TimeUnit.SECONDS)}
            assertEquals("Unsupported control session version $controlVersion",error.cause?.message)
            assertArrayEquals("unsupported control version must not be echoed or downgraded",
                byteArrayOf(),peer.phoneOutput.toByteArray())
            assertNull(peer.link.negotiatedControlSession)
            assertNull(peer.link.negotiatedSynchronizationSchema)
            assertEquals(0,peer.link.generation)
            assertEquals(1,peer.failures.size)
        }
    }

    @Test(timeout=6000) fun supportedControlVersionsRemainIndependentOfLinkFramingVersion(){
        for(linkVersion in 1..3)for(controlVersion in 1..2)Peer().use{peer->
            val payload=if(linkVersion==1)sync(id=7,version=controlVersion)
                else syncMulti(link=linkVersion,id=7,version=controlVersion)
            val response=peer.establish(payload)
            assertEquals(linkVersion,response.payload[0].toInt() and 255)
            assertEquals(controlVersion,response.payload.last().toInt() and 255)
            assertEquals(IapLink.ControlSession(7,0,controlVersion),peer.link.negotiatedControlSession)
            assertEquals(1,peer.link.generation)
            val packetVersion=if(linkVersion==2)2 else 1
            peer.put(IapWire.Frame(0x40,255,response.seq,7,IapWire.csm(0x5703)),packetVersion)
            assertEquals(255,IapWire.read(peer.carInput,packetVersion=packetVersion).ack)
            assertEquals(0x5703,peer.link.poll(1000)?.id)
            assertTrue(peer.failures.isEmpty())
        }
    }

    /** 24A446 CheckNegotiation preserves the peer's ordered session table.
     * Control remains the routing session, but extra sessions must not be
     * silently removed from SYNACK. Vectors match the BMW and Outback logs. */
    @Test(timeout=6000) fun synAckPreservesEveryPeerSessionDescriptor(){
        val cases=listOf(
            Triple(10,1,byteArrayOf(12,2,1)),
            Triple(1,2,byteArrayOf(2,1,1,3,2,1)),
        )
        for((id,controlVersion,extra) in cases)Peer().use{peer->
            val offered=byteArrayOf(id.toByte(),0,controlVersion.toByte())+extra
            val response=peer.establish(sync(id=id,version=controlVersion,extra=extra))
            assertEquals(10+offered.size,response.payload.size)
            assertArrayEquals(offered,response.payload.copyOfRange(10,response.payload.size))
            assertEquals(IapLink.ControlSession(id,0,controlVersion),peer.link.negotiatedControlSession)
            assertEquals(1,peer.link.generation)
            assertTrue(peer.failures.isEmpty())
        }
    }

    /** ch01 §2.3：四项全零是合法的“无重传”模式，允许 window=0；发送不等 ACK。 */
    @Test(timeout=6000) fun noRetransmitSynIsAcceptedAndSendDoesNotWaitForAnAck(){
        Peer().use{peer->
            val payload=sync(retries=0,retryMs=0,ackMs=0,maxAck=0,window=0)
            val (started,response)=peer.begin(payload)
            peer.put(IapWire.Frame(0x40,254,response.seq,0,byteArrayOf()))
            started.get(3,TimeUnit.SECONDS)
            assertNotNull(peer.link.negotiatedControlSession)
            peer.link.send(0xaa00)
            assertNotNull("no-retransmit send must write immediately",IapWire.read(peer.carInput))
        }
    }

    /** Firmware iAP2MsgSendControl returns after queue submission; ACK timeout is
     * delivered later through the optional sent callback. */
    @Test(timeout=6000) fun asyncSendReturnsBeforeAckAndReportsCompletion(){
        Peer().use{peer->
            peer.establish(sync(retries=1,retryMs=300))
            val completed=java.util.concurrent.CountDownLatch(1)
            val result=java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val started=System.nanoTime()
            assertTrue(peer.link.sendAsync(0xaa00,completion={result.set(it);completed.countDown()}))
            val elapsed=(System.nanoTime()-started)/1_000_000
            assertTrue("queue submission must not wait for ACK: ${elapsed}ms",elapsed<100)
            val outbound=IapWire.read(peer.carInput)
            assertEquals(0xaa00,IapWire.u16(outbound.payload,4))
            peer.put(IapWire.Frame(0x40,0,outbound.seq,0,byteArrayOf()))
            assertTrue(completed.await(2,TimeUnit.SECONDS))
            assertNull(result.get())
        }
    }

    @Test(timeout=6000) fun asyncSendReportsAckTimeoutWithoutBlockingCaller(){
        Peer().use{peer->
            peer.establish(sync(retries=1,retryMs=100))
            val completed=java.util.concurrent.CountDownLatch(1)
            val result=java.util.concurrent.atomic.AtomicReference<Throwable?>()
            assertTrue(peer.link.sendAsync(0xaa00,completion={result.set(it);completed.countDown()}))
            IapWire.read(peer.carInput) // do not acknowledge
            assertTrue(completed.await(2,TimeUnit.SECONDS))
            assertTrue(result.get() is java.net.SocketTimeoutException)
            assertTrue(peer.failures.isNotEmpty())
        }
    }

    /** 24A446 ProcessInOrderPacket: a bare 0x10 RST is an invalid control
     * packet.  The original run-once vector emits no response and leaves the
     * established session unchanged. */
    @Test(timeout=6000) fun bareRstWhileConnectedIsIgnoredWithoutResponse(){
        Peer().use{peer->
            peer.establish(sync())
            peer.put(IapWire.Frame(0x10,254,0,0,byteArrayOf()))
            val noResponse=peer.executor.submit<IapWire.Frame>{IapWire.read(peer.carInput)}
            assertThrows(java.util.concurrent.TimeoutException::class.java){
                noResponse.get(200,TimeUnit.MILLISECONDS)
            }
            noResponse.cancel(true)
            assertNotNull(peer.link.negotiatedControlSession)
            assertTrue(peer.failures.isEmpty())
        }
    }

    @Test(timeout=6000) fun exposesControlDescriptorOnlyAfterMatchingConfirmation(){
        Peer().use{peer->
            val (started,response)=peer.begin(sync(id=1,version=2))
            assertNull(peer.link.negotiatedControlSession)
            assertEquals(0,peer.link.generation)
            assertThrows(IllegalStateException::class.java){peer.link.send(0xaa00)}
            peer.put(IapWire.Frame(0x40,254,response.seq,0,byteArrayOf()))
            started.get(3,TimeUnit.SECONDS)
            assertEquals(IapLink.ControlSession(1,0,2),peer.link.negotiatedControlSession)
            assertEquals(1,peer.link.generation)
            peer.link.close()
            assertNull(peer.link.negotiatedControlSession)
        }
    }

    @Test(timeout=6000) fun staleAcknowledgementNeverPublishesTheNegotiatedSession(){
        Peer().use{peer->
            val (started,response)=peer.begin(sync())
            peer.put(IapWire.Frame(0x40,254,(response.seq+1) and 255,0,byteArrayOf()))
            // Unsigned gap255 is outside the active default window: reset the
            // pending proposal without treating the bad ACK as confirmation.
            val reset=IapWire.read(peer.carInput)
            assertEquals(0x10,reset.flags);assertEquals(0,reset.seq)
            assertEquals(0,reset.ack);assertEquals(0,reset.session)
            assertTrue(reset.payload.isEmpty())
            peer.put(IapWire.Frame(0x40,254,response.seq,0,byteArrayOf()))
            assertThrows(java.util.concurrent.TimeoutException::class.java){started.get(200,TimeUnit.MILLISECONDS)}
            assertNull(peer.link.negotiatedControlSession)
            assertEquals(0,peer.link.generation)
            assertTrue(peer.failures.isEmpty())
        }
    }

    @Test(timeout=6000) fun malformedSynAckCannotCompleteAnOtherwiseValidHandshake(){
        Peer().use{peer->
            val (started,response)=peer.begin(sync())
            // The old implementation accepted this solely because ACK named the initial PSN.
            peer.put(IapWire.Frame(0xc0,17,response.seq,0,byteArrayOf(1)))
            assertThrows(ExecutionException::class.java){started.get(3,TimeUnit.SECONDS)}
            assertNull(peer.link.negotiatedControlSession)
        }
    }

    /** Native pending events4/6 both send SYNACK; only pure confirmation commits. */
    @Test(timeout=6000) fun matchingPeerSynAckRequiresAnotherSynAckAndPureConfirmation(){
        for(linkVersion in 1..3)for(controlVersion in 1..2)Peer().use{peer->
            val payload=if(linkVersion==1)sync(id=1,version=controlVersion)
                else syncMulti(link=linkVersion,id=1,version=controlVersion)
            val (started,response)=peer.begin(payload)
            peer.put(IapWire.Frame(0xc0,17,response.seq,0,payload))
            val confirmation=IapWire.read(peer.carInput)
            assertEquals(0xc0,confirmation.flags)
            assertEquals(17,confirmation.ack)
            assertEquals(0,confirmation.session)
            assertFalse(confirmation.payload.isEmpty())
            assertNull(peer.link.negotiatedControlSession)
            peer.put(IapWire.Frame(0x40,17,confirmation.seq,0,byteArrayOf()))
            started.get(3,TimeUnit.SECONDS)
            assertEquals(IapLink.ControlSession(1,0,controlVersion),peer.link.negotiatedControlSession)
            assertEquals(1,peer.link.generation)
            val packetVersion=if(linkVersion==2)2 else 1
            peer.put(IapWire.Frame(0x40,18,confirmation.seq,1,IapWire.csm(0x5703)),packetVersion)
            assertEquals(18,IapWire.read(peer.carInput,packetVersion=packetVersion).ack)
            assertEquals(0x5703,peer.link.poll(1000)?.id)
        }
    }

    @Test(timeout=6000) fun supportedChangedPeerSynAckParametersStayPendingUntilConfirmation(){
        val first=sync(id=1,version=1)
        val proposals=listOf(
            sync(id=2,version=1),
            sync(id=1,version=2),
            syncMulti(link=2,id=1,version=1),
            syncMulti(link=3,id=1,version=1),
            sync(id=1,extra=byteArrayOf(2,1,1)),
            sync(id=1,window=6),
            sync(id=1,retryMs=200),
            sync(id=1,ackMs=80),
            sync(id=1,retries=2),
            sync(id=1,maxAck=2),
        )
        for(second in proposals)Peer().use{peer->
            val (started,response)=peer.begin(first)
            peer.put(IapWire.Frame(0xc0,17,response.seq,0,second))
            val next=IapWire.read(peer.carInput)
            assertEquals(0xc0,next.flags)
            assertEquals((response.seq+1) and 255,next.seq)
            assertEquals(1,next.payload[0].toInt()) // current work cannot upgrade link1
            assertNull(peer.link.negotiatedControlSession)
            assertNull(peer.link.negotiatedSynchronizationSchema)
            assertEquals(0,peer.link.generation)
            peer.put(IapWire.Frame(0x40,17,next.seq,0,byteArrayOf()))
            started.get(3,TimeUnit.SECONDS)
            assertEquals(1,peer.link.generation)
            assertTrue(peer.failures.isEmpty())
        }
    }

    @Test(timeout=6000) fun confirmedPeerSynAckUpdatesTransmitMtuWithinLocalLimit(){
        for((firstMtu,secondMtu) in listOf(2048 to 24,24 to 2048))Peer().use{peer->
            val (started,response)=peer.begin(sync(id=1,maxPacket=firstMtu))
            peer.put(IapWire.Frame(0xc0,17,response.seq,0,sync(id=1,maxPacket=secondMtu)))
            val confirmation=IapWire.read(peer.carInput)
            assertEquals(0xc0,confirmation.flags)
            assertEquals(17,confirmation.ack)
            peer.put(IapWire.Frame(0x40,17,confirmation.seq,0,byteArrayOf()))
            started.get(3,TimeUnit.SECONDS)
            assertEquals(IapLink.ControlSession(1,0,1),peer.link.negotiatedControlSession)
            val body=ByteArray(50){it.toByte()}
            val expected=IapWire.csm(0x1d01,listOf(0 to body))
            val sending=peer.executor.submit{peer.link.send(0x1d01,listOf(0 to body))}
            val received=java.io.ByteArrayOutputStream()
            while(received.size()<expected.size){
                val data=IapWire.read(peer.carInput)
                assertTrue("latest peer MTU must bound actual output",IapWire.packet(data).size<=secondMtu)
                assertEquals(1,data.session)
                received.write(data.payload)
                peer.put(IapWire.Frame(0x40,17,data.seq,0,byteArrayOf()))
            }
            sending.get(3,TimeUnit.SECONDS)
            assertArrayEquals(expected,received.toByteArray())
            assertTrue(peer.failures.isEmpty())
        }
    }

    @Test(timeout=6000) fun capturedSchemaDoesNotTreatPayloadAckOrChangedPsnAsConfirmation(){
        for((seq,session,payload) in listOf(Triple(255,1,IapWire.csm(0x1d01)),Triple(17,0,byteArrayOf())))Peer().use{peer->
            val (started,response)=peer.begin(sync(id=1),seq=254)
            peer.put(IapWire.Frame(0x40,seq,response.seq,session,payload))
            val error=assertThrows(ExecutionException::class.java){started.get(2,TimeUnit.SECONDS)}
            assertEquals("SYNC_CONFIRMATION_INVALID",error.cause?.message)
            assertNull(peer.link.negotiatedControlSession)
        }
    }

    @Test(timeout=6000) fun receiveWindowUsesOurOfferIndependentlyOfThePeerTransmitLimit(){
        Peer().use{peer->
            val response=peer.establish(sync(id=1,window=1,maxAck=1))
            assertEquals(32,response.payload[1].toInt())
            peer.put(IapWire.Frame(0x40,0,response.seq,1,IapWire.csm(0x4301)))
            assertEquals(254,IapWire.read(peer.carInput).ack)
            peer.put(IapWire.Frame(0x40,255,response.seq,1,IapWire.csm(0x5703)))
            assertEquals(0,IapWire.read(peer.carInput).ack)
            assertEquals(0x5703,peer.link.poll(1000)?.id)
            assertEquals(0x4301,peer.link.poll(1000)?.id)
        }
    }

    /** Current local offer policy is retained; it is not the complete 24A446
     * endpoint profile (whose local window can be 127). */
    @Test(timeout=6000) fun synAckUsesConfiguredLocalOfferAndNegotiatedLowerBounds(){
        Peer(localTx=65535,localRx=65535,localWindow=1).use{peer->
            val response=peer.establish(sync(window=5,maxPacket=65535,retries=15,retryMs=2000,ackMs=500,maxAck=1))
            assertEquals(1,response.payload[1].toInt() and 255)
            assertEquals(65535,IapWire.u16(response.payload,2))
            assertEquals(1000,IapWire.u16(response.payload,4))
            assertEquals(10,IapWire.u16(response.payload,6))
            assertEquals(15,response.payload[8].toInt() and 255)
            assertEquals(0,response.payload[9].toInt() and 255)
        }
    }

    @Test(timeout=6000) fun rejectsUnknownLinkSchemaTruncatedEntriesAndConflictingSessions(){
        val good=sync()
        val malformed=listOf(
            good.copyOf().apply{this[0]=2}, // Unknown synchronization version, not control version.
            good+byteArrayOf(1), // Descriptor remainder must be a multiple of three.
            good.copyOfRange(0,10), // A valid scalar prefix still needs one usable control session.
            good.copyOf().apply{this[2]=0;this[3]=10}, // No room for a data byte and checksum.
            good+byteArrayOf(10,1,1), // One ID cannot name two session kinds.
            good+byteArrayOf(10,0,1), // Repeated identical ID is also ambiguous.
            good+byteArrayOf(11,0,2), // More than one control session has no routing policy.
            sync(id=0),sync(version=0),sync(retryMs=0),sync(ackMs=0)
        )
        for(payload in malformed)Peer().use{peer->
            val started=peer.executor.submit{peer.link.start(allowDirectSyn=true)}
            peer.put(IapWire.Frame(0x80,254,0,0,payload))
            assertThrows(ExecutionException::class.java){started.get(2,TimeUnit.SECONDS)}
            assertNull(peer.link.negotiatedControlSession)
            assertEquals(0,peer.link.generation)
        }
    }

    @Test(timeout=6000) fun negotiatedSmallMaxLengthSplitsCsmAndUsesDynamicSessionId(){
        Peer().use{peer->
            val response=peer.establish(sync(id=1,maxPacket=24))
            val body=ByteArray(50){it.toByte()}
            val expected=IapWire.csm(0x1d01,listOf(0 to body))
            val sending=peer.executor.submit{peer.link.send(0x1d01,listOf(0 to body))}
            val received=java.io.ByteArrayOutputStream()
            var previous=response.seq
            while(received.size()<expected.size){
                val data=IapWire.read(peer.carInput)
                assertTrue(IapWire.packet(data).size<=24)
                assertEquals(1,data.session)
                assertEquals((previous+1) and 255,data.seq)
                received.write(data.payload);previous=data.seq
                peer.put(IapWire.Frame(0x40,254,data.seq,0,byteArrayOf()))
            }
            sending.get(3,TimeUnit.SECONDS)
            assertArrayEquals(expected,received.toByteArray())
        }
    }

    @Test(timeout=6000) fun transmitLimitAlsoHonorsTheAdapterAndSynAdvertisesActualReceiveLimit(){
        Peer(localTx=766,localRx=1024).use{peer->
            val response=peer.establish(sync(id=3,version=2,maxPacket=4096,maxAck=0,retryMs=1000,ackMs=500))
            assertEquals(1024,IapWire.u16(response.payload,2))
            assertEquals(IapLink.SynchronizationSchema.LINK_SYNC_V1_IMMEDIATE_ACK,peer.link.negotiatedSynchronizationSchema)
            val body=ByteArray(1800){it.toByte()}
            val expected=IapWire.csm(0x1d01,listOf(0 to body))
            val sending=peer.executor.submit{peer.link.send(0x1d01,listOf(0 to body))}
            val received=java.io.ByteArrayOutputStream()
            while(received.size()<expected.size){
                val frame=IapWire.read(peer.carInput)
                assertTrue(IapWire.packet(frame).size<=766)
                assertEquals(3,frame.session)
                received.write(frame.payload)
                peer.put(IapWire.Frame(0x40,254,frame.seq,0,byteArrayOf()))
            }
            sending.get(3,TimeUnit.SECONDS)
            assertArrayEquals(expected,received.toByteArray())
            assertTrue(peer.failures.isEmpty())
        }
    }

    @Test(timeout=6000) fun zeroCumulativeAckStillAcknowledgesEveryDataFrameAndRetainsReliability(){
        Peer().use{peer->
            val response=peer.establish(sync(id=2,version=2,maxPacket=4096,retryMs=1000,ackMs=500,maxAck=0))
            for(seq in listOf(255,0)){
                peer.put(IapWire.Frame(0x40,seq,response.seq,2,IapWire.csm(0x5703)))
                val ack=IapWire.read(peer.carInput)
                assertEquals(0x40,ack.flags);assertEquals(seq,ack.ack);assertTrue(ack.payload.isEmpty())
                assertEquals(0x5703,peer.link.poll(1000)?.id)
            }
            val sending=peer.executor.submit{peer.link.send(0xaa00)}
            val outbound=IapWire.read(peer.carInput)
            assertFalse(sending.isDone)
            peer.put(IapWire.Frame(0x40,0,(outbound.seq+1) and 255,0,byteArrayOf()))
            assertFalse(sending.isDone)
            peer.put(IapWire.Frame(0x40,0,outbound.seq,0,byteArrayOf()))
            sending.get(3,TimeUnit.SECONDS)
        }
    }

    @Test(timeout=6000) fun oversizedInboundFrameIsRejectedBeforeItCanEnterCsmReassembly(){
        Peer(localRx=64).use{peer->
            val response=peer.establish(sync())
            peer.put(IapWire.Frame(0x40,255,response.seq,10,IapWire.csm(0xaa01,listOf(0 to ByteArray(45)))))
            peer.put(IapWire.Frame(0x40,255,response.seq,10,IapWire.csm(0x5703)))
            assertEquals(255,IapWire.read(peer.carInput).ack)
            assertEquals(0x5703,peer.link.poll(1000)?.id)
            assertNull(peer.link.poll(0))
            assertTrue(peer.failures.isEmpty())
        }
    }

    @Test(timeout=6000) fun repeatedSynReusesTheSameResponseWithoutRestartingTransactions(){
        Peer().use{peer->
            val payload=sync(id=2,version=2,maxAck=0)
            val (started,response)=peer.begin(payload,seq=116)
            peer.put(IapWire.Frame(0x80,116,0,0,payload))
            val repeated=IapWire.read(peer.carInput)
            assertArrayEquals(IapWire.packet(response),IapWire.packet(repeated))
            peer.put(IapWire.Frame(0x40,116,response.seq,0,byteArrayOf()))
            started.get(3,TimeUnit.SECONDS)
            val epoch=peer.link.linkEpoch
            peer.put(IapWire.Frame(0x80,116,0,0,payload))
            assertArrayEquals(IapWire.packet(response),IapWire.packet(IapWire.read(peer.carInput)))
            assertEquals(1,peer.link.generation);assertEquals(epoch,peer.link.linkEpoch)
            peer.put(IapWire.Frame(0x40,117,response.seq,2,IapWire.csm(0x1d01)))
            assertEquals(117,IapWire.read(peer.carInput).ack)
            assertEquals(0x1d01,peer.link.poll(1000)?.id)
        }
    }

    /** An explicitly disabled EAK schema is rejected; SLP remains unsupported.
     * Bare RST handling is covered by the firmware invalid-control test above. */
    @Test(timeout=6000) fun unsupportedEakAndSlpCloseTheLinkWithoutPretendingRecovery(){
        Peer(eak=IapLink.EakSchema.UNSUPPORTED).use{peer->
            val response=peer.establish(sync())
            peer.put(IapWire.Frame(0x60,254,response.seq,0,byteArrayOf(1)))
            assertTrue(peer.failureSignal.await(2,TimeUnit.SECONDS))
            assertNull(peer.link.negotiatedControlSession)
            assertEquals(1,peer.link.generation)
            assertThrows(java.io.IOException::class.java){peer.link.poll(0)}
        }
        Peer().use{peer->
            val response=peer.establish(sync())
            peer.put(IapWire.Frame(0x08,254,response.seq,0,byteArrayOf()))
            assertTrue(peer.failureSignal.await(2,TimeUnit.SECONDS))
            assertNull(peer.link.negotiatedControlSession)
        }
    }

    /** Composite 0x50 is not the bare-RST form.  Its ACK/data path remains
     * active, so a valid CSM is delivered and acknowledged. */
    @Test(timeout=6000) fun compositeRstDataUsesNormalAckAndDeliveryPath(){
        Peer().use{peer->
            val response=peer.establish(sync())
            peer.put(IapWire.Frame(0x50,255,response.seq,10,IapWire.csm(0x5703)))
            val ack=IapWire.read(peer.carInput)
            assertEquals(0x40,ack.flags)
            assertEquals(255,ack.ack)
            assertEquals(0x5703,peer.link.poll(1000)?.id)
            assertNotNull(peer.link.negotiatedControlSession)
            assertTrue(peer.failures.isEmpty())
        }
    }

    @Test(timeout=6000) fun outOfOrderWithDefaultEakUsesReceivedPsnsAndWaitsForTheMissingFrame(){
        Peer().use{peer->
            val response=peer.establish(sync())
            peer.put(IapWire.Frame(0x40,0,response.seq,10,IapWire.csm(0x4301)))
            val ack=IapWire.read(peer.carInput)
            assertEquals(0x60,ack.flags);assertEquals(254,ack.ack)
            assertArrayEquals(byteArrayOf(0),ack.payload)
            peer.put(IapWire.Frame(0x40,255,response.seq,10,IapWire.csm(0x5703)))
            assertEquals(0,IapWire.read(peer.carInput).ack)
            assertEquals(0x5703,peer.link.poll(1000)?.id)
            assertEquals(0x4301,peer.link.poll(1000)?.id)
        }
    }

    /** Firmware 24A446 dispatches flags bit2 before ordinary CSM handling.
     *  A 0x55 time-sync response must therefore be observable as a command,
     *  and its PSN becomes the receive ACK baseline for the next data frame. */
    @Test(timeout=6000) fun cmdTimeSyncFrameBypassesCsmAndAdvancesAckBaseline(){
        Peer().use{peer->
            val response=peer.establish(syncMulti(link=2))
            val timeBody=ByteArray(24){(it+1).toByte()}
            peer.put(IapWire.Frame(0x44,255,response.seq,0,byteArrayOf(0x55)+timeBody),packetVersion=2)
            val command=peer.link.poll(1000)
            assertTrue(command is IapCommand.Control)
            val control=command as IapCommand.Control
            assertEquals(0x55,control.opcode)
            assertEquals(10,control.fsmEvent)
            assertTrue(control.timingContextRequired)
            assertArrayEquals(timeBody,control.body)
            assertEquals(255,control.frame.seq)
            // CMD itself has no CSM ACK packet; the following in-order data
            // frame carries the ACK baseline established by the CMD handler.
            peer.put(IapWire.Frame(0x40,0,response.seq,10,IapWire.csm(0x5703)),packetVersion=2)
            val ack=IapWire.read(peer.carInput,packetVersion=2)
            assertEquals(0,ack.ack)
            assertEquals(0x5703,peer.link.poll(1000)?.id)
            assertTrue(peer.failures.isEmpty())
        }
    }

    /** Firmware maps the request opcode to FSM event 9 and exposes the event
     *  before any timestamp interpretation. */
    @Test(timeout=6000) fun cmdTimeSyncRequestMapsToFirmwareFsmEventNine(){
        Peer().use{peer->
            val response=peer.establish(syncMulti(link=2))
            peer.put(IapWire.Frame(0x44,255,response.seq,0,byteArrayOf(0x54,1,2,3)),packetVersion=2)
            val command=peer.link.poll(1000) as IapCommand.Control
            assertEquals(0x54,command.opcode)
            assertEquals(9,command.fsmEvent)
            assertArrayEquals(byteArrayOf(1,2,3),command.body)
            assertTrue(peer.failures.isEmpty())
        }
    }

    /** The native CMD handler returns false for unknown opcodes, so they do
     *  not become application commands. */
    @Test(timeout=6000) fun unknownCmdOpcodeIsNotDeliveredToApplication(){
        Peer().use{peer->
            val response=peer.establish(sync())
            peer.put(IapWire.Frame(0x44,255,response.seq,0,byteArrayOf(0x56,9,8,7)))
            assertNull(peer.link.poll(100))
            assertTrue(peer.failures.isEmpty())
        }
    }

    /** CMD2-02: version 2 updates lastRX before returning unhandled for an
     * unknown opcode; the next ordered data frame must therefore be ACKed from
     * the CMD PSN even though no application command was delivered. */
    @Test(timeout=6000) fun unknownVersionTwoCmdUpdatesRxWithoutDelivery(){
        Peer().use{peer->
            val response=peer.establish(syncMulti(link=2))
            peer.put(IapWire.Frame(0x44,255,response.seq,0,byteArrayOf(0x56,9,8,7)),packetVersion=2)
            assertNull(peer.link.poll(100))
            peer.put(IapWire.Frame(0x40,0,response.seq,10,IapWire.csm(0x5703)),packetVersion=2)
            val ack=IapWire.read(peer.carInput,packetVersion=2)
            assertEquals(0,ack.ack)
            assertEquals(0x5703,peer.link.poll(1000)?.id)
            assertTrue(peer.failures.isEmpty())
        }
    }

    /** Firmware's CMD handler does not consume the frame ACK field.  A 0x44
     * time-sync packet therefore cannot complete an unrelated pending send. */
    @Test(timeout=6000) fun cmdAckFieldDoesNotCompletePendingDataSend(){
        Peer().use{peer->
            val response=peer.establish(syncMulti(link=2,retryMs=10000))
            val sending=peer.executor.submit{peer.link.send(0xaa00)}
            val outbound=IapWire.read(peer.carInput,packetVersion=2)
            peer.put(IapWire.Frame(0x44,255,outbound.seq,0,byteArrayOf(0x55,1,2,3)),packetVersion=2)
            assertTrue(peer.link.poll(1000) is IapCommand.Control)
            Thread.sleep(50)
            assertFalse("CMD ACK must not release data ACK wait",sending.isDone)
            peer.put(IapWire.Frame(0x40,0,outbound.seq,0,byteArrayOf()),packetVersion=2)
            sending.get(2,TimeUnit.SECONDS)
            assertTrue(peer.failures.isEmpty())
        }
    }

    /** CMD2-02: Link version 1 returns unhandled and leaves the receive PSN
     *  unchanged; this differs from version 2/3 even when the wire framing is
     *  otherwise valid. */
    @Test(timeout=6000) fun linkVersionOneCmdIsIgnoredWithoutRxUpdate(){
        Peer().use{peer->
            val response=peer.establish(sync())
            peer.put(IapWire.Frame(0x44,255,response.seq,0,byteArrayOf(0x54,1,2,3)))
            assertNull(peer.link.poll(100))
            peer.put(IapWire.Frame(0x40,255,response.seq,10,IapWire.csm(0x5703)))
            val ack=IapWire.read(peer.carInput)
            assertEquals(255,ack.ack)
            assertEquals(0x5703,peer.link.poll(1000)?.id)
            assertTrue(peer.failures.isEmpty())
        }
    }

    @Test(timeout=6000) fun outOfOrderAcrossWrapUsesEakThenDeliversOnceInOrder(){
        Peer().use{peer->
            val response=peer.establish(sync())
            peer.put(IapWire.Frame(0x40,0,response.seq,10,IapWire.csm(0x4301)))
            val eak=IapWire.read(peer.carInput)
            assertEquals(0x60,eak.flags)
            assertEquals(254,eak.ack)
            assertArrayEquals(byteArrayOf(0),eak.payload)
            peer.put(IapWire.Frame(0x40,255,response.seq,10,IapWire.csm(0x5703)))
            assertEquals(0,IapWire.read(peer.carInput).ack)
            assertEquals(0x5703,peer.link.poll(1000)?.id)
            assertEquals(0x4301,peer.link.poll(1000)?.id)
            peer.put(IapWire.Frame(0x40,255,response.seq,10,IapWire.csm(0x5703)))
            assertEquals(0,IapWire.read(peer.carInput).ack)
            assertNull(peer.link.poll(100))
            assertTrue(peer.failures.isEmpty())
        }
    }

    /** 24A446 REL-02: below maxCumACK the receiver waits for ack_ms instead of
     * emitting a pure ACK for every ordered data frame. */
    @Test(timeout=6000) fun delayedCumulativeAckIsSentWhenThresholdIsNotReached(){
        Peer(localAckMs=120,localMaxAck=3).use{peer->
            val response=peer.establish(sync(window=5,maxAck=3))
            peer.put(IapWire.Frame(0x40,255,response.seq,10,IapWire.csm(0x5703)))
            val pending=peer.executor.submit<IapWire.Frame>{IapWire.read(peer.carInput)}
            assertThrows(java.util.concurrent.TimeoutException::class.java){
                pending.get(40,TimeUnit.MILLISECONDS)
            }
            val ack=pending.get(1,TimeUnit.SECONDS)
            assertEquals(0x40,ack.flags)
            assertEquals(255,ack.ack)
            assertEquals(0,ack.payload.size)
        }
    }

    /** Reaching maxCumACK sends once immediately and cancels the pending timer. */
    @Test(timeout=6000) fun cumulativeAckThresholdSendsImmediatelyAndCancelsTimer(){
        Peer(localAckMs=150,localMaxAck=3).use{peer->
            val response=peer.establish(sync(window=5,maxAck=3))
            peer.put(IapWire.Frame(0x40,255,response.seq,10,IapWire.csm(0x5703)))
            peer.put(IapWire.Frame(0x40,0,response.seq,10,IapWire.csm(0x5703)))
            peer.put(IapWire.Frame(0x40,1,response.seq,10,IapWire.csm(0x5703)))
            val ack=peer.executor.submit<IapWire.Frame>{IapWire.read(peer.carInput)}.get(1,TimeUnit.SECONDS)
            assertEquals(1,ack.ack)
            val noExtra=peer.executor.submit<IapWire.Frame>{IapWire.read(peer.carInput)}
            assertThrows(java.util.concurrent.TimeoutException::class.java){
                noExtra.get(250,TimeUnit.MILLISECONDS)
            }
        }
    }

    @Test(timeout=6000) fun smallMtuStillEmitsOneBoundedEak(){
        Peer(localTx=32,localRx=32,localWindow=32).use{peer->
            val response=peer.establish(sync(maxPacket=32,window=5))
            peer.put(IapWire.Frame(0x40,0,response.seq,10,IapWire.csm(0x4301)))
            val eak=IapWire.read(peer.carInput)
            assertEquals(0x60,eak.flags)
            assertTrue(eak.payload.size<=32-8-2)
            assertThrows(java.util.concurrent.TimeoutException::class.java){
                peer.executor.submit<IapWire.Frame>{IapWire.read(peer.carInput)}.get(100,TimeUnit.MILLISECONDS)
            }
        }
    }

    @Test(timeout=6000) fun eakRetransmitsTheRequestedOutstandingFrameWithoutWaitingForTimeout(){
        Peer().use{peer->
            peer.establish(sync(retryMs=10000))
            val sending=peer.executor.submit{peer.link.send(0xaa00)}
            val first=IapWire.read(peer.carInput)
            val eakAck=(first.seq-2) and 255
            peer.put(IapWire.Frame(0x60,254,eakAck,0,byteArrayOf(((first.seq+1) and 255).toByte())))
            val retransmission=peer.executor.submit<IapWire.Frame>{IapWire.read(peer.carInput)}.get(2,TimeUnit.SECONDS)
            assertEquals(first.seq,retransmission.seq)
            assertArrayEquals(first.payload,retransmission.payload)
            peer.put(IapWire.Frame(0x40,254,retransmission.seq,0,byteArrayOf()))
            sending.get(2,TimeUnit.SECONDS)
            assertTrue(peer.failures.isEmpty())
        }
    }

    /** 24A446 REL-01/08: one CSM may occupy several TX slots; a cumulative ACK releases
     * the acknowledged prefix and immediately pumps the newly available slots. */
    @Test(timeout=6000) fun cumulativeAckReleasesMultipleSlotsAndPumpsTheNextFragments(){
        Peer().use{peer->
            val response=peer.establish(sync(maxPacket=24,window=3,retryMs=10000,maxAck=3))
            val csm=IapWire.csm(0xaa00,listOf(0 to ByteArray(100){0x41}))
            val expectedChunks=(csm.size+13)/14
            val sending=peer.executor.submit{peer.link.sendRaw(csm)}
            val first=IapWire.read(peer.carInput)
            val second=IapWire.read(peer.carInput)
            val third=IapWire.read(peer.carInput)
            assertEquals((first.seq+1) and 255,second.seq)
            assertEquals((second.seq+1) and 255,third.seq)
            peer.put(IapWire.Frame(0x40,254,second.seq,0,byteArrayOf()))
            val fourth=IapWire.read(peer.carInput)
            val fifth=IapWire.read(peer.carInput)
            assertEquals((third.seq+1) and 255,fourth.seq)
            assertEquals((fourth.seq+1) and 255,fifth.seq)
            peer.put(IapWire.Frame(0x40,254,fifth.seq,0,byteArrayOf()))
            // Drain and cumulatively acknowledge the remaining fragments.
            repeat(expectedChunks-5){
                val next=IapWire.read(peer.carInput)
                val last=next
                peer.put(IapWire.Frame(0x40,254,last.seq,0,byteArrayOf()))
            }
            sending.get(2,TimeUnit.SECONDS)
            assertTrue(peer.failures.isEmpty())
        }
    }

    /** EAK body lists received PSNs, so a multi-slot gap must select the missing slot while
     * preserving the original PSN and leaving the other in-flight frames untouched. */
    @Test(timeout=6000) fun eakSelectsOneMissingSlotFromAMultiFrameWindow(){
        Peer().use{peer->
            val response=peer.establish(sync(maxPacket=24,window=3,retryMs=10000,maxAck=3))
            val csm=IapWire.csm(0xaa00,listOf(0 to ByteArray(100){0x52}))
            val expectedChunks=(csm.size+13)/14
            val sending=peer.executor.submit{peer.link.sendRaw(csm)}
            val first=IapWire.read(peer.carInput)
            val second=IapWire.read(peer.carInput)
            val third=IapWire.read(peer.carInput)
            // EAK ACKs the first slot cumulatively while its body says that the third
            // slot arrived; the second slot is therefore the only missing PSN.
            peer.put(IapWire.Frame(0x60,254,first.seq,0,byteArrayOf(third.seq.toByte())))
            val retransmission=IapWire.read(peer.carInput)
            assertEquals(second.seq,retransmission.seq)
            assertArrayEquals(second.payload,retransmission.payload)
            peer.put(IapWire.Frame(0x40,254,third.seq,0,byteArrayOf()))
            repeat(expectedChunks-3){
                val next=IapWire.read(peer.carInput)
                val last=next
                peer.put(IapWire.Frame(0x40,254,last.seq,0,byteArrayOf()))
            }
            sending.get(2,TimeUnit.SECONDS)
            assertTrue(peer.failures.isEmpty())
        }
    }

    /** 24A446 EAK action: an EAK body is a received-PSN list and cannot be empty. */
    @Test(timeout=6000) fun emptyEakIsRejectedAsMalformedControl(){
        Peer().use{peer->
            val response=peer.establish(sync())
            peer.put(IapWire.Frame(0x60,254,response.seq,0,byteArrayOf()))
            assertTrue(peer.failureSignal.await(1,TimeUnit.SECONDS))
            assertTrue(peer.failures.single().message!!.contains("Malformed EAK"))
        }
    }

    @Test(timeout=6000) fun repeatedEakSharesTheBoundedRetryBudgetAndTerminatesTheLink(){
        Peer().use{peer->
            peer.establish(sync(retries=1,retryMs=10000))
            val sending=peer.executor.submit{peer.link.send(0xaa00)}
            val first=IapWire.read(peer.carInput)
            repeat(2){attempt->
                val eakAck=(first.seq-2) and 255
                peer.put(IapWire.Frame(0x60,254,eakAck,0,byteArrayOf(((first.seq+1) and 255).toByte())))
                if(attempt==0)assertEquals(first.seq,IapWire.read(peer.carInput).seq)
            }
            val failure=assertThrows(ExecutionException::class.java){sending.get(2,TimeUnit.SECONDS)}
            assertTrue(failure.cause is java.net.SocketTimeoutException)
            assertNull(peer.link.negotiatedControlSession)
            assertEquals(1,peer.failures.size)
        }
    }

    @Test(timeout=6000) fun runtimeDetectionClearsFragmentsAndChangesTheControlGeneration(){
        Peer().use{peer->
            val first=peer.establish(sync())
            val fragment=IapWire.csm(0xaa01,listOf(0 to ByteArray(60){0x69})).copyOfRange(0,20)
            peer.put(IapWire.Frame(0x40,255,first.seq,10,fragment))
            assertEquals(255,IapWire.read(peer.carInput).ack)
            assertTrue(peer.phoneInput.offer(IapWire.detection))
            assertArrayEquals(IapWire.detection,IapWire.exact(peer.carInput,6))
            assertNull(peer.link.negotiatedControlSession)
            peer.put(IapWire.Frame(0x80,41,0,0,sync(id=1,version=2)))
            val second=IapWire.read(peer.carInput)
            peer.put(IapWire.Frame(0x40,41,second.seq,0,byteArrayOf()))
            peer.put(IapWire.Frame(0x40,42,second.seq,1,IapWire.csm(0x5703)))
            assertEquals(42,IapWire.read(peer.carInput).ack)
            assertEquals(2,peer.link.generation)
            assertEquals(IapLink.ControlSession(1,0,2),peer.link.negotiatedControlSession)
            assertTrue(peer.link.await(0x5703,2).isEmpty())
            assertNull(peer.link.poll(0))
            assertTrue(peer.failures.isEmpty())
        }
    }

    /** Firmware CSM parser drops a bad candidate byte and keeps scanning; a
     * following valid CSM must not be converted into a link failure. */
    @Test(timeout=6000) fun csmMarkerMismatchResynchronizesWithoutClosingLink(){
        Peer().use{peer->
            val response=peer.establish(sync())
            val valid=IapWire.csm(0x5703)
            val payload=byteArrayOf(0x12,0x40,0x41,0x33)+valid
            peer.put(IapWire.Frame(0x40,255,response.seq,10,payload))
            assertEquals(255,IapWire.read(peer.carInput).ack)
            assertEquals(0x5703,peer.link.poll(1000)?.id)
            assertTrue(peer.failures.isEmpty())
            assertNotNull(peer.link.negotiatedControlSession)
        }
    }

    @Test(timeout=6000) fun fragmentedAuthenticationAndCredentialsNeverAppearInFrameLogs(){
        Peer().use{peer->
            val response=peer.establish(sync(maxPacket=64))
            val secret=ByteArray(120){listOf(0xde,0xad,0xbe,0xef)[it%4].toByte()}
            val encodedSecret="deadbeefdeadbeef"
            var receivedSeq=254
            for(id in listOf(0xaa01,0xaa02,0xaa03,0x5703,0x4301)){
                val inbound=IapWire.csm(id,listOf(0 to secret))
                for(chunk in inbound.asList().chunked(25)){
                    receivedSeq=(receivedSeq+1) and 255
                    peer.put(IapWire.Frame(0x40,receivedSeq,response.seq,10,chunk.toByteArray()))
                    assertEquals(receivedSeq,IapWire.read(peer.carInput).ack)
                }
                assertArrayEquals(secret,peer.link.await(id,2)[0])
                val sending=peer.executor.submit{peer.link.send(id,listOf(0 to secret))}
                var remaining=inbound.size
                while(remaining>0){
                    val frame=IapWire.read(peer.carInput)
                    remaining-=frame.payload.size
                    peer.put(IapWire.Frame(0x40,receivedSeq,frame.seq,0,byteArrayOf()))
                }
                sending.get(2,TimeUnit.SECONDS)
            }
            assertFalse(peer.logs.any{it.contains(encodedSecret)})
            assertTrue(peer.logs.any{it.contains("csm=aa01")})
            assertTrue(peer.failures.isEmpty())
        }
    }

    @Test(timeout=6000) fun callerCannotSendAuthenticationForAnOldControlGeneration(){
        Peer().use{peer->
            peer.establish(sync())
            assertThrows(IllegalStateException::class.java){peer.link.send(0xaa05,expectedGeneration=0)}
            val sending=peer.executor.submit{peer.link.send(0xaa00,expectedGeneration=1)}
            val frame=IapWire.read(peer.carInput)
            assertEquals(0xaa00,IapWire.u16(frame.payload,4))
            peer.put(IapWire.Frame(0x40,254,frame.seq,0,byteArrayOf()))
            sending.get(2,TimeUnit.SECONDS)
        }
    }

    @Test(timeout=6000) fun maximumLengthCsmAndFollowingMessageCanShareTheFinalLinkPayload(){
        Peer().use{peer->
            val response=peer.establish(sync())
            val certificate=ByteArray(65525){(it%251).toByte()}
            val first=IapWire.csm(0xaa01,listOf(0 to certificate))
            assertEquals(65535,first.size)
            val stream=first+IapWire.csm(0x5703)
            var sequence=254
            for(chunk in stream.asList().chunked(2038)){
                sequence=(sequence+1) and 255
                peer.put(IapWire.Frame(0x40,sequence,response.seq,10,chunk.toByteArray()))
                assertEquals(sequence,IapWire.read(peer.carInput).ack)
            }
            assertArrayEquals(certificate,peer.link.await(0xaa01,2)[0])
            assertTrue(peer.link.await(0x5703,2).isEmpty())
            assertTrue(peer.failures.isEmpty())
        }
    }

    @Test(timeout=6000) fun reSynchronizationCancelsTheOutstandingCsmBeforeItsNextChunk(){
        Peer().use{peer->
            peer.establish(sync(maxPacket=24,retryMs=10000))
            val sending=peer.executor.submit{peer.link.send(0xaa03,listOf(0 to ByteArray(100){0x69}),expectedGeneration=1)}
            val fragment=IapWire.read(peer.carInput)
            assertEquals(10,fragment.session)
            // Start a new generation before acknowledging the old fragmented CSM.
            peer.put(IapWire.Frame(0x80,41,0,0,sync(id=1,version=2)))
            var response=IapWire.read(peer.carInput)
            // With a peer window greater than one, earlier CSM fragments may already have
            // been committed to the socket before the new Detection/SYN is observed. They
            // belong to the old generation and must be drained before inspecting the new SYNACK.
            while(response.flags!=0xc0){
                assertEquals(0x40,response.flags)
                response=IapWire.read(peer.carInput)
            }
            assertEquals(0xc0,response.flags)
            peer.put(IapWire.Frame(0x40,41,response.seq,0,byteArrayOf()))
            assertThrows(ExecutionException::class.java){sending.get(2,TimeUnit.SECONDS)}
            peer.put(IapWire.Frame(0x40,42,response.seq,1,IapWire.csm(0x5703)))
            // No certificate/signature tail may have appeared between SYN-ACK and this ACK.
            val ack=IapWire.read(peer.carInput)
            assertEquals(0x40,ack.flags)
            assertTrue(ack.payload.isEmpty())
            assertTrue(peer.link.await(0x5703,2).isEmpty())
            assertEquals(2,peer.link.generation)
            assertTrue(peer.failures.isEmpty())
        }
    }

    /** 指南 §3.3：打开后 `firstByteTimeoutMs` 内一个字节都没收到就发一次 Detect；
     *  再过 `detectTimeoutMs` 仍没有对端 SYN 就判 NO_SYN 拆链。两段时限在这里压到 80/120 ms，
     *  只验证顺序与终态，不验证真实秒数。 */
    @Test(timeout=6000) fun silentPeerGetsOneRstThenDetectAndThenNoSynFailure(){
        val failures=CopyOnWriteArrayList<Throwable>()
        val signal=java.util.concurrent.CountDownLatch(1)
        val phoneInput=BoundedByteInput()
        val carInput=BoundedByteInput()
        val link=IapLink(phoneInput,object:OutputStream(){
            override fun write(b:Int){check(carInput.offer(byteArrayOf(b.toByte())))}
            override fun write(b:ByteArray,off:Int,len:Int){check(carInput.offer(b.copyOfRange(off,off+len)))}
        },{},{failures.add(it);signal.countDown()},firstByteTimeoutMs=80,detectTimeoutMs=120)
        try{
            val worker=Executors.newSingleThreadExecutor().submit{runCatching{link.start(allowDirectSyn=true)}}
            // 静默时先发一条 RST 把车机的旧链路状态清掉，再发 Detect 探它：
            // 真车 2026-11-04 那一次只发 Detect，车机 7 秒一声不响，直接判了 NO_SYN。
            val rst=ByteArray(9); var at=0
            while(at<9){val n=carInput.read(rst,at,9-at);check(n>0);at+=n}
            assertEquals("RST 必须先到",(-1).toByte(),rst[0])
            assertEquals(0x5a.toByte(),rst[1])
            assertEquals("包长字段",9,rst[3].toInt() and 0xff)
            assertEquals("flags 必须是 0x10 (RST)",0x10,rst[4].toInt() and 0xff)
            val detect=ByteArray(6); at=0
            while(at<6){val n=carInput.read(detect,at,6-at);check(n>0);at+=n}
            assertArrayEquals(IapWire.detection,detect)
            assertTrue("NO_SYN 兜底必须触发",signal.await(3,TimeUnit.SECONDS))
            val failure=failures.single()
            assertTrue(failure is java.net.SocketTimeoutException)
            assertTrue((failure.message?:"").contains("NO_SYN"))
            worker.get(3,TimeUnit.SECONDS)
        }finally{link.close();phoneInput.close();carInput.close()}
    }

    /** 专用承载（iAP tunnel）不该用 Detect 探路：`detectOnIdle=false` 时一个字节都不写，
     *  只保留 NO_SYN 兜底，把“永久阻塞”换成可诊断的失败。 */
    @Test(timeout=6000) fun detectOnIdleFalseFailsWithoutWritingAnything(){
        val failures=CopyOnWriteArrayList<Throwable>()
        val signal=java.util.concurrent.CountDownLatch(1)
        val written=java.util.concurrent.atomic.AtomicInteger()
        val phoneInput=BoundedByteInput()
        val link=IapLink(phoneInput,object:OutputStream(){
            override fun write(b:Int){written.incrementAndGet()}
            override fun write(b:ByteArray,off:Int,len:Int){written.addAndGet(len)}
        },{},{failures.add(it);signal.countDown()},firstByteTimeoutMs=80,detectTimeoutMs=120)
        try{
            val worker=Executors.newSingleThreadExecutor().submit{
                runCatching{link.start(allowDirectSyn=true,detectOnIdle=false)}
            }
            assertTrue("NO_SYN 兜底必须触发",signal.await(3,TimeUnit.SECONDS))
            assertEquals("detectOnIdle=false 时不得写出任何字节",0,written.get())
            assertTrue(failures.single() is java.net.SocketTimeoutException)
            worker.get(3,TimeUnit.SECONDS)
        }finally{link.close();phoneInput.close()}
    }
}
