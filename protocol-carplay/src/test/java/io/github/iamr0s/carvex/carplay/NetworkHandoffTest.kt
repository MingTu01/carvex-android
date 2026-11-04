package io.github.iamr0s.carvex.carplay

import io.github.iamr0s.carvex.carplay.core.*
import io.github.iamr0s.carvex.sessioncore.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** Runs the same coordinator and ordering pipeline used by CarPlayRunner. */
class NetworkHandoffTest {
    private class Fixture(val declaredStart:Boolean=true) {
        var current=true
        val logs=mutableListOf<String>()
        val handoff=IapNetworkHandoff(7,9,true,{current},logs::add)
        val identity=AcceptedIdentification(7,9,IdentifyInfo(
            accessorySends=listOf(MessageId(0x57,3))+if(declaredStart)listOf(MessageId(0x43,1))else emptyList(),
            networkHandoff=NetworkHandoffCapabilities(true,declaredStart)))
        val initial=WifiBootstrap("synthetic","password1",2,null,
            authentication=BootstrapAuthentication(7,9,2,false,true),identification=identity)
        init{handoff.acceptBootstrap(initial)}
        fun start(address:String="192.0.2.1",password:String="password1",security:Int=3)=IapFields(listOf(
            1 to IapWire.fields(listOf(0 to IapWire.cstring("synthetic"),1 to IapWire.cstring(password),
                3 to IapWire.cstring(address),4 to byteArrayOf(security.toByte()))),
            2 to IapWire.be32(7654),3 to IapWire.cstring("synthetic-peer")))
        fun late(fields:IapFields=start())=handoff.handle(IapCommand.Fields(0x4301,fields),7,9)
        fun discovery()=initial.resolved("192.0.2.2",8765,"discovered-peer",null,ReceiverMetadata(null,null,0UL))
    }

    @Test fun bootstrapRequiresCurrentAuthenticationAndIdentification() {
        val f=Fixture()
        assertThrows(IllegalArgumentException::class.java){
            IapNetworkHandoff(7,9,true,{true}).acceptBootstrap(f.initial.copy(authentication=null))
        }
        for(value in listOf(f.initial.copy(authentication=BootstrapAuthentication(7,10,2,false,true)),
            f.initial.copy(authentication=BootstrapAuthentication(7,9,2,false,false)),
            f.initial.copy(identification=AcceptedIdentification(8,9,IdentifyInfo())))) {
            val state=IapNetworkHandoff(7,9,true,{true})
            assertThrows(IllegalStateException::class.java){state.acceptBootstrap(value)}
        }
        assertThrows(IllegalStateException::class.java){f.handoff.acceptBootstrap(f.initial)}
    }

    @Test fun productionModeDoesNotPromoteExperimentalSignature() {
        val f=Fixture()
        assertThrows(IllegalStateException::class.java){
            IapNetworkHandoff(7,9,false,{true}).acceptBootstrap(f.initial)
        }
    }

    @Test fun retiredIdentificationCannotAcceptNewEventsOrCommit() {
        val f=Fixture();f.identity.invalidate()
        assertThrows(IllegalStateException::class.java){f.late()}
        assertThrows(IllegalStateException::class.java){f.handoff.commit(f.discovery())}
        assertThrows(IllegalStateException::class.java){
            IapNetworkHandoff(7,9,true,{true}).acceptBootstrap(f.initial)
        }
    }

    @Test fun networkEventsCannotPrecedeAcceptanceOrCrossEpochs() {
        val f=Fixture();val command=IapCommand.Fields(0x4301,f.start())
        assertThrows(IllegalArgumentException::class.java){IapNetworkHandoff(7,9,true,{true}).handle(command,7,9)}
        assertThrows(IllegalStateException::class.java){f.handoff.handle(command,7,10)}
        assertThrows(IllegalStateException::class.java){f.handoff.handle(command,8,9)}
        assertNull(f.handoff.configuration()!!.endpoint)
    }

    @Test fun unadvertisedLateStartCannotCreateEndpoint() {
        val f=Fixture(false)
        assertTrue(f.late());assertNull(f.handoff.configuration()!!.endpoint)
        assertFalse(f.handoff.canAwaitStart())
        assertTrue(f.logs.any{it.contains("unadvertised")})
    }

    @Test fun lateStartPreservesAuthAndUsesExistingAndroidSecurityMapping() {
        val f=Fixture();f.late()
        val result=f.handoff.configuration()!!
        assertEquals(2,result.security) // Never reconfigure the already selected lease.
        assertEquals("192.0.2.1",result.address);assertEquals(7654,result.port)
        assertSame(f.initial.authentication,result.authentication)
        assertSame(f.identity,result.identification)
        assertEquals(result.endpoint,result.startSession)
    }

