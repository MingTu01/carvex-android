package io.github.iamr0s.carvex.sessioncore

import io.github.iamr0s.carvex.pcm.PcmFormat
import io.github.iamr0s.carvex.pcm.Pcm16Converter
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class HostAudioBridgeTest {
    private class CapturingStream(private val pcmFormat:PcmFormat,
        private val beforeSend:suspend ()->Unit={}):NegotiatedAudioTransport {
        private val output=kotlinx.coroutines.channels.Channel<ByteArray>(8)
        @Volatile private var closed=false
        override val ready get()=!closed
        override val formats=mapOf(3 to pcmFormat)
        override val microphoneFormat:PcmFormat?=null
        override suspend fun send(category:Int,pcm:ByteArray,format:PcmFormat){
            assertEquals(3,category);assertEquals(pcmFormat,format);beforeSend();output.send(pcm)
        }
        suspend fun next():ByteArray=withTimeout(2000){output.receive()}
        override fun close(){closed=true;output.cancel()}
    }

    @Test fun fresh120msSpeechCallbackIsForwardedWholeInsteadOfLosingItsFirst20ms():Unit=runBlocking {
        val source=PcmFormat(16000,1)
        val stream=CapturingStream(source)
        val bridge=HostAudioBridge({},configureRoute={})
        val pcm=ByteArray(3840){it.toByte()}
        try{
            bridge.attach(stream);bridge.receive(pcm,3,source)
            assertArrayEquals(pcm,stream.next())
            assertEquals(0L,bridge.snapshot().dropped)
        }finally{bridge.close()}
    }

    @Test fun resampledSpeechRetainsEveryFreshBlockAndConverterPhase():Unit=runBlocking {
        val source=PcmFormat(16000,1);val target=PcmFormat(48000,1)
        val stream=CapturingStream(target)
        val bridge=HostAudioBridge({},configureRoute={})
        val reference=Pcm16Converter(source,target)
        try{
            bridge.attach(stream)
            repeat(3){block->
                val pcm=ByteArray(3840){(it+block*31).toByte()}
                bridge.receive(pcm,3,source)
                assertArrayEquals(reference.convert(pcm),stream.next())
            }
            assertEquals(0L,bridge.snapshot().dropped)
        }finally{bridge.close()}
    }

    /** 实车（session d8b32229）：CarWith 只广告了 `101/default` 一条非音乐路由，
     *  会话建立时被导航（category 2）占住，于是语音（category 3）每次都在本地占位检查那里失败
     *  （`Audio stream type=101/default already has a session`），小爱的 PCM 一路被丢掉。
     *
     *  语音在这种情况下必须并进那条活着的流：接收端看到的还是同一条 `101/default`，
     *  和另建一条语音流没有任何区别，却不需要任何 SETUP。 */
    @Test fun speechWithoutItsOwnRouteIsMergedIntoTheLiveLane():Unit=runBlocking {
        val source=PcmFormat(16000,1)
        val nav=PcmFormat(48000,2)
        val seen=mutableListOf<Triple<Int,PcmFormat,ByteArray>>()
        val lane=object:NegotiatedAudioTransport {
            @Volatile private var closed=false
            override val ready get()=!closed
            override val formats=mapOf(2 to nav)
            override val microphoneFormat:PcmFormat?=null
            override suspend fun send(category:Int,pcm:ByteArray,format:PcmFormat){
                synchronized(seen){seen+=Triple(category,format,pcm)}
            }
            override fun close(){closed=true}
        }
        val bridge=HostAudioBridge({},configureRoute={})
        val pcm=ByteArray(3840){it.toByte()}
        try{
            bridge.attach(lane)
            bridge.receive(pcm,3,source)
            withTimeout(2000){while(synchronized(seen){seen.isEmpty()})delay(1)}
            val one=synchronized(seen){seen.single()}
            // 走的是导航那条流（2），送到接收端的格式也是那条流的格式。
            assertEquals(2,one.first);assertEquals(nav,one.second)
            assertArrayEquals(Pcm16Converter(source,nav).convert(pcm),one.third)
            assertEquals(0L,bridge.snapshot().dropped)
        }finally{bridge.close()}
    }

    /** 有自己的流时绝不合并：车机广告了 `100/speechRecognition` 就必须各走各的。 */
    @Test fun aCategoryWithItsOwnRouteIsNotMerged():Unit=runBlocking {
        val nav=PcmFormat(48000,2);val speech=PcmFormat(16000,1)
        val counts=mutableMapOf<Int,AtomicInteger>()
        val transport=object:NegotiatedAudioTransport {
            @Volatile private var closed=false
            override val ready get()=!closed
            override val formats=mapOf(2 to nav,3 to speech)
            override val microphoneFormat:PcmFormat?=null
            override suspend fun send(category:Int,pcm:ByteArray,format:PcmFormat){
                synchronized(counts){counts.getOrPut(category){AtomicInteger()}.incrementAndGet()}
            }
            override fun close(){closed=true}
        }
        val bridge=HostAudioBridge({},configureRoute={})
        try{
            bridge.attach(transport)
            bridge.receive(ByteArray(3840),3,speech)
            withTimeout(2000){while(synchronized(counts){counts[3]?.get()?:0}==0)delay(1)}
            assertEquals(0,synchronized(counts){counts[2]?.get()?:0})
            assertEquals(1,synchronized(counts){counts[3]?.get()?:0})
        }finally{bridge.close()}
    }

    /** 合并之后两路声音**不能一块一块拼**：那是每 120 ms 换一个人的断续句子，
     *  实车（session a77d8359）小爱唤醒导航之后听到的就是这个。共享通道一次只让一个来源说话。
     *
     *  用送出去的字节数区分来源。这里**不写死**转换后的长度：重采样器是带相位状态的，
     *  16k/1→48k/2 对同一个块实际输出比理论值少几个样本，48k/2→48k/2 则是原样透传。
     *  用同一套 [Pcm16Converter] 现算期望值，测的就只剩仲裁逻辑本身。 */
    @Test fun aSharedLaneCarriesOneSourceAtATime():Unit=runBlocking {
        val nav=PcmFormat(48000,2);val speech=PcmFormat(16000,1)
        val seen=mutableListOf<Int>()
        val lane=object:NegotiatedAudioTransport {
            @Volatile private var closed=false
            override val ready get()=!closed
            override val formats=mapOf(2 to nav)
            override val microphoneFormat:PcmFormat?=null
            override suspend fun send(category:Int,pcm:ByteArray,format:PcmFormat){
                synchronized(seen){seen+=pcm.size}
            }
            override fun close(){closed=true}
        }
        val bridge=HostAudioBridge({},configureRoute={})
        val speechBlock=ByteArray(1920){it.toByte()}
        val navBlock=ByteArray(5760){it.toByte()}
        val speechOut=Pcm16Converter(speech,nav).convert(speechBlock).size
        try{
            bridge.attach(lane)
            // 语音先占住通道。
            bridge.receive(speechBlock,3,speech)
            withTimeout(2000){while(synchronized(seen){seen.isEmpty()})delay(1)}
            // 紧接着的导航块必须让路，不能插进同一句话里。
            repeat(3){bridge.receive(navBlock,2,nav)}
            delay(120)
            assertEquals("导航块抢不过还在说话的语音，只有语音那一块被发出去",
                listOf(speechOut),synchronized(seen){seen.toList()})
            assertEquals(3L,bridge.snapshot().yielded)
        }finally{bridge.close()}
    }

    /** 语音优先级高于导航：导航正在说的时候，语音可以立刻抢占。 */
    @Test fun speechPreemptsNavigationOnTheSharedLane():Unit=runBlocking {
        val nav=PcmFormat(48000,2);val speech=PcmFormat(16000,1)
        val seen=mutableListOf<Int>()
        val lane=object:NegotiatedAudioTransport {
            @Volatile private var closed=false
            override val ready get()=!closed
            override val formats=mapOf(2 to nav)
            override val microphoneFormat:PcmFormat?=null
            override suspend fun send(category:Int,pcm:ByteArray,format:PcmFormat){
                synchronized(seen){seen+=pcm.size}
            }
            override fun close(){closed=true}
        }
        val bridge=HostAudioBridge({},configureRoute={})
        val navBlock=ByteArray(5760){it.toByte()}
        val speechBlock=ByteArray(1920){it.toByte()}
        val navOut=Pcm16Converter(nav,nav).convert(navBlock).size
        val speechOut=Pcm16Converter(speech,nav).convert(speechBlock).size
        try{
            bridge.attach(lane)
            bridge.receive(navBlock,2,nav)
            withTimeout(2000){while(synchronized(seen){seen.isEmpty()})delay(1)}
            // 抢占不需要等对方安静下来。
            bridge.receive(speechBlock,3,speech)
            withTimeout(2000){while(synchronized(seen){seen.size<2})delay(1)}
            assertEquals(listOf(navOut,speechOut),synchronized(seen){seen.toList()})
            assertEquals(0L,bridge.snapshot().yielded)
        }finally{bridge.close()}
    }

    @Test fun callbackContinuityAccountsFor120msBlocksAndNormalJitter(){        val source=PcmFormat(16000,1)
        assertFalse(pcmCallbackHasGap(null,0,source,3840))
        assertFalse(pcmCallbackHasGap(0,120_000_000,source,3840))
        assertFalse(pcmCallbackHasGap(0,150_000_000,source,3840))
        assertFalse(pcmCallbackHasGap(0,220_000_000,source,3840))
        assertTrue(pcmCallbackHasGap(0,220_000_001,source,3840))
        // Callback completion spacing follows the current block's duration,
        // including a change from a short block to a long one and vice versa.
        assertFalse(pcmCallbackHasGap(40_000_000,160_000_000,source,3840))
        assertFalse(pcmCallbackHasGap(160_000_000,200_000_000,source,1280))
        assertTrue(pcmCallbackHasGap(160_000_000,500_000_000,source,1280))
    }

    @Test fun preservingFreshSpeechDoesNotRemoveFrameAlignmentOrAllocationBounds(){
        val bridge=HostAudioBridge({},configureRoute={})
        try{
            assertThrows(IllegalArgumentException::class.java){
                bridge.receive(ByteArray(32770),3,PcmFormat(16000,1))
            }
            assertThrows(IllegalArgumentException::class.java){
                bridge.receive(ByteArray(3841),3,PcmFormat(16000,1))
            }
            assertEquals(0L,bridge.snapshot().received)
        }finally{bridge.close()}
    }

    @Test fun fullSpeechBlocksStillExpireInASlowQueueAndFreshSpeechRecovers():Unit=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val calls=AtomicInteger();val source=PcmFormat(16000,1)
        val stream=CapturingStream(source){
            if(calls.incrementAndGet()==1){entered.complete(Unit);release.await()}
        }
        val bridge=HostAudioBridge({},configureRoute={})
        val first=ByteArray(3840){1};val stale=ByteArray(3840){2};val fresh=ByteArray(3840){3}
        try{
            bridge.attach(stream);bridge.receive(first,3,source)
            withTimeout(2000){entered.await()}
            bridge.receive(stale,3,source)
            delay(150) // Actual queue age, not the 120 ms represented by this PCM block.
            release.complete(Unit)
            assertArrayEquals(first,stream.next())
            withTimeout(2000){while(bridge.snapshot().dropped==0L)delay(1)}
            bridge.receive(fresh,3,source)
            assertArrayEquals(fresh,stream.next())
            assertEquals(2,calls.get());assertEquals(1L,bridge.snapshot().dropped)
        }finally{release.complete(Unit);bridge.close()}
    }

    private class Stream(category:Int,val fail:Boolean=false):NegotiatedAudioTransport {
        @Volatile var closed=false
        val sent=AtomicInteger()
        override val ready get()=!closed
        override val formats=mapOf(category to PcmFormat(48000,2))
        override val microphoneFormat:PcmFormat?=null
        override suspend fun send(category:Int,pcm:ByteArray,format:PcmFormat){if(fail)error("broken audio socket");sent.incrementAndGet()}
        override fun close(){closed=true}
    }
    @Test fun alternateAttachmentAndFailureLeaveMediaAlive()=runBlocking {
        val bridge=HostAudioBridge({},configureRoute={})
        val media=Stream(1);val alternate=Stream(2,true)
        try{
            bridge.attach(media);bridge.attach(alternate)
            assertEquals(setOf(1,2),bridge.categories());assertFalse(media.closed)
            bridge.receive(ByteArray(960),2,PcmFormat(48000,2))
            withTimeout(2000){while(!alternate.closed)delay(1)}
            assertEquals(setOf(1),bridge.categories());assertFalse(media.closed)
            bridge.receive(ByteArray(960),1,PcmFormat(48000,2))
            withTimeout(2000){while(media.sent.get()==0)delay(1)}
        }finally{bridge.close()}
        assertTrue(media.closed);assertTrue(alternate.closed)
    }
    @Test fun failedRouteConfigurationRestoresExistingCategories()=runBlocking {
        var attempts=0
        val bridge=HostAudioBridge({},configureRoute={if(++attempts==2)error("host rejected route")})
        val media=Stream(1);val alternate=Stream(2)
        try{
            bridge.attach(media)
            try{bridge.attach(alternate);fail("must reject")}catch(_:IllegalStateException){}
            assertEquals(setOf(1),bridge.categories());assertFalse(media.closed);assertTrue(alternate.closed)
        }finally{bridge.close()}
    }
    private class Microphone:MicrophoneInput {
        @Volatile var closed=false
        override val ready get()=!closed
        override fun close(){closed=true}
    }
    @Test fun microphoneFailureRevokesAdvertisedReadiness():Unit=runBlocking {
        val bridge=HostAudioBridge({},configureRoute={})
        val microphone=Microphone()
        try{
            assertFalse(bridge.microphoneReady())
            bridge.attachMicrophone(microphone,PcmFormat(16000,1))
            assertTrue(bridge.microphoneReady())
            microphone.close()
            assertFalse(bridge.microphoneReady())
            assertFalse(bridge.microphone(ByteArray(320),PcmFormat(16000,1)))
        }finally{bridge.close()}
    }
    @Test fun microphoneReplacementClosesPreviousAndRejectsDeadReceiver():Unit=runBlocking {
        val bridge=HostAudioBridge({},configureRoute={})
        val first=Microphone();val second=Microphone();val dead=Microphone().apply{close()}
        try{
            bridge.attachMicrophone(first,PcmFormat(16000,1))
            bridge.attachMicrophone(second,PcmFormat(24000,1))
            assertTrue(first.closed);assertFalse(second.closed)
            try{bridge.attachMicrophone(dead,PcmFormat(16000,1));fail("dead input must be rejected")}catch(_:IllegalStateException){}
            assertTrue(bridge.microphoneReady());assertFalse(second.closed)
        }finally{bridge.close()}
        assertTrue(second.closed)
    }
    @Test fun preferredMusicRateIsAvailableBeforeDownstreamAttach(){
        val bridge=HostAudioBridge({},configureRoute={})
        try{assertEquals(48000,bridge.musicSampleRate())}finally{bridge.close()}
    }
    @Test fun initialActiveStateAndUnboundPcmAreObservableWithoutInventingTransport(){
        val logs=mutableListOf<String>()
        val bridge=HostAudioBridge({logs+=it},configureRoute={})
        try{
            bridge.state(3,1);bridge.state(3,1)
            bridge.receive(ByteArray(960),3,PcmFormat(24000,1))
            bridge.receive(ByteArray(960),3,PcmFormat(24000,1))
            assertEquals(1,logs.count{it.startsWith("Audio host state observed category=3 state=1")})
            assertEquals(1,logs.count{it.startsWith("Audio source first category=3")&&"attached=false" in it})
            assertFalse(logs.any{"-> resume" in it})
            assertTrue(bridge.categories().isEmpty())
            assertEquals(2L,bridge.snapshot().dropped)
        }finally{bridge.close()}
    }
    @Test fun pauseDiagnosticsSeparateSourceStateFromReceiverPlayback():Unit=runBlocking {
        val logs=java.util.concurrent.CopyOnWriteArrayList<String>()
        val bridge=HostAudioBridge({logs+=it},configureRoute={})
        val media=Stream(1)
        try{
            bridge.attach(media)
            bridge.receive(ByteArray(7680),1,PcmFormat(48000,2))
            withTimeout(2000){while(media.sent.get()==0)delay(1)}
            bridge.state(1,0)
            assertTrue(logs.any{it.startsWith("Audio source category=1")&&"blockMs=40" in it})
            assertTrue(logs.any{it.startsWith("Audio state timing category=1 state=0")&&"lastSendMonoNs=" in it})
            assertTrue(logs.any{"receiver buffered audio not measured" in it})
            assertTrue(media.ready)
        }finally{bridge.close()}
    }
}
