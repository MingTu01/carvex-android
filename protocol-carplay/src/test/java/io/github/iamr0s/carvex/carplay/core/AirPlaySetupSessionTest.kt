package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AirPlaySetupSessionTest {
    private val path="rtsp://192.0.2.1/123"
    private data class Reply(val body:Any?=emptyMap<String,Any?>(),val status:Int=200,val raw:ByteArray?=null)
    private fun wire(reply:Reply,index:Int):ByteArray {
        val body=reply.raw?:Plist.encode(reply.body)
        return "RTSP/1.0 ${reply.status} Synthetic\r\nCSeq: $index\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray()+body
    }
    private inner class Peer(vararg replies:Reply,consume:(Map<String,Any?>)->Map<String,Any?> = {it.toMap()},current:()->Unit={}) {
        val output=ByteArrayOutputStream()
        val client=RtspClient(ByteArrayInputStream(replies.mapIndexed{i,r->wire(r,i+1)}.fold(byteArrayOf()){a,b->a+b}),output)
        val session=AirPlaySetupSession(client,path,current,consumeInfo=consume)
        fun requests():List<RtspMessage> {
            val input=ByteArrayInputStream(output.toByteArray())
            return buildList{while(input.available()>0)add(RtspWire.read(input))}
        }
    }
    private fun screen(port:Any?=7000,type:Any?=110)=mapOf("streams" to listOf(mapOf("type" to type,"dataPort" to port)))

    @Test fun embeddedInfoSkipsGetAndFeedsTheSameConsumerIncludingAnEmptyDictionary(){
        for(info in listOf(emptyMap<String,Any?>(),mapOf("displays" to emptyList<Any>(),"sourceVersion" to "synthetic"))){
            var calls=0
            val peer=Peer(Reply(mapOf("info" to info)),consume={calls++;it.toMap()})
            val setup=peer.session.control(mapOf("updateSessionRequest" to true))
            assertNull(setup.eventPort)
            assertEquals(info,peer.session.info())
            assertEquals(info,peer.session.info())
            assertEquals(1,calls)
            val request=peer.requests().single()
            assertEquals("SETUP $path RTSP/1.0",request.first)
            assertEquals(false,Plist.dictionary(request.body)["updateSessionRequest"])
            assertEquals("1",request.headers["x-apple-protocolversion"])
        }
    }

    @Test fun missingOrWrongTypeEmbeddedInfoFallsBackToGet(){
        for(value in listOf(null,1L,"bad",emptyList<Any>(),byteArrayOf(1))){
            val peer=Peer(Reply(mapOf("info" to value)),Reply(mapOf("model" to "synthetic")))
            peer.session.control(emptyMap())
            assertEquals("synthetic",peer.session.info()["model"])
            assertEquals(listOf("SETUP $path RTSP/1.0","GET /info RTSP/1.0"),peer.requests().map{it.first})
        }
    }

    @Test fun failedEmbeddedConsumerDoesNotMarkInfoFetched(){
        var calls=0
        val peer=Peer(Reply(mapOf("info" to mapOf("name" to "first"))),Reply(mapOf("name" to "second")),
            consume={calls++;if(calls==1)error("synthetic consumer failure");it})
        peer.session.control(emptyMap())
        assertEquals("second",peer.session.info()["name"])
        assertEquals(2,calls)
        assertEquals(2,peer.requests().size)
    }

    @Test fun failedIndependentInfoConsumerIsNotCached(){
        var calls=0
        val peer=Peer(Reply(),Reply(mapOf("name" to "first")),Reply(mapOf("name" to "second")),
            consume={calls++;if(calls==1)error("synthetic failure");it})
        peer.session.control(emptyMap())
        assertThrows(IllegalStateException::class.java){peer.session.info()}
        assertEquals("second",peer.session.info()["name"])
        assertEquals(3,peer.requests().size)
    }

    @Test fun eventAndKeepAlivePortsAreIndependentAndOptional(){
        for(body in listOf(emptyMap<String,Any?>(),mapOf("eventPort" to 0,"keepAlivePort" to 0),
            mapOf("eventPort" to emptyList<Any>(),"keepAlivePort" to "bad"))){
            val peer=Peer(Reply(body))
            val result=peer.session.control(emptyMap())
            assertNull(result.eventPort);assertNull(result.keepAlivePort)
        }
        val result=Peer(Reply(mapOf("eventPort" to 0,"keepAlivePort" to 65535))).session.control(emptyMap())
        assertNull(result.eventPort);assertEquals(65535,result.keepAlivePort)
    }

    @Test fun portConversionDoesNotWrapLargeOrNegativeIntegers(){
        for(key in listOf("eventPort","keepAlivePort"))for(value in listOf(-1L,65536L,4294974296L,Long.MAX_VALUE)){
            val peer=Peer(Reply(mapOf(key to value)))
            assertThrows(IllegalArgumentException::class.java){peer.session.control(emptyMap())}
            assertEquals(1,peer.requests().size)
        }
    }

    @Test fun commonNativePortConversionsRetainValueAndErrorSeparately(){
        for(value in listOf(7000L,7000.9,"7000suffix",byteArrayOf(0x1b,0x58))){
            assertEquals(7000,Peer(Reply(mapOf("eventPort" to value))).session.control(emptyMap()).eventPort)
        }
        assertNull(Peer(Reply(mapOf("eventPort" to Double.POSITIVE_INFINITY))).session.control(emptyMap()).eventPort)
    }

    @Test fun enabledFeaturesMissingIsAllowedButPresentNonArrayIsNot(){
        assertNull(Peer(Reply()).session.control(emptyMap()).enabledFeatures)
        assertEquals(listOf(1L,"synthetic"),Peer(Reply(mapOf("enabledFeatures" to listOf(1,"synthetic")))).session.control(emptyMap()).enabledFeatures)
        for(value in listOf(null,1,"bad",emptyMap<String,Any>())){
            assertThrows(IllegalArgumentException::class.java){Peer(Reply(mapOf("enabledFeatures" to value))).session.control(emptyMap())}
        }
    }

    @Test fun nonzeroSkipRecordSuppressesRecordAndIsStickyAcrossUpdate(){
        for(value in listOf(1,-1,2,true,"yes",byteArrayOf(1))){
            val peer=Peer(Reply(mapOf("skipRecord" to value)),Reply(mapOf("skipRecord" to 0)),Reply())
            peer.session.control(emptyMap())
            peer.session.control(emptyMap(),update=true)
            peer.session.control(emptyMap(),update=true)
            assertFalse(peer.session.start());assertFalse(peer.session.start())
            val requests=peer.requests()
            assertEquals(3,requests.size)
            assertTrue(requests.all{it.first=="SETUP $path RTSP/1.0"})
            assertEquals(true,Plist.dictionary(requests[1].body)["updateSessionRequest"])
        }
    }

    @Test fun zeroMissingOrInvalidSkipRecordSendsOneBodylessRecord(){
        for(value in listOf(null,0,false,"false","bad",emptyList<Any>())){
            val peer=Peer(Reply(mapOf("skipRecord" to value)),Reply(raw=byteArrayOf()))
            peer.session.control(emptyMap())
            assertTrue(peer.session.start());assertFalse(peer.session.start())
            val request=peer.requests().last()
            assertEquals("RECORD $path RTSP/1.0",request.first)
            assertTrue(request.body.isEmpty())
            assertFalse(request.headers.containsKey("x-apple-protocolversion"))
        }
    }

    @Test fun recordFailureDoesNotCommitStartedAndDoesNotAutomaticallyRetry(){
        val peer=Peer(Reply(),Reply(status=500),Reply(raw=byteArrayOf()))
        peer.session.control(emptyMap())
        assertEquals(500,assertThrows(RtspClient.StatusException::class.java){peer.session.start()}.status)
        assertEquals(2,peer.requests().size)
        assertTrue(peer.session.start())
        assertEquals(3,peer.requests().size)
    }

    @Test fun recordAgainSendsASecondBodylessRecordAfterStartCommitted(){
        val peer=Peer(Reply(),Reply(raw=byteArrayOf()),Reply(raw=byteArrayOf()))
        peer.session.control(emptyMap())
        assertTrue(peer.session.start())
        assertTrue(peer.session.recordAgain("audio main ready"))
        val records=peer.requests().filter{it.first.startsWith("RECORD")}
        assertEquals(2,records.size)
        assertTrue(records.all{it.body.isEmpty()})
        assertEquals("RECORD $path RTSP/1.0",records[1].first)
        assertFalse(records[1].headers.containsKey("x-apple-protocolversion"))
    }

    @Test fun recordAgainIsSuppressedBySkipRecordWithoutWritingOnTheWire(){
        val peer=Peer(Reply(mapOf("skipRecord" to true)))
        peer.session.control(emptyMap())
        assertFalse(peer.session.recordAgain("audio main ready"))
        assertEquals(1,peer.requests().size)
    }

    @Test fun recordAgainReportsRefusalInsteadOfThrowingAndStaysRetryable(){
        val peer=Peer(Reply(),Reply(status=500),Reply(status=400),Reply(raw=byteArrayOf()))
        peer.session.control(emptyMap())
        assertFalse(peer.session.recordAgain("first"))
        assertEquals(2,peer.requests().size)
        assertFalse(peer.session.recordAgain("second"))
        assertEquals(3,peer.requests().size)
        assertTrue(peer.session.recordAgain("third"))
        assertEquals(4,peer.requests().size)
    }

    @Test fun recordAgainOnClosedSessionThrowsBeforeWriting(){
        val peer=Peer()
        peer.session.close()
        assertThrows(IllegalStateException::class.java){peer.session.recordAgain("closed")}
        assertTrue(peer.requests().isEmpty())
    }

    @Test fun skipAndInfoDoNotLeakToANewControlSession(){
        val first=Peer(Reply(mapOf("skipRecord" to true,"info" to emptyMap<String,Any?>())))
        first.session.control(emptyMap());assertFalse(first.session.start());first.session.close()
        val next=Peer(Reply(),Reply(mapOf("name" to "new")),Reply(raw=byteArrayOf()))
        next.session.control(emptyMap());assertEquals("new",next.session.info()["name"]);assertTrue(next.session.start())
        assertEquals(3,next.requests().size)
    }

    @Test fun closedSessionWritesNothingAndCannotReturnCachedInfo(){
        val peer=Peer(Reply(mapOf("info" to emptyMap<String,Any?>())))
        peer.session.control(emptyMap());peer.session.close()
        assertThrows(IllegalStateException::class.java){peer.session.info()}
        assertThrows(IllegalStateException::class.java){peer.session.start()}
        assertThrows(IllegalStateException::class.java){peer.session.control(emptyMap())}
        assertEquals(1,peer.requests().size)
    }

    @Test fun staleBeforeSendOrAfterReplyDoesNotPublishInfo(){
        var checks=0
        val peer=Peer(Reply(mapOf("info" to emptyMap<String,Any?>())),current={checks++;check(checks<2)})
        assertThrows(IllegalStateException::class.java){peer.session.control(emptyMap())}
        assertEquals(1,peer.requests().size)
        val stale=Peer(Reply(),current={error("stale")})
        assertThrows(IllegalStateException::class.java){stale.session.control(emptyMap())}
        assertTrue(stale.requests().isEmpty())
    }

    @Test fun controlRefusalDoesNotSendInventedSetupUrlOrGetInfo(){
        val peer=Peer(Reply(status=400))
        assertEquals(400,assertThrows(RtspClient.StatusException::class.java){peer.session.control(emptyMap())}.status)
        assertEquals("SETUP $path RTSP/1.0",peer.requests().single().first)
    }

    @Test fun malformedRootIsNotSuccessfulSetup(){
        for(reply in listOf(Reply(body=emptyList<Any>()),Reply(raw=byteArrayOf(1,2,3)))){
            val peer=Peer(reply)
            assertThrows(Exception::class.java){peer.session.control(emptyMap())}
            assertEquals(1,peer.requests().size)
        }
    }

    @Test fun screenReplyRequiresExactlyOneDictionaryOfRequestedType(){
        val valid=mapOf("type" to 110,"dataPort" to 7000)
        for(body in listOf(emptyMap(),mapOf("streams" to emptyList<Any>()),mapOf("streams" to listOf(valid,valid)),
            mapOf("streams" to listOf("bad",valid)),mapOf("streams" to listOf(110)),screen(type=111),screen(type=4294967406L))){
            assertThrows(IllegalArgumentException::class.java){AirPlaySetupSession.parseScreen(body)}
        }
        assertEquals(7000,AirPlaySetupSession.parseScreen(screen()).dataPort)
    }

    @Test fun screenPortCannotBeMissingZeroNegativeOutOfRangeOrWrapped(){
        for(value in listOf(null,0,-1,65536L,4294974296L,"bad")){
            assertThrows(IllegalArgumentException::class.java){AirPlaySetupSession.parseScreen(screen(value))}
        }
    }

    @Test fun screenRefusalNeverTriesRootOrSendsSpuriousTeardown(){
        var accepted=false
        val peer=Peer(Reply(status=400))
        assertThrows(RtspClient.StatusException::class.java){peer.session.screen(mapOf("type" to 110)){accepted=true}}
        assertFalse(accepted)
        assertEquals("SETUP $path RTSP/1.0",peer.requests().single().first)
    }

    @Test fun malformedSuccessfulScreenKeepsRemoteCleanupOwnership(){
        var accepted=false
        val peer=Peer(Reply(mapOf("streams" to emptyList<Any>())))
        assertThrows(IllegalArgumentException::class.java){peer.session.screen(mapOf("type" to 110)){accepted=true}}
        assertTrue(accepted)
        assertEquals(1,peer.requests().size)
    }

    @Test fun prepareFailureAfterScreenAllocationSendsTeardownBeforeControlIsClosed(){
        for(body in listOf(screen(),mapOf("streams" to emptyList<Any>()))){
            val peer=Peer(Reply(body),Reply())
            val resources=io.github.iamr0s.carvex.sessioncore.Resources()
            resources.own(AutoCloseable{peer.client.close()})
            resources.own(peer.session)
            try{
                peer.session.screen(mapOf("type" to 110,"streamConnectionID" to 321L))
                error("synthetic video socket failure before encoder creation")
            }catch(_:Exception){}finally{resources.close()}
            val requests=peer.requests()
            assertEquals(2,requests.size)
            assertEquals("TEARDOWN $path RTSP/1.0",requests[1].first)
            assertEquals(listOf(mapOf("type" to 110L,"streamConnectionID" to 321L)),Plist.dictionary(requests[1].body)["streams"])
            peer.session.close();assertEquals(2,peer.requests().size)
        }
    }

    @Test fun failedRecordStillOwnsScreenCleanupAndFailedTeardownDoesNotReregister(){
        val peer=Peer(Reply(screen()),Reply(status=500),Reply(status=500))
        peer.session.screen(mapOf("type" to 110,"streamConnectionID" to 321L))
        assertThrows(RtspClient.StatusException::class.java){peer.session.start()}
        peer.session.closeScreen();peer.session.closeScreen();peer.session.close()
        assertEquals(listOf("SETUP","RECORD","TEARDOWN"),peer.requests().map{it.first.substringBefore(' ')})
    }

    @Test fun screenRefusalOrNoScreenDoesNotCreateTeardownOwnership(){
        val peer=Peer(Reply(status=400))
        assertThrows(RtspClient.StatusException::class.java){peer.session.screen(mapOf("type" to 110))}
        peer.session.close()
        assertEquals(1,peer.requests().size)
        val empty=Peer();empty.session.close();assertTrue(empty.requests().isEmpty())
    }

    @Test fun lateSuccessfulScreenAfterCloseStillAttemptsCleanupOnTheOldClient(){
        val peer=Peer(Reply(screen()),Reply())
        peer.client.onExchange={exchange->
            if(exchange.method=="SETUP" && exchange.phase==RtspClient.Phase.RESPONSE)peer.session.close()
        }
        assertThrows(IllegalStateException::class.java){peer.session.screen(mapOf("type" to 110,"streamConnectionID" to 321L))}
        assertEquals(listOf("SETUP","TEARDOWN"),peer.requests().map{it.first.substringBefore(' ')})
        peer.session.close();assertEquals(2,peer.requests().size)
    }

    @Test fun staleContextAfterScreenResponseCannotLoseCleanupOwnership(){
        var checks=0
        val peer=Peer(Reply(screen()),Reply(),current={checks++;check(checks==1)})
        assertThrows(IllegalStateException::class.java){peer.session.screen(mapOf("type" to 110,"streamConnectionID" to 321L))}
        peer.session.close()
        assertEquals(listOf("SETUP","TEARDOWN"),peer.requests().map{it.first.substringBefore(' ')})
    }

    @Test fun wholeSessionStopUnregistersScreenWithoutPartialTeardown(){
        val peer=Peer(Reply(screen()))
        peer.session.screen(mapOf("type" to 110,"streamConnectionID" to 321L))
        peer.session.beginWholeSessionStop();peer.session.closeScreen();peer.session.close()
        assertEquals(listOf("SETUP"),peer.requests().map{it.first.substringBefore(' ')})
        assertThrows(IllegalStateException::class.java){peer.session.start()}
        assertThrows(IllegalStateException::class.java){peer.session.info()}
    }

    @Test fun wholeSessionStopRejectsLateScreenWithoutSendingAnotherPartialTeardown(){
        val peer=Peer(Reply(screen()))
        var installed=false
        peer.client.onExchange={exchange->
            if(exchange.method=="SETUP"&&exchange.phase==RtspClient.Phase.RESPONSE)peer.session.beginWholeSessionStop()
        }
        assertThrows(IllegalStateException::class.java){
            peer.session.screen(mapOf("type" to 110,"streamConnectionID" to 321L)){installed=true}
        }
        assertFalse(installed)
        peer.session.close()
        assertEquals(listOf("SETUP"),peer.requests().map{it.first.substringBefore(' ')})
    }

    @Test fun successfulControlInfoScreenAndConditionalRecordSerializeThroughRealClient(){
        for(skip in listOf(false,true)){
            val peer=Peer(Reply(mapOf("info" to mapOf("displays" to emptyList<Any>()),"skipRecord" to skip)),Reply(screen()),Reply())
            peer.session.control(mapOf("sessionUUID" to "synthetic-session"))
            peer.session.info()
            assertEquals(7000,peer.session.screen(mapOf("type" to 110,"streamConnectionID" to 123L)).dataPort)
            assertEquals(!skip,peer.session.start())
            val requests=peer.requests()
            assertEquals(if(skip)2 else 3,requests.size)
            assertEquals(listOf(mapOf("type" to 110L,"streamConnectionID" to 123L)),Plist.dictionary(requests[1].body)["streams"])
            assertEquals("1",requests[1].headers["x-apple-protocolversion"])
        }
    }

    @Test fun wirelessCarplayScreenSetupUsesLatencyAndUuidInsteadOfGenericTimestampInfo(){
        val peer=Peer(Reply(screen()))
        peer.session.screen(CarPlaySetupRequests.screen(99L,"display-uuid"))
        val request=peer.requests().single()
        assertEquals("SETUP $path RTSP/1.0",request.first)
        val encoded=Plist.dictionary(request.body)
        val actual=(encoded["streams"] as List<*>).single() as Map<*,*>
        assertEquals(110L,actual["type"]);assertEquals(99L,actual["streamConnectionID"])
        assertEquals(75L,actual["latencyMs"]);assertEquals("display-uuid",actual["uuid"])
        assertFalse(actual.containsKey("timestampInfo"))
        assertEquals(setOf("type","streamConnectionID","latencyMs","uuid"),actual.keys)
    }

    @Test fun wirelessControlProducerFieldsReachTheActualRtspBody(){
        val peer=Peer(Reply())
        peer.session.control(CarPlaySetupRequests.control("device","mac","name","model","16",
            "session-uuid",timingPort=10001))
        val actual=Plist.dictionary(peer.requests().single().body)
        assertEquals(10001L,actual["timingPort"])
        assertEquals(false,actual["updateSessionRequest"])
        assertEquals("session-uuid",actual["sessionUUID"])
        assertFalse(actual.containsKey("timingProtocol"))
        assertFalse(actual.containsKey("isScreenMirroringSession"))
        assertFalse(actual.containsKey("features"))
    }

    @Test fun missingSelectedDisplayUuidFailsBeforeSendingScreenSetup(){
        val peer=Peer(Reply(screen()))
        assertThrows(IllegalArgumentException::class.java){
            peer.session.screen(CarPlaySetupRequests.screen(99L,null))
        }
        assertTrue(peer.requests().isEmpty())
    }

    @Test fun asynchronousReaderUsesTheSameSetupResponseConsumer(){
        val replyOutput=java.io.PipedOutputStream()
        val input=java.io.PipedInputStream(replyOutput)
        val written=ByteArrayOutputStream()
        val output=object:java.io.OutputStream(){
            override fun write(value:Int){written.write(value)}
            override fun write(bytes:ByteArray,offset:Int,length:Int){written.write(bytes,offset,length)}
            override fun flush(){replyOutput.write(wire(Reply(mapOf("info" to emptyMap<String,Any?>(),"skipRecord" to true)),1));replyOutput.flush()}
        }
        val client=RtspClient(input,output)
        try{
            client.startReader()
            val session=AirPlaySetupSession(client,path)
            assertNull(session.control(emptyMap()).eventPort)
            assertTrue(session.info().isEmpty());assertFalse(session.start())
            val requests=ByteArrayInputStream(written.toByteArray())
            assertEquals("SETUP $path RTSP/1.0",RtspWire.read(requests).first)
            assertEquals(0,requests.available())
        }finally{client.close();replyOutput.close();input.close()}
    }
}