    @Test fun conflictsCannotRedirectLeaseAndDoNotLogCredentials() {
        val f=Fixture();f.late(f.start(password="different-secret"));f.late(f.start(security=4))
        f.handoff.announcement(WifiAnnouncement("other-network","password1",2,null))
        assertNull(f.handoff.configuration()!!.endpoint)
        assertFalse(f.logs.joinToString().contains("different-secret"))
        assertFalse(f.logs.joinToString().contains("other-network"))
        f.late();assertNotNull(f.handoff.configuration()!!.endpoint)
    }

    @Test fun malformedOrPartialStartDoesNotEraseExistingEndpoint() {
        val f=Fixture();f.late();val first=f.handoff.configuration()!!.endpoint
        f.late(IapFields(f.start().ordered+listOf(8 to byteArrayOf(2))))
        f.late(IapFields(listOf(1 to IapWire.fields(listOf(0 to IapWire.cstring("synthetic"))))))
        f.handoff.announcement(WifiAnnouncement("synthetic","password1",2,null))
        assertSame(first,f.handoff.configuration()!!.endpoint)
    }

    @Test fun completeSameMessageCredentialsAllowNetworkOnlyStart() {
        val state=IapNetworkHandoff(7,9,true,{true})
        val fields=IapFields(listOf(1 to IapWire.fields(listOf(0 to IapWire.cstring("synthetic"),
            1 to IapWire.cstring("password1"),4 to byteArrayOf(3)))))
        state.startRequest(fields)
        val config=state.configuration()!!
        assertEquals(3,config.security);assertNull(config.endpoint);assertNull(config.authentication)
    }

    @Test fun partialStartDoesNotBorrowMissingCredentialsFrom5703() {
        val f=Fixture()
        val withoutPassword=IapFields(listOf(1 to IapWire.fields(listOf(0 to IapWire.cstring("synthetic"),
            3 to IapWire.cstring("192.0.2.1"),4 to byteArrayOf(3))),
            2 to IapWire.be32(7654),3 to IapWire.cstring("synthetic-peer")))
        f.late(withoutPassword)
        assertNull(f.handoff.configuration()!!.endpoint)
    }

    @Test fun commitmentFreezesEndpointAndRejectsForeignContext() {
        val f=Fixture();f.late()
        assertThrows(IllegalStateException::class.java){f.handoff.commit(f.discovery().copy(authentication=null))}
        val committed=f.handoff.commit(f.discovery()) // Current4301 wins the boundary race.
        assertEquals("192.0.2.1",committed.address)
        f.late(f.start(address="192.0.2.3"))
        assertSame(committed,f.handoff.configuration())
        assertThrows(IllegalStateException::class.java){f.handoff.commit(committed)}
    }

    @Test(timeout=5000) fun closeWakesWaiterAndNeverRevivesIt() {runBlocking {
        val f=Fixture()
        supervisorScope {
            val waiting=async(start=CoroutineStart.UNDISPATCHED){f.handoff.awaitEndpoint()}
            f.handoff.close()
            try{waiting.await();fail("closed wait succeeded")}catch(expected:IllegalStateException){}
        }
        assertThrows(IllegalStateException::class.java){f.late()}
    }}

    @Test fun currentContextGateRejectsOldCallbacksAndCommit() {
        val f=Fixture();f.current=false
        assertThrows(IllegalStateException::class.java){f.late()}
        assertThrows(IllegalStateException::class.java){f.handoff.commit(f.discovery())}
    }

    @Test(timeout=5000) fun publishPrecedesDiscoveryAndBreaksConditionalWait() {runBlocking {
        val f=Fixture();val order=mutableListOf<String>();val published=CompletableDeferred<Unit>()
        val discovered=f.discovery()
        val result=NetworkHandoffPipeline(f.handoff,
            join={order+="join";"network"},
            publish={order+="publish";published.complete(Unit);"publication"},
            discover={_,_->published.await();order+="discover";discovered}).open(f.initial)
        assertEquals(listOf("join","publish","discover"),order)
        assertEquals("publication",result.publication)
        assertEquals(8765,result.bootstrap.port)
        assertSame(f.initial.authentication,result.bootstrap.authentication)
        assertSame(f.identity,result.bootstrap.identification)
        assertSame(discovered.endpoint!!.metadata,result.bootstrap.endpoint!!.metadata)
    }}

    @Test(timeout=5000) fun late4301DuringJoinUsesOneLeaseAndSkipsDiscovery() {runBlocking {
        val f=Fixture();var joined=0;var published=0
        val result=NetworkHandoffPipeline(f.handoff,
            join={joined++;f.late();f.late();"network"},publish={published++;"publication"},
            discover={_,_->error("direct endpoint must not browse here")}).open(f.initial)
        assertEquals(1,joined);assertEquals(1,published);assertEquals(7654,result.bootstrap.port)
    }}

    @Test(timeout=5000) fun late4301CancelsOutstandingBrowse() {runBlocking {
        val f=Fixture();val browsing=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>()
        val open=async{NetworkHandoffPipeline(f.handoff,join={Unit},publish={Unit},discover={_,_->
            browsing.complete(Unit)
            try{awaitCancellation()}finally{cancelled.complete(Unit)}
        }).open(f.initial)}
        browsing.await();f.late()
        assertEquals(7654,open.await().bootstrap.port);cancelled.await()
    }}

