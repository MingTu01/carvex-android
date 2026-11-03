package io.github.iamr0s.carvex.carplay.core

/** Bounded 24A446 control/one-screen SETUP consumer. A new instance belongs to one
 * control connection, never to a saved peer. No authentication decisions live here.
 * See research/connection/27-setup-response-branches.md for original branches
 * and the deliberately narrower local endpoint-info consumer. */
internal class AirPlaySetupSession(
    private val client:RtspClient,
    private val path:String,
    private val requireCurrent:()->Unit = {},
    private val log:(String)->Unit = {},
    /** Publish only after this consumer succeeds. The application stores a private
     * dictionary snapshot and uses it for all display/audio/HID consumers. This is
     * not an implementation of Apple's entire native endpoint-property updater. */
    private val consumeInfo:(Map<String,Any?>)->Map<String,Any?> = {it.toMap()}
):AutoCloseable {
    data class ControlReply(val eventPort:Int?,val keepAlivePort:Int?,val enabledFeatures:List<*>?)
    data class ScreenReply(val dataPort:Int)
    @Volatile private var closed=false
    @Volatile private var wholeSessionStopping=false
    private val lifecycleLock=Any()
    private var allocatedScreen:Map<String,Any?>?=null
    private var skipRecord=false
    private var started=false
    private var infoSnapshot:Map<String,Any?>?=null

    private fun current(){check(!closed){"SETUP session closed"};requireCurrent()}

    private fun request(method:String,body:ByteArray=byteArrayOf(),url:String=path):RtspMessage {
        current()
        val reply=client.request(method,url,body)
        current()
        if(reply.status !in 200..299)throw RtspClient.StatusException(reply.status,method,url)
        return reply
    }

    /** No invented alternate URL or retry after an explicit peer rejection. */
    fun control(fields:Map<String,Any?>,update:Boolean=false):ControlReply {
        val body=fields.toMutableMap().apply{put("updateSessionRequest",update)}
        val response=request("SETUP",Plist.encode(body)).dictionaryOrEmpty()
        // AP12 ignores a non-dictionary info value and logs consumer failure. Neither
        // establishes protectedInfoFetched; the later GET remains available.
        val embedded=response["info"]
        if(embedded is Map<*,*> && embedded.keys.all{it is String}){
            @Suppress("UNCHECKED_CAST")
            try{
                val accepted=consumeInfo(embedded as Map<String,Any?>)
                current();infoSnapshot=accepted
                log("SETUP embedded info consumed; separate GET /info not needed")
            }catch(t:Exception){
                current()
                log("SETUP embedded info consumer failed: "+t.javaClass.simpleName+"; GET /info remains pending")
            }
        }
        // AP12 only sets +0xdd. Zero/missing in a later update does not clear it.
        if(SetupPlistInteger.read(response["skipRecord"]).value!=0L)skipRecord=true
        val enabled=response["enabledFeatures"]
        require(!response.containsKey("enabledFeatures") || enabled is List<*>){"SETUP enabledFeatures is not an array"}
        return ControlReply(optionalPort(response,"eventPort"),optionalPort(response,"keepAlivePort"),enabled as? List<*>)
    }

    fun info():Map<String,Any?> {
        current()
        infoSnapshot?.let{return it}
        val reply=request("GET",url="/info")
        val accepted=consumeInfo(Plist.dictionary(reply.body))
        current();infoSnapshot=accepted
        log("GET /info consumed")
        return accepted
    }

    /** One caller requests one screen stream; do not search a malformed/multiple
     * stream list for a convenient matching item (AP21 0x2519bbf24). */
    fun screen(stream:Map<String,Any?>,accepted:()->Unit={}):ScreenReply {
        current()
        val reply=client.request("SETUP",path,Plist.encode(mapOf("streams" to listOf(stream))))
        if(reply.status !in 200..299)throw RtspClient.StatusException(reply.status,"SETUP",path)
        val descriptor=mapOf("type" to 110,"streamConnectionID" to stream["streamConnectionID"])
        val late=synchronized(lifecycleLock){
            if(closed)true else {allocatedScreen=descriptor;false}
        }
        // close may run while SETUP is in flight. A late 2xx still allocated a
        // peer stream: attempt cleanup on this old client, never a replacement.
        if(late)teardownScreen(descriptor)
        current()
        // A successful wire response may have allocated a remote stream even if
        // its body is malformed. Let the caller retain teardown ownership first.
        accepted()
        return parseScreen(reply.dictionaryOrEmpty())
    }

    /** Returns whether RECORD was sent. Commit started only after a required
     * RECORD succeeds; skipRecord also establishes the local started state. */
    @Synchronized fun start():Boolean {
        current()
        if(started)return false
        if(!skipRecord)request("RECORD")
        current();started=true
        log(if(skipRecord)"RECORD omitted by SETUP skipRecord; visual acceptance pending"
            else "RECORD accepted; visual acceptance pending")
        return !skipRecord
    }

    /** Unregister before sending, including prepare failures before an encoder
     * exists. Failed teardown must not resurrect the stream or be sent twice. */
    fun closeScreen() {
        val descriptor=synchronized(lifecycleLock){allocatedScreen.also{allocatedScreen=null}}?:return
        teardownScreen(descriptor)
    }

    private fun teardownScreen(descriptor:Map<String,Any?>){
        if(wholeSessionStopping)return
        try{
            // This exact client belongs to the old connection even after a local
            // cancellation. Cleanup must not re-enter the new-session event gate.
            client.plist("TEARDOWN",path,mapOf("streams" to listOf(descriptor)))
            log("screen TEARDOWN accepted")
        }catch(t:Exception){log("screen TEARDOWN failed: "+t.javaClass.simpleName)}
    }

    /** Full stop owns all peer streams, including a SETUP whose reply is still in
     * flight. Do not generate partial cleanup after the full-stop request. */
    fun beginWholeSessionStop(){
        synchronized(lifecycleLock){
            wholeSessionStopping=true;closed=true;infoSnapshot=null;allocatedScreen=null
        }
    }

    override fun close(){
        val descriptor=synchronized(lifecycleLock){
            closed=true;infoSnapshot=null
            allocatedScreen.also{allocatedScreen=null}
        }
        if(descriptor!=null)teardownScreen(descriptor)
    }

    companion object {
        private fun RtspMessage.dictionaryOrEmpty():Map<String,Any?> =
            if(body.isEmpty())emptyMap() else Plist.dictionary(body)

        private fun optionalPort(response:Map<String,Any?>,key:String):Int? {
            val number=SetupPlistInteger.read(response[key])
            if(!number.valid || number.value==0L)return null
            // The original getter has no TCP range check here. This explicit
            // socket boundary avoids narrowing/wrapping an untrusted int64.
            require(number.value in 1..65535){"SETUP $key outside TCP/UDP port range"}
            return number.value.toInt()
        }

        internal fun parseScreen(response:Map<String,Any?>):ScreenReply {
            val streams=response["streams"] as? List<*>
                ?:throw IllegalArgumentException("Screen SETUP missing streams array")
            require(streams.size==1){"Screen SETUP must contain exactly one stream"}
            val stream=streams.single() as? Map<*,*>
                ?:throw IllegalArgumentException("Screen SETUP stream is not a dictionary")
            val type=SetupPlistInteger.read(stream["type"])
            require(type.valid && type.value==110L){"Screen SETUP type mismatch"}
            val port=SetupPlistInteger.read(stream["dataPort"])
            require(port.valid && port.value in 1..65535){"Screen SETUP missing or invalid dataPort"}
            return ScreenReply(port.value.toInt())
        }
    }
}