class SetupPlistIntegerTest {
    @Test fun commonCfTypesAndCStringIntegerPrefixes(){
        val cases=listOf(null to 0L,true to 1L,false to 0L,1L to 1L,-2L to -2L,
            1.9 to 1L,-1.9 to -1L,"true" to 1L,"YES" to 1L,"on" to 1L,"false" to 0L,
            "  -12tail" to -12L,"010" to 8L,"0x10tail" to 16L,"09" to 9L,"08" to 8L,
            "0b10tail" to 2L,"-0B11" to -3L,"0b2" to 0L,"0xG" to 0L,"1\u0000bad" to 1L,
            "18446744073709551615" to -1L,"18446744073709551616" to 0L,
            byteArrayOf(1,2) to 258L,ByteArray(0) to 0L,ByteArray(8){0xff.toByte()} to -1L,Double.NaN to 0L)
        for((input,expected) in cases)assertEquals(SetupPlistInteger.Result(expected),SetupPlistInteger.read(input))
    }

    @Test fun conversionErrorsAreNotConfusedWithZeroSuccess(){
        for(input in listOf("bad","",emptyList<Any>(),emptyMap<String,Any>(),ByteArray(9),"1".repeat(128),"非")){
            assertEquals(SetupPlistInteger.Result(0,false),SetupPlistInteger.read(input))
        }
        assertEquals(SetupPlistInteger.Result(Long.MAX_VALUE,false),SetupPlistInteger.read(Double.POSITIVE_INFINITY))
        assertEquals(SetupPlistInteger.Result(Long.MIN_VALUE,false),SetupPlistInteger.read(Double.NEGATIVE_INFINITY))
    }
}