    @Test(timeout=5000) fun resolverOwnTimeoutStillAllowsDeclaredLate4301() {runBlocking {
        val f=Fixture();val timedOut=CompletableDeferred<Unit>()
        val open=async{NetworkHandoffPipeline(f.handoff,join={Unit},publish={Unit},discover={_,_->
            try{withTimeout(20){awaitCancellation()}}finally{timedOut.complete(Unit)}
        }).open(f.initial)}
        timedOut.await();yield();assertFalse(open.isCompleted)
        f.late();assertEquals(7654,open.await().bootstrap.port)
    }}

    @Test(timeout=5000) fun ordinaryDiscoveryFailureWaitsOnlyForDeclaredRoute() {runBlocking {
        val f=Fixture();val failed=CompletableDeferred<Unit>()
        val open=async{NetworkHandoffPipeline(f.handoff,join={Unit},publish={Unit},discover={_,_->
            failed.complete(Unit);throw IOException("synthetic browse failure")
        }).open(f.initial)}
        failed.await();yield();assertFalse(open.isCompleted);f.late();open.await()
        val noStart=Fixture(false)
        try{NetworkHandoffPipeline(noStart.handoff,join={Unit},publish={Unit},discover={_,_->
            throw IOException("no declared route")}).open(noStart.initial);fail("must fail")}
        catch(expected:IOException){assertEquals("no declared route",expected.message)}
    }}

    @Test(timeout=5000) fun securityFailureNeverFallsBackToLate4301() {runBlocking {
        val f=Fixture()
        try{NetworkHandoffPipeline(f.handoff,join={Unit},publish={Unit},discover={_,_->
            throw SecurityException("key conflict")}).open(f.initial);fail("must fail")}
        catch(expected:SecurityException){assertEquals("key conflict",expected.message)}
    }}

    @Test(timeout=5000) fun resolverDeadlineWithoutDeclaredRouteIsNotCancellation() {runBlocking {
        // 真车 2026-11-03：车机自报 carPlayStartSession=false（没有 4301 可等），端点发现 15 秒
        // 到点后如果这个异常以 CancellationException 的面目上抛，上层重试循环会读成"整条链被
        // 放弃"——日志里既没有 failure 也没有 RETRY，之后再没重试过。它必须是普通超时。
        val f=Fixture(false)
        try{NetworkHandoffPipeline(f.handoff,join={Unit},publish={Unit},discover={_,_->
            withTimeout(20){awaitCancellation()}
        }).open(f.initial);fail("must fail")}
        catch(expected:SocketTimeoutException){}
        catch(unexpected:CancellationException){
            fail("a resolver's own deadline must surface as SocketTimeoutException, not cancellation")}
    }}

    @Test(timeout=5000) fun endpointDeadlineIsFailureButParentTimeoutRemainsCancellation() {runBlocking {
        val f=Fixture()
        try{NetworkHandoffPipeline(f.handoff,join={Unit},publish={Unit},discover={_,_->awaitCancellation()},
            endpointWaitMs=20).open(f.initial);fail("must time out")}
        catch(expected:SocketTimeoutException){}
        val other=Fixture()
        try{withTimeout(20){NetworkHandoffPipeline(other.handoff,join={Unit},publish={Unit},
            discover={_,_->awaitCancellation()}).open(other.initial)};fail("parent must cancel")}
        catch(expected:TimeoutCancellationException){}
    }}

    @Test(timeout=5000) fun cancellationDuringJoinPreventsPublication() {runBlocking {
        val f=Fixture();val joining=CompletableDeferred<Unit>();var publications=0
        val task=launch{NetworkHandoffPipeline(f.handoff,join={joining.complete(Unit);awaitCancellation()},
            publish={_:Unit->publications++},discover={_,_->f.discovery()}).open(f.initial)}
        joining.await();task.cancelAndJoin();assertEquals(0,publications)
    }}

    @Test(timeout=5000) fun epochChangeAfterJoinPreventsPublication() {runBlocking {
        val f=Fixture();var publications=0
        try{NetworkHandoffPipeline(f.handoff,join={f.current=false;Unit},publish={publications++},
            discover={_,_->f.discovery()}).open(f.initial);fail("stale epoch accepted")}
        catch(expected:IllegalStateException){}
        assertEquals(0,publications)
    }}

    @Test(timeout=5000) fun epochChangeDuringDiscoveryPreventsCommit() {runBlocking {
        val f=Fixture()
        try{NetworkHandoffPipeline(f.handoff,join={Unit},publish={Unit},discover={_,_->
            f.current=false;f.discovery()}).open(f.initial);fail("stale endpoint committed")}
        catch(expected:IllegalStateException){}
    }}
}