/** CFGetInt64's common binary-plist inputs, with value and conversion status kept
 * separate: skipRecord ignores status, ports do not. This is NOT general CF
 * equivalence: CFDate and non-ASCII strings are outside this bounded profile. */
internal object SetupPlistInteger {
    data class Result(val value:Long,val valid:Boolean=true)
    fun read(value:Any?):Result = when(value){
        null -> Result(0)
        is Boolean -> Result(if(value)1 else 0)
        is Byte,is Short,is Int,is Long -> Result((value as Number).toLong())
        is Float,is Double -> {
            val n=(value as Number).toDouble()
            when {
                n.isNaN() -> Result(0)
                n>=9223372036854775808.0 -> Result(Long.MAX_VALUE,false)
                n< -9223372036854775808.0 -> Result(Long.MIN_VALUE,false)
                else -> Result(n.toLong())
            }
        }
        is ByteArray -> if(value.size>8)Result(0,false) else
            Result(value.fold(0L){n,b->(n shl 8) or (b.toLong() and 255)})
        is String -> text(value)
        else -> Result(0,false)
    }

    private fun text(value:String):Result {
        // Native conversion has a 128-byte C buffer. Embedded NUL terminates
        // scanning; reject unsupported characters rather than guessing encoding.
        if(value.length>=128 || value.any{it.code>127})return Result(0,false)
        val s=value.substringBefore('\u0000')
        if(s.lowercase(java.util.Locale.ROOT) in setOf("true","yes","y","on","1"))return Result(1)
        if(s.lowercase(java.util.Locale.ROOT) in setOf("false","no","n","off","0"))return Result(0)
        val input=s.trimStart(' ','\t','\r','\n','\u000b','\u000c')
        var index=0
        var negative=false
        if(input.startsWith("-") || input.startsWith("+")){negative=input[0]=='-';index++}
        if(index>=input.length)return Result(0,false)
        var radix=10
        if(input[index]=='0'){
            if(index+2<input.length && input[index+1].lowercaseChar()=='x' && input[index+2].digitToIntOrNull(16)!=null){
                radix=16;index+=2
            }else if(index+2<input.length && input[index+1].lowercaseChar()=='b' && input[index+2] in '0'..'1'){
                radix=2;index+=2
            }else if(index+1<input.length && input[index+1] in '0'..'7'){
                radix=8
            }
        }
        val start=index
        while(index<input.length && input[index].digitToIntOrNull(radix)!=null)index++
        if(index==start)return Result(0,false)
        // VSNScanF's integer loop uses unchecked 64-bit mul/add, then sign.
        var result=0L
        for(i in start until index)result=result*radix+input[i].digitToInt(radix)
        return Result(if(negative)-result else result)
    }
}