class AirPlayConnectionUrlTest {
    @Test fun usesPeerLiteralInsteadOfInetAddressNameSlashAndUnsignedToken(){
        val peer=java.net.InetAddress.getByAddress("not-the-url-host",byteArrayOf(192.toByte(),0,2,1))
        assertEquals("rtsp://192.0.2.1/18446744073709551615",AirPlayConnectionUrl.create(peer,-1))
    }
    @Test fun ipv6TargetDropsScopeWithoutChangingTheSocketAddress(){
        val bytes=ByteArray(16).apply{this[0]=0xfe.toByte();this[1]=0x80.toByte();this[15]=1}
        val peer=java.net.Inet6Address.getByAddress(null,bytes,7)
        val url=AirPlayConnectionUrl.create(peer,123)
        assertEquals("rtsp://fe80::1/123",url)
        assertFalse(url.contains('%'));assertFalse(url.contains('['));assertEquals(7,peer.scopeId)
    }
    @Test fun originalIpv6CompressionMappedAndCompatibleBranches(){
        fun words(vararg input:Int):java.net.Inet6Address {
            require(input.size==8)
            val raw=ByteArray(16){i->if(i%2==0)(input[i/2] ushr 8).toByte() else input[i/2].toByte()}
            return java.net.Inet6Address.getByAddress(null,raw,0)
        }
        val cases=listOf(
            words(0,0,0,0,0,0,0,0) to "::",
            words(0,0,0,0,0,0,0,1) to "::1",
            words(0,0,0,0,0,0,0,2) to "::0.0.0.2",
            words(0,0,0,0,0,0xffff,0xc000,0x0201) to "::ffff:192.0.2.1",
            words(0x2001,0,0,1,0,0,2,3) to "2001::1:0:0:2:3",
            words(0x2001,0,1,2,3,4,5,6) to "2001:0:1:2:3:4:5:6",
            words(0x2001,0xdb8,0,0,0,0,0,0) to "2001:db8::",
            words(0x2001,0xdb8,0,0,0,0,0,1) to "2001:db8::1")
        for((peer,expected) in cases)assertEquals("rtsp://$expected/123",AirPlayConnectionUrl.create(peer,123))
    }
}