/** APTransport 0x24fdc93c0 uses getpeername + SockAddrToString(flags=3):
 * peer IP, no port/scope, unsigned connection token. That original RTSP target
 * does not add IPv6 brackets here; it is not Java URI normalization. The socket
 * still retains its scoped address. Never use InetAddress.toString (name/IP). */
internal object AirPlayConnectionUrl {
    fun create(peer:java.net.InetAddress,token:Long):String =
        "rtsp://"+literal(peer.address)+"/"+java.lang.Long.toUnsignedString(token)

    private fun literal(bytes:ByteArray):String {
        fun v4(offset:Int)=bytes.drop(offset).joinToString("."){(it.toInt() and 255).toString()}
        if(bytes.size==4)return v4(0)
        require(bytes.size==16)
        // IPv6AddressToCString has explicit mapped/compatible IPv4 forms.
        if(bytes.take(10).all{it==0.toByte()} && bytes[10]==(-1).toByte() && bytes[11]==(-1).toByte())
            return "::ffff:"+v4(12)
        if(bytes.take(12).all{it==0.toByte()}){
            if(bytes.slice(12..14).all{it==0.toByte()} && (bytes[15].toInt() and 255)<=1)
                return if(bytes[15]==0.toByte())"::" else "::1"
            return "::"+v4(12)
        }
        val words=List(8){i->((bytes[i*2].toInt() and 255) shl 8) or (bytes[i*2+1].toInt() and 255)}
        var bestStart=-1;var bestLength=1;var i=0
        while(i<8){
            if(words[i]!=0){i++;continue}
            val start=i
            while(i<8 && words[i]==0)i++
            if(i-start>bestLength){bestStart=start;bestLength=i-start}
        }
        if(bestStart<0)return words.joinToString(":"){it.toString(16)}
        return words.take(bestStart).joinToString(":"){it.toString(16)}+"::"+
            words.drop(bestStart+bestLength).joinToString(":"){it.toString(16)}
    }
}
