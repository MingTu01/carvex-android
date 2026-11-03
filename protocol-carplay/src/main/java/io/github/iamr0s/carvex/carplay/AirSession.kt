package io.github.iamr0s.carvex.carplay
import io.github.iamr0s.carvex.sessioncore.*
import android.content.Context
import android.net.*
import android.os.Build
import io.github.iamr0s.carvex.carplay.core.*
import io.github.iamr0s.carvex.pcm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import java.net.*
import java.io.*
import java.nio.*

data class Screen(val width:Int,val height:Int,val fps:Int,val uuid:String?,val touchId:String?,val touch:HidTouch?)
class AirSession(private val context:Context,private val path:NetworkPath,private val bootstrap:WifiBootstrap,
    private val options:Options,private val resources:Resources,private val log:(String)->Unit,
    private val input:(TouchSample)->Boolean,
    /** CarPlay 按键（HID usage）的按下/松开：prefix 是页面（media/keyboard），交由上层按协议映射。 */
    private val carPlayKey:(String,Int,Boolean)->Boolean,
    private val failure:(Throwable)->Unit,
    private val runtimeChanged:(RuntimeIapState)->Unit={},
    /** Car receiver-side requests that need a local reaction (界面切换/日夜/焦点). Defaults keep the
     *  session usable when a caller does not implement them. */
    private val uiRequest:(String)->Boolean={false},
    private val nightMode:(Int)->Unit={},
    private val focusOffer:()->Unit={},
    /** Car voice request: the raw action text, interpreted by the caller because its value set is not
     *  documented. Reports whether anything acted on it, which decides 200 vs 501. */
    private val voice:(String)->Boolean={false},
    /** Said at most once per session when the system refuses to pin a socket to the car network
     *  (VPN lockdown, WLAN restrictions). See [Bootstrap.bind]. */
    private val warn:(String)->Unit={},
    /** 本机可编的 (宽,高,上限)→实际帧率 查询，用于 display 能力过滤。
     *  注入而不是直接调用 VideoEncoder：编码器属于上层的会话编排层（:session），
     *  而本类在分层上位于它**下面**（:protocol-carplay）。直接引用会形成环。 */
    private val localFps:(Int,Int,Int)->Int?={_,_,_->null},
    private val securityBinding:SecurityBinding,
    private val sourceContextCurrent:()->Boolean,
    /** Selected internally for this peer; not inferred from physical transport. */
    private val setupInputProvider:SetupInputProvider?=null,
    private val pairedCapabilityGate:Boolean?=null,
    controlPublication:CarPlayControlPublication?=null) {
    private val network=path.network
    @Volatile private var address=Bootstrap.address(path.properties,bootstrap.address)
    /** Current endpoint candidates also constrain timing/invitation peers. The
     * publication may already be listening on this attempt's selected Network. */
    @Volatile private var acceptablePeers:Set<java.net.InetAddress> =bootstrap.endpoint?.addresses.orEmpty().mapNotNull{runCatching{Bootstrap.address(path.properties,it)}.getOrNull()}.toSet()+address
    private val bindingRefused=java.util.concurrent.atomic.AtomicBoolean(false)
    /** Report once; permission refusal continues with a selected Wi-Fi source address. */
    private fun pinningRefused(t:SocketException){
        if(!bindingRefused.compareAndSet(false,true))return
        val message="Selected CarPlay network mark denied; continuing with selected Wi-Fi source binding. Actual routing pending: "+t.message
        log(message);warn(message)
    }
    /** 控制面读超时：固件对 AirPlay 控制消息的发送超时默认 60 s（`APSenderSessionMessageSendTimeout`，`S-0121`），
     *  teardown 另按 50 s 封顶（`S-0122`）；这里用 60 s 覆盖两者——TEARDOWN 也走这条连接。
     *  注意外层 phase 的上限仍然先起作用：Stage.SETUP 是 15 s，落在里面的请求实际由 phase 判失败。 */
    private val controlReadTimeoutMs=60_000
    private fun connectedSocket(peer:InetAddress,port:Int,readTimeoutMs:Int=10_000):Socket=Bootstrap.socket(network,peer,port,
        resources,options.bindNetwork,::pinningRefused,
        fallbackLocal={NetworkAddressCandidates.sourceFor(path.properties.linkAddresses.map{
            Bootstrap.address(path.properties,requireNotNull(it.address.hostAddress))},peer)},
        readTimeoutMs=readTimeoutMs)
    @Volatile private var client:RtspClient?=null
    @Volatile private var stopping=false
    @Volatile private var localMediaAddress:InetAddress?=null
    @Volatile private var screen:Screen?=null
    /** Owns teardown even if preparation fails before a video encoder exists. */
    @Volatile private var setupLifecycle:AirPlaySetupSession?=null
    /** 本次会话的连接 URL（`rtsp://<host>/<u64>`）：SETUP/RECORD/TEARDOWN/音频 SETUP 共用。 */
    @Volatile private var connectionPath:String?=null
    @Volatile var receiverAudio:List<AudioCapabilities.Entry> = emptyList();private set
    private var receiverAudioLatencies:List<AudioLatencyCapabilities.Entry> = emptyList()
    @Volatile var requestKeyFrame:(()->Boolean)?=null
    @Volatile var screenOwnership:((Boolean)->Boolean)?=null
    private val commandLock=Any()
    private val events=SessionEventGate(securityBinding){!stopping&&sourceContextCurrent()}
    private val hidKeys=HidKeyRegistry()
    private val controlSiri=SiriRequests{voice(it.toString())}
    private val eventSiri=SiriRequests(allowMissingCSeq=true){voice(it.toString())}
    private val screenModes=ScreenResourceModes()
    /** Main audio ownership has to be settled before a main stream exists; see [MainAudioOwnership]. */
    private val audioOwnership=MainAudioOwnership(log){phone->
        val control=client?:throw IllegalStateException("Main audio ownership needs a control session")
        val notification=synchronized(commandLock){screenModes.audio(phone);modes()}
        try{control.plist("POST","/command",notification);true}
        catch(t:Throwable){synchronized(commandLock){screenModes.audio(!phone)};throw t}
    }
    private val modeOutput=Channel<Map<String,Any?>>(16)
    private var shared:ByteArray?=null
    private var mfi:AccessoryAuth.Mfi?=null
    private val streamId=Crypto.random.nextLong().and(Long.MAX_VALUE).coerceAtLeast(1)
    private var video:Socket?=null
    // This observer has its own short state lock; polling never waits for the video monitor.
    private val videoDiagnostics=VideoSendDiagnostics(log=log,
        extraThreads={listOfNotNull(client?.diagnosticReaderThread())})
    /** `/info.hidDevices[].uuid` 里登记过的设备；SETUP 之后逐个武装（第 10 章 §2.6）。 */
    private val hidArmUuids=java.util.Collections.synchronizedList(mutableListOf<String>())
    /** HID 报告计数：用来在本次会话的第一条报告上留一条"通道已通"的痕迹。 */
    private val hidReportsSeen=java.util.concurrent.atomic.AtomicLong()
    /** 只记一次的日志键：触摸/未知设备报告很频繁，每次都记会把真正有用的行冲掉。 */
    private val hidLogSeen=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val iapEventSeen=java.util.concurrent.atomic.AtomicInteger(0)
    private fun logOnce(key:String,message:String){if(hidLogSeen.add(key))log(message)}
    /** 未知设备的"内容组合"（uuid+长度+末字节）去重集：与 logOnce 的键同源，但用于放行
     *  同一个设备的**不同**报告。没有它，首见去重会把 03→00→04→00 这一串全吞掉，
     *  而"按键不来"要看的就是这串变化。 */
    private val hidUnknownObserved=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var videoCounter=0L
    private var lastVideoMonoUs:Long?=null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mainAudioUsers=java.util.concurrent.atomic.AtomicInteger(0)
    private val audioOwnershipLock=Mutex()
    private val audioSetupLock=Mutex()
    /** 本地按 (type, audioType) 查找；不代表对端允许同 type 的不同用途并发。 */
    private val audioStreams=AudioStreamLifecycle()
    /** 心跳用：按 category 记已建好的发送端，读它的包数与最后一包时刻。 */
    private val audioTransports=java.util.concurrent.ConcurrentHashMap<Int,NegotiatedAudioTransport>()
    @Volatile private var videoFrames=0L
    @Volatile private var lastVideoNanos=0L
    /** Local recovery policy: remember explicit navigation SETUP refusals for this session only. */
    private val rejectedPromptRoutes=java.util.concurrent.ConcurrentHashMap.newKeySet<CarPlayAudioPlan.Route>()
    /** 每条流响应里的 streamID，仅用于诊断；固件音频关闭路径不把它写入 TEARDOWN。 */
    private val audioStreamIds=java.util.concurrent.ConcurrentHashMap<String,Long>()
    private fun audioKey(type:Int,purpose:String)="$type/$purpose"
    private val runtimeOutput=Channel<ByteArray>(64)
    /** type=130 专用流；建立后手机→车机的 iAP2 字节改走这条 TCP（第 13 章 §2.6b）。 */
    @Volatile private var tunnel:IapTunnel?=null
    /** SETUP 响应里返回的 streamID：专用 RCS 消费者的登记身份；type130 关闭路径尚未闭合。 */
    @Volatile private var tunnelStreamId:Long?=null
    /** 麦克风 type100 响应的 streamID（可缺），仅保留诊断值。 */
    @Volatile private var micStreamId:Long?=null
    private fun micTeardownDescriptor():Map<String,Any?> = AudioTeardownDescriptor.forType(100)
    @Volatile private var runtimeCapabilities=CapabilityRegistry()
    private val nowPlayingPublisher=NowPlayingPublisher{runtime.send(NowPlayingUpdate.ID,it)}
    private fun emitRuntime(bytes:ByteArray):Boolean{
        if(stopping||!options.experiment)return false
        tunnel?.let{return it.send(bytes)}
        return runtimeOutput.trySend(bytes).isSuccess
    }
    private val runtime:RuntimeIap=RuntimeIap({emitRuntime(it)},log,{state->
        log("Runtime iAP state="+state)
        if(state in setOf(RuntimeIapState.SYNCHRONIZING,RuntimeIapState.DEGRADED,RuntimeIapState.CLOSED))runtimeCapabilities=CapabilityRegistry()
        nowPlayingPublisher.linkReady(state==RuntimeIapState.CONTROL_READY)
        runtimeChanged(state)
    },message={command->
        if(command.id in setOf(NowPlayingUpdate.START,NowPlayingUpdate.STOP,0x5003)&&
            runtimeCapabilities.sentByAccessory.any{it.value==command.id}&&
            runtimeCapabilities.receivedByAccessory.any{it.value==NowPlayingUpdate.ID}){
            if(command.id==0x5003)nowPlayingPublisher.setInfo(requireNotNull(command.fields))
            else nowPlayingPublisher.command(command.id,requireNotNull(command.fields))
        }
    },negotiate={link->
        Bootstrap.negotiateRuntimeIdentification(link,options.phoneMac,options.carPlayName,PairStore(context).identity(),
            experimentalIntegrityOnly=options.experiment,log=log)
    },identificationAccepted={runtimeCapabilities=it.capabilities})
    private val channelFailed=java.util.concurrent.atomic.AtomicBoolean(false)
    private fun channelFailure(source:String,t:Throwable){
        if(stopping)return // Normal stop owns cleanup; rejected late business is not a new failure.
        // Reader, deadline and waiting writers may observe the same terminal cause.
        if(!channelFailed.compareAndSet(false,true))return
        log("${"channel source="}${source}${" "}"+t.javaClass.simpleName+" "+(t.message?:"no message"))
        failure(t)
    }
    /** SETUP 被拒时点名加密路线：本实现只有 MFi/SAP（`et=16` + `ekey`/`eiv`），没有 FairPlay `/fp-setup`
     *  （W-0024：CarPlay 接收端也不走 FairPlay）。接收端若只认 FairPlay，恰好死在 SETUP 这一步，
     *  日志里必须能一眼看出这是路线缺失，而不是配置写错。 */
    private fun logSetupEncryptionRoute(where:String,status:Int){
        val route=if(mfi!=null)"et=16 (MFi/SAP) with ekey/eiv"
            else "no et/ekey/eiv at all (no MFi material)"
        log("SETUP rejected where="+where+" status="+status+"; this build offers "+route+
            " only: there is no FairPlay /fp-setup, so a receiver that requires FairPlay fails exactly here")
    }
    private val publication=controlPublication?:resources.own(CarPlayControlPublication(context,path,options,
        sourceContextCurrent,log,{channelFailure("invitation-listener",it)}))
    private val published get()=publication.published
    private val invited get()=publication.invited
    private val timingWallClock=CarPlayTiming.WallClock()
    init {
        resources.own(AutoCloseable{events.close();runtime.close();runtimeOutput.close();modeOutput.close();audioStreams.close();client?.close();scope.cancel()})
        // Events may arrive inside RtspClient.request's lock. Never issue a nested
        // request from that callback: a separate writer runs after its reply.
        scope.launch{
            try{for(notification in modeOutput){
                val control=client?:error("Modes before control session")
                // 车机对模式通知回非 2xx，是"这个通知我不接受"，不是"传输断了"。
                // 实车证据：422 曾把一条画面/音频都正常的会话在第 20 秒整条判死。
                // 只有 IO 层异常才升格成会话失败。
                try{control.plist("POST","/command",notification)}
                catch(t:RtspClient.StatusException){
                    log("modes-writer rejected status="+t.status+"; session kept")
                }
            }}catch(t:Exception){if(isActive)channelFailure("modes-writer",t)}
        }
        scope.launch{
            try{
                for(bytes in runtimeOutput){
                    val c=client?:error("Runtime iAP before control connection")
                    // Separate IO writer waits for the control result, never the reader/event
                    // worker. The iAP2 ACK is received independently by RuntimeIap.
                    CarPlayControlRequests.sendIap(c,bytes)
                }
            }catch(t:Exception){if(isActive){runtime.fail(t);if(t is IOException)channelFailure("runtime-control-writer",t)}}
        }
    }

    /** App disconnect, not process force-stop. Retain control until the whole
     * session transaction is confirmed or its bounded local failure is known. */
    fun stopNormally(){
        stopping=true
        val control=client;val url=connectionPath;val setup=setupLifecycle
        // The volatile gate rejects new business without waiting for a running
        // host event callback. Keep resource close/join after control completion.
        audioStreams.close();setup?.beginWholeSessionStop()
        runtimeOutput.close();modeOutput.close();scope.cancel()
        try{
            if(control==null||url==null||setup==null||!control.readerRunning){
                log("AirPlay whole-session stop skipped: established session control unavailable")
                return
            }
            log("AirPlay whole-session TEARDOWN begin streams=absent teardownType=2 totalBudgetMs=5000")
            control.stopSession(url)
            log("AirPlay whole-session TEARDOWN accepted; closing local transports")
        }catch(t:Exception){
            // This is our forced local cleanup policy, not EnsureStopped's native error exit.
            log("AirPlay whole-session TEARDOWN unconfirmed: "+t.javaClass.simpleName+"; forcing local cleanup")
        }finally{
            control?.close()
            // Resources closes tunnel/data sockets before runtime.close(). A
            // runtime writer may still hold its link lock in a socket write.
        }
    }
    /**
     * type=130 专用承载（第 13 章 §2.6b）。
     *
     * 固件：SETUP 的流类型 `0x82`，字段 `clientTypeUUID`/`clientUUID`/`channelID`/`controlType`/
     * `sendMessageAsIs` + 8 字节随机 `seed`（`0x251a7d9d0` L98-110）；回复里取 `streamID` 与
     * `NetworkPortData`，只有端口非 0 才走专用；专用流 type=`rmcl`，包为 `asyn`/`comm` 32 字节头，
     * 记录密钥盐 `DataStream-Salt<十进制 seed>`、label `DataStream-Output-Encryption-Key`
     * （`0x2519bfecc` L24-33）。
     *
     * 失败/端口缺失时保持原事件通道路径（`POST /command` 的 `iAPSendMessage`）。
     */
    private fun setupRuntimeTunnel(enabledFeatures:List<*>?){
        val secret=shared
        if(secret==null){log("iAP tunnel skipped: no HAP shared secret");return}
        if(!options.experiment){log("iAP tunnel skipped: experimental mode off");return}
        if(!IapTunnelSetupRequest.enabledBy(enabledFeatures)){
            log("iAP tunnel skipped: peer did not enable iAPChannel; keeping event path")
            return
        }
        val c=client?:return
        // 固件 `iap-setup-exchange24.md:16-27`：seed 是 64 位（signed int64 语义），
        // KDF 用它的**无符号十进制**；请求还必须带 controlType=2、wantsDedicatedSocket=true、
        // 以及 `X-Apple-ProtocolVersion`（父 SETUP 的版本）。
        val seed=java.security.SecureRandom().nextLong()
        val seedText=java.lang.Long.toUnsignedString(seed)
        // 24A446's channelID is a CFString, generated as "<endpoint>-RCS-<counter>".
        // Sending an integer here lets a receiver accept the outer SETUP but return
        // no type130 stream. Use the same AirPlay device identity exposed in SETUP.
        val messageBody=IapTunnelSetupRequest.build(options.phoneMac,seed)
        val message=runCatching{
            c.request("SETUP",connectionPath?:"/",
                Plist.encode(messageBody))
        }.getOrElse{
            log("iAP tunnel SETUP failed: "+it.javaClass.simpleName+" "+(it.message?:""));return
        }
        if(message.status !in 200..299){
            log("iAP tunnel SETUP rejected status="+message.status);return
        }
        val response=if(message.body.isEmpty())emptyMap<String,Any?>()else Plist.dictionary(message.body)
        log("iAP tunnel SETUP response keys="+response.keys.sorted().joinToString(","))
        val reply=runCatching{IapTunnelSetupResponse.parse(response)}.getOrElse{
            log("iAP tunnel SETUP malformed response: "+(it.message?:it.javaClass.simpleName)+"; keeping event path")
            return
        }
        // streamID 是专用 RCS 消费者的登记身份，必须保留下来（TEARDOWN 按 (type,streamID) 定位）。
        tunnelStreamId=reply.streamId
        val port=reply.dataPort
        if(port==null){
            log("iAP tunnel SETUP accepted without dedicated dataPort (streamID="+tunnelStreamId+"); keeping event path")
            return
        }
        val socket=runCatching{connectedSocket(address,port)}.getOrElse{
            log("iAP tunnel connect failed: "+it.javaClass.simpleName+" "+(it.message?:""));return
        }
        resources.own(AutoCloseable{runCatching{socket.close()}})
        val key=Crypto.hkdf(secret,"DataStream-Salt"+seedText,"DataStream-Output-Encryption-Key")
        val t=IapTunnel(socket.getOutputStream(),key,log)
        tunnel=t
        resources.own(t)
        log("iAP tunnel: type130 dataPort="+port+" streamID="+tunnelStreamId+" seed="+seedText+
            "; phone-to-car iAP now uses the dedicated stream")
        // 专用承载上手机不等对端数据就启动链路（固件 `0x24fda7e80` 的 send 路径同样不阻塞）。
        // 本实现还不会构造 `0x80` 主动 SYN，所以这里只装 NO_SYN 兜底：对端一直不说就 7 s 判死，
        // 不用 Detect 探路——专用流上那不是对端期待的开口方式。
        runtime.start(detectOnIdle=false)
    }

    /** 建立会话。返回 null 表示**这台车机没有可用显示**：控制会话与事件通道已经建好，但视频流与
     *  触摸与视频不绑定，独立按键仍可用。调用方按"仅音频/控制"继续。 */
    fun prepare(onDisplayNegotiation:()->Unit = {}):Screen? {
        events.requireCurrent()
        bootstrap.requireAuthenticatedBootstrap(options.experiment)
        // Advertise on usable local addresses before discovery/invitation; don't make the
        // first 43/01 address family a prerequisite for trying other candidates.
        val locals=NetworkAddressCandidates.local(path.properties.linkAddresses.map{it.address})
        require(locals.isNotEmpty())
        val observedTiming=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun startTiming(local:java.net.InetAddress):java.net.DatagramSocket{
            val socket=resources.own(DatagramSocket(null));Bootstrap.bind(network,socket,options.bindNetwork,::pinningRefused)
            socket.bind(InetSocketAddress(local,0))
            scope.launch{
                try{while(isActive){
                    val b=ByteArray(512);val packet=DatagramPacket(b,b.size);socket.receive(packet)
                    if(packet.address !in acceptablePeers)continue
                    val receivedAt=System.nanoTime()
                    val datagram=b.copyOfRange(packet.offset,packet.offset+packet.length)
                    val reply=CarPlayTiming.reply(datagram,receivedAt,System.nanoTime(),timingWallClock)
                    val shape=datagram.size.toString()+"/"+(datagram.firstOrNull()?.toInt()?.and(255))
                    if(observedTiming.size<16&&observedTiming.add(shape))log("Timing datagram length/firstByte="+shape+" replyBytes="+(reply?.size?:0))
                    if(reply!=null)socket.send(DatagramPacket(reply,reply.size,packet.socketAddress))
                }}catch(t:Exception){if(isActive)channelFailure("timing",t)}
            }
            return socket
        }
        val timings=java.util.concurrent.ConcurrentHashMap<java.net.InetAddress,java.net.DatagramSocket>()
        locals.forEach{local->timings[local]=startTiming(local)}
        publication.candidates(acceptablePeers)
        publication.start() // Idempotent: the network handoff already published on this lease.
        val discovery=if(bootstrap.endpoint?.metadata==null)
            ReceiverDiscovery(context,path,bootstrap,resources,log).also{it.start()}else null
        val metadata=try{bootstrap.endpoint?.metadata?.let{cached->
            // A discovered endpoint does not acquire an extra mandatory invitation
            // gate just because it was resolved before constructing AirSession.
            runBlocking{withTimeout(25000){published.await()}}
            cached
        }?:runBlocking{withTimeout(25000){
            published.await()
            log("Startup: publication ready; awaiting receiver metadata")
            val result=Channel<Result<ReceiverMetadata>>(1)
            val discoveryJob=launch {
                try {
                    val found=requireNotNull(discovery).await()
                    acceptablePeers=acceptablePeers+listOfNotNull(found.endpoint)+found.endpoints
                    publication.candidates(acceptablePeers)
                    result.send(Result.success(found))
                }
                catch(t:TimeoutCancellationException){
                    currentCoroutineContext().ensureActive()
                    result.send(Result.failure(t))
                }
                catch(t:CancellationException){throw t}
                catch(t:SecurityException){throw t}
                catch(t:Exception){
                    if(isActive){
                        log("AirPlay discovery failed category=DISCOVERY_FAILED: "+t.javaClass.simpleName+" "+(t.message?:""))
                        result.send(Result.failure(t))
                    }
                }
            }
            try {
                val selected=select<Result<ReceiverMetadata>> {
                    result.onReceive{value->
                        if(value.isSuccess){
                            log("Startup: receiver metadata ready; invitation is an independent input")
                            value
                        }else{
                            // 0x4301 已经过 iAP 认证；固件序列从 /info 开始，没有 invitation 闸门。
                            // 真车机（如 FRONTLANDER）既不广播 _airplay._tcp 也不发 invitation，
                            // 再等 invitation 只会把 25 秒预算耗尽在 discovery 上。
                            log("Startup: discovery failed; using the 0x4301 AirPlay endpoint without an invitation")
                            Result.success(ReceiverMetadata(null,bootstrap.publicKey,null,endpoint=address))
                        }
                    }
                    invited.onAwait{
                        // Candidate signal only: the current4301 supplies the endpoint,
                        // and the subsequent security route must still verify the peer.
                        log("Startup: invitation candidate received; using current4301 endpoint without a new trust assertion")
                        Result.success(ReceiverMetadata(null,bootstrap.publicKey,null,endpoint=address))
                    }
                }
                val resolved=selected.getOrElse{
                    log("Startup: receiver metadata unavailable; using the 0x4301 AirPlay endpoint")
                    ReceiverMetadata(null,bootstrap.publicKey,null,endpoint=address)
                }
                resolved
            } finally {
                discoveryJob.cancel()
                result.close()
                discovery?.let{resources.release(it)}
            }
        }}}catch(t:TimeoutCancellationException){
            // Local stage deadline is a connection failure, not user coroutine cancellation.
            throw SocketTimeoutException("${"AirPlay startup deadline: publication="}${published.isCompleted}${" receiver/invitation pending; validInvitation="}${invited.isCompleted}").also{it.initCause(t)}
        }
        log("Startup endpoint candidate ready; publication="+published.isCompleted+" invitationObserved="+invited.isCompleted)
        // 邀请来源的可接受集合：0x4301 的地址（已在字段初始化里），加上 Bonjour 解析到的地址。
        acceptablePeers=acceptablePeers+listOfNotNull(metadata.endpoint)+metadata.endpoints
        publication.candidates(acceptablePeers)
        val store=PairStore(context);val identity=store.identity()
        // Local policy: retain bootstrap-first ordering, but keep each address
        // paired with its own source port. This is not an Apple priority claim.
        fun openControlSocket():java.net.Socket {
            var last:Exception?=null
            val direct=bootstrap.endpoint?.addresses.orEmpty().mapNotNull{runCatching{Bootstrap.address(path.properties,it)}.getOrNull()}
            log("control endpoint sources: bonjourPort="+(metadata.port?.toString()?:"none")+" bootstrapPort="+bootstrap.port)
            val discovered=listOfNotNull(metadata.endpoint)+metadata.endpoints
            fun show(xs:List<java.net.InetAddress>)=xs.joinToString(","){a->
                a.hostAddress+(when(a){is java.net.Inet6Address->"(scope="+a.scopeId+")";else->""})}
            // 本端地址要现取：`locals` 是网络就绪那一刻的快照，而 IPv6 link-local 往往晚一步形成
            // （现场日志：IPv4=1/IPv6=0 之后约 1 秒才 IPv6=1），车机却可能只宣告 fe80::。用旧快照会把
            // 唯一可行的候选滤掉。同族过滤保留——拿 IPv4 源去连 IPv6 对端必然失败，等地址就绪才有意义。
            fun currentLocals():List<java.net.InetAddress>{
                val fresh=runCatching{
                    context.getSystemService(ConnectivityManager::class.java)?.getLinkProperties(network)
                }.getOrNull()
                return NetworkAddressCandidates.local((fresh?:path.properties).linkAddresses.map{it.address})
            }
            val waitUntil=System.nanoTime()+3_000_000_000L
            var localsNow=currentLocals()
            var candidates=ControlEndpointCandidates.select(localsNow,direct,bootstrap.port,discovered,metadata.port)
            while(candidates.isEmpty()&&System.nanoTime()<waitUntil){
                Thread.sleep(150)
                localsNow=currentLocals()
                candidates=ControlEndpointCandidates.select(localsNow,direct,bootstrap.port,discovered,metadata.port)
            }
            if(candidates.isEmpty())
                throw IllegalStateException("no usable AirPlay control endpoint; local=["+show(localsNow)+
                    "] iap2=["+show(direct)+"] bonjour=["+show(discovered)+"]")
            log("control candidates="+candidates.joinToString(","){it.source.toString()+"@"+it.address.hostAddress+":"+it.port}+" local="+show(localsNow))
            for(candidate in candidates){
                try{ return connectedSocket(candidate.address,candidate.port) }
                catch(t:Exception){
                    last=t
                    log("control connect failed source="+candidate.source+" at "+candidate.address.hostAddress+":"+candidate.port+" "+(t.message?:t.javaClass.simpleName))
                }
            }
            throw last?:IllegalStateException("control connect produced no attempt")
        }
        val controlSocket=openControlSocket()
        address=controlSocket.inetAddress
        val controlLocal=controlSocket.localAddress
        localMediaAddress=controlLocal
        // 控制连接可能落在准备阶段快照里没有的本端地址上——IPv6 link-local 常晚于 IPv4 形成，
        // 现场（DiPlay，2026-10-27）就是控制连接走了 fe80::、而 timing 表里只有 IPv4 的那一份，
        // 原来的 first{同族} 直接抛 NoSuchElementException。这里为该地址补一个 timing 端点；
        // mDNS 发布已在准备阶段完成，不重复发布。
        val timing=timings[controlLocal]?:run{
            log("timing endpoint missing for control source "+controlLocal.hostAddress+
                "(scope="+(controlLocal as? Inet6Address)?.scopeId+"); binding one now")
            startTiming(controlLocal).also{timings[controlLocal]=it}
        }
        val c=RtspClient(controlSocket.getInputStream(),controlSocket.getOutputStream(),{event(it)}).also{control->
            control.afterEventReply=::eventReplied
            control.abortTransport={controlSocket.close()}
            // Session failure closes resources and may join media workers. Never run that
            // blocking cleanup on the control reader itself.
            control.onFailure={t->scope.launch{channelFailure("control-transport",t)}}
            control.onExchange={exchange->
                log("Control exchange phase="+exchange.phase+" method="+exchange.method+" path="+exchange.path+" cseq="+exchange.cseq+
                    " bodyBytes="+exchange.bodyBytes+" elapsedMs="+exchange.elapsedMs+
                    " lockWaitMs="+exchange.lockWaitMs+
                    (exchange.commandType?.let{" command="+it}?:"")+
                    " responseCseq="+(exchange.responseCseq?:"none")+
                    " status="+exchange.status+" error="+exchange.error)
            }
        };client=c
        // 世代证据只来自 Bonjour 一侧：配对发生在 GET /info 之前，那时还没有 /info 的
        // protocolVersion/features（那一路证据在 SETUP 之后补记，见下面的 INFO 行）。
        val generation=AirPlayGenerations.estimate(AirPlayGenerationEvidence(
            protocolVersion=metadata.protocolVersion,sourceVersion=metadata.sourceVersion,
            features=AirPlayCapabilities(metadata.effectiveFeatures),services=metadata.services))
        log("AirPlay generation hint (diagnostic only): "+AirPlayGenerations.describe(generation)+
            "${"; evidence Bonjour protovers="}"+(metadata.protocolVersion?:"${"missing"}")+
            " srcvers="+(metadata.sourceVersion?:"${"missing"}")+
            " featuresLow64="+(metadata.effectiveFeatures?.let{"0x"+it.toString(16)}?:"${"missing"}")+
            "${" services="}"+metadata.services.sorted().joinToString(",").ifEmpty{"${"none found"}"})
        events.requireCurrent()
        // 车机只给 `features`、不给 TXT `pk` 时，CU/HAP 的 gate 必然为 null。但 features 里的
        // AES-128 MFi SAP v1（位26）说明对端准备走 `/auth-setup`：那条路只要**对端自己的**证书
        // 与公钥，不需要 Apple 私钥，所以这里在实验开关下给它一次机会，而不是直接判策略未决。
        val sapFallback=options.experiment&&metadata.effectiveFeatureEvidence.has(26)==true
        val inputs=bootstrap.securityInputs(securityBinding,metadata,networkReady=true,endpointValidated=true,
            pairedCapabilityGate=pairedCapabilityGate,experimentalIntegrityOnly=options.experiment,
            sourceContextCurrent=sourceContextCurrent(),
            sapFallbackAfterGate=sapFallback)
        log("iAP2 4301 peer declarations: "+(bootstrap.startSession?.startSessionPeerInfo?.toString()?:"unavailable")+
            "; preserved independently of Bonjour; no version-based authentication override")
        log("AirPlay security capabilities: "+AirPlaySecurityCapabilities(metadata.effectiveFeatures,metadata.effectiveFeatureEvidence).diagnostic())
        // 路由一旦走向 PAIRED，唯一的门槛就是 ExperimentalHapDiscovery.gate；它拒绝时只回 null，
        // 上层看到的是笼统的 SECURITY_POLICY_UNRESOLVED。这里把具体是哪一条没满足打出来。
        log("Experimental HAP gate="+ExperimentalHapDiscovery.lastDecision+
            " pi="+(metadata.pairingId?.length?.toString()?:"absent")+
            " pk="+(metadata.publicKey?.size?.toString()?:"absent")+
            " bonjour="+(metadata.endpoint?.hostAddress?:"none")+":"+(metadata.port?.toString()?:"-")+
            " iap2="+(bootstrap.endpoint?.address?:"none")+":"+(bootstrap.endpoint?.port?.toString()?:"-"))
        val securityRoute=SecurityRouter.route(inputs)
        log("AirPlay security route="+securityRoute+" controlVersion="+inputs.controlVersion+
            " endpointSource="+inputs.endpointSource+" startReady="+inputs.startReady)
        when(securityRoute){
            SecurityRoute.PAIRED->{
                val identifier=PairingIdentityBinding.identifier(bootstrap.startSession?.receiver,metadata.deviceId,metadata.pairingId)
                // 固件对 /info 与 Bonjour 的 deviceID 冲突是"保留原值并继续"，这里保持一致，只记录。
                if(PairingIdentityBinding.lastDeviceIdConflict)
                    log("receiver deviceID conflicts with Bonjour identity; keeping the advertised identity")
                if(options.experiment){
                    val pairingId=requireNotNull(metadata.pairingId)
                    val homeKit=HomeKit(c,identity,ExperimentalHapV11::srp)
                    shared=ExperimentalHapV11.authenticate(identifier,metadata.publicKey,
                        store.experimental.load(identity,bootstrap.receiver,pairingId),
                        setup={code->homeKit.setup(metadata.publicKey,code)},verify=homeKit::verify,
                        saveVerified={peer->events.requireCurrent();store.experimental.saveVerified(identity,bootstrap.receiver,pairingId,peer)})
                    log("Experimental HAP v11 verified; encrypted control/media active; AA/SAP root trust not established")
                }else{
                    val recordKey=store.recordKey(identity,identifier)
                    val previous=store.records.lookup(recordKey)
                    val setupCode=if(previous.status==PairRecordStatus.ABSENT)setupInputProvider?.input(securityBinding) else null
                    val action=try{SecurityRouter.select(inputs.copy(record=previous.status,peerHintPresent=identifier.isNotEmpty(),
                        setupProviderBound=setupCode?.isNotEmpty()==true))}catch(t:Throwable){setupCode?.fill(0);throw t}
                    val transaction=java.util.UUID.randomUUID().toString()
                    resources.own(AutoCloseable{store.records.rollback(recordKey,transaction)})
                    val homeKit=HomeKit(c,identity)
                    val target=if(action==SecurityAction.PAIR_VERIFY){
                        previous.peer?:securityFailure(SecurityReason.PAIR_STORE_CORRUPT)
                    }else{
                        val code=setupCode?:securityFailure(SecurityReason.SETUP_INPUT_UNAVAILABLE,phase="pair-setup")
                        val paired=try{homeKit.setup(metadata.publicKey,code)}finally{code.fill(0)}
                        if(!paired.identifier.contentEquals(identifier))securityFailure(SecurityReason.PEER_BINDING_CONFLICT)
                        store.records.stage(recordKey,transaction,paired)
                        paired
                    }
                    if(!target.identifier.contentEquals(identifier)||
                        (metadata.publicKey!=null&&!java.security.MessageDigest.isEqual(metadata.publicKey,target.public)))
                        securityFailure(SecurityReason.PEER_BINDING_CONFLICT)
                    shared=homeKit.verify(target)
                }
                controlSiri.reset()
            }
            SecurityRoute.SAP->{
                // 这里原本是空的：SAP 被选中后 mfi 永远是 null，SETUP 也就永远没有 et/ekey/eiv。
                // /auth-setup 的语义是"对端出示自己的 MFi 证书 + 用派生密钥包起来的签名"，手机侧
                // 只做验证并自选媒体密钥，不需要任何 Apple 私钥材料。
                if(options.experiment){
                    log("MFi/SAP route selected; POST /auth-setup (this build has no FairPlay /fp-setup)")
                    mfi=AccessoryAuth.mfi(c,AccessoryAuth.SapSignatureSchema.EXPERIMENTAL_RSA_PKCS1_SHA1_PUBLIC_KEYS_V1,log)
                    log("MFi/SAP material ready; SETUP will carry et=16 with ekey/eiv")
                }
            }
            SecurityRoute.CONTINUE_VERIFY,SecurityRoute.WAIT_START_PARAMETERS->securityFailure(SecurityReason.STALE_CONTEXT)
        }
        // v11 HAP uses its verified shared secret directly; SAP was an alternative,
        // not a second handshake after HAP. Formal/SAP-only paths still need material.
        // 实验 SAP 已经拿到 mfi 时不走这里的正式门槛：要求的是 Apple 根信任包，而 /auth-setup
        // 的签名只由对端证书自身的公钥验证，两者不是同一件事。
        if(mfi==null&&(!options.experiment||shared==null)){
            SecurityRequirements.requireSapSignatureSchema()
            SecurityRequirements.requireSapTrust()
        }
        events.authenticated(securityBinding)
        onDisplayNegotiation()
        val initial=CarPlaySetupRequests.control(
            deviceId=options.phoneMac,macAddress=options.phoneMac,name=options.carPlayName,
            model=Build.MODEL,osVersion=Build.VERSION.RELEASE,
            sessionUuid=java.util.UUID.randomUUID().toString(),
            timingPort=timing.takeIf{it.isBound&&!it.isClosed}?.localPort,
            encryption=mfi?.let{CarPlaySetupRequests.Encryption(16,it.wrapped,it.iv)})
        log("AirPlay identity osBuildVersion="+initial["osBuildVersion"]+" androidBuild="+Build.ID+
            " audioAAD=4-12; compatibility profile, actual device osName=Android osVersion="+Build.VERSION.RELEASE)
        log("Control SETUP profile=wireless-kind5 timingPort="+(initial["timingPort"]?:"absent")+
            " requestedFeatures=absent transportType=unmapped correlationProvider=unmapped"+
            "; no generic timingProtocol or screen-mirroring flag")
        // 连接 URL：固件在建立 HTTP 连接时生成一次（`0x24fdc93c0:47`，格式串 `"rtsp://%s/%llu"`），
        // 之后所有"不设自己 URL"的包都用它 —— 初始 SETUP（`'stup'`）、媒体 SETUP、RECORD（`'recr'`）、
        // TEARDOWN（`'tdwn'`）都是这一类。`/setup`、`/stream` 在固件里都不存在，是我们自己起的路径。
        val sessionPath=AirPlayConnectionUrl.create(address,java.security.SecureRandom().nextLong())
        connectionPath=sessionPath
        // 从这里交给异步读线程：加密流（若启用）已经替换完毕，之后所有响应都由它读掉，
        // 包括没人等的单向响应——不读会把对端的发送缓冲堆满，它就会卡在自己的 write 上。
        c.startReader()
        controlSocket.soTimeout=0
        // 建会话这几步值得等 60 s（固件 S-0121 的量级）；之后压回 10 s，流 SETUP 与命令不占这个预算。
        c.timeoutMs=controlReadTimeoutMs
        val setupSession=resources.own(AirPlaySetupSession(c,sessionPath,events::requireCurrent,log))
        setupLifecycle=setupSession
        val setup=try{setupSession.control(initial)}catch(t:RtspClient.StatusException){
            logSetupEncryptionRoute("initial",t.status);throw t
        }
        val info=try{setupSession.info()}finally{c.timeoutMs=10_000}
        // 版本一律读值、不假设：规格里的 210.81 只是本车所带 SDK 的版本，别的车机会是别的值。
        val infoProtocol=info["protocolVersion"]?.toString()
        val infoSource=info["sourceVersion"]?.toString()
        log("INFO peer versions protocolVersion="+(infoProtocol?:"${"missing"}")+" sourceVersion="+(infoSource?:"${"missing"}")+
            "；Bonjour protovers="+(metadata.protocolVersion?:"${"missing"}")+" srcvers="+(metadata.sourceVersion?:"${"missing"}"))
        if(metadata.sourceVersion!=null&&infoSource!=null&&metadata.sourceVersion!=infoSource)
            log("Bonjour and /info sourceVersion differ; recorded separately, existing security route unchanged")
        // /info arrives after authentication. Version/generation labels below are
        // diagnostic only; they neither overwrite discovery nor reselect security.
        // The subsequent audio/display capability consumers still use this response.
        val infoCapabilities=AirPlayCapabilities((info["features"] as? Number)?.toLong()?.toULong())
        val infoGeneration=AirPlayGenerations.estimate(AirPlayGenerationEvidence(
            protocolVersion=infoProtocol,sourceVersion=infoSource,
            features=infoCapabilities,services=metadata.services))
        log("INFO features="+(infoCapabilities.features?.let{"0x"+it.toString(16)}?:"${"missing"}")+
            "${" names="}"+infoCapabilities.names().joinToString(",").ifEmpty{"${"none"}"})
        // 位 42 = SUPPORTS_SCREEN_MULTI_CODEC（catplay §6，B 级；与 GM 样本"NvdecHW 支持 H.264/HEVC"呼应）。
        // 车机置了它只说明"它可能接受 HEVC"：本实现仍发 H.264，因为 HEVC 的帧封装在 docs 里没有证据
        // （gap-closure §1 只还原了 H.264 的 avcc 与帧 opcode）。要换编码得先补上那一段。
        if(infoCapabilities.has(AirPlayFeature.SUPPORTS_SCREEN_MULTI_CODEC))
            log("receiver advertises multi-codec (features bit 42); this build still sends H.264/AVCC, HEVC framing is a gap in docs")
        if(infoGeneration!=generation)
            log("generation hints differ: Bonjour="+generation+" /info="+infoGeneration+
                "; diagnostic only, security route remains "+securityRoute)
        receiverAudio=AudioCapabilities.parse(info["audioFormats"],log)
        audioOwnership.receiverAdvertised(receiverAudio.any{it.streamType==100})
        receiverAudioLatencies=AudioLatencyCapabilities.parse(info["audioLatencies"],log)
        log("INFO PCM16 capture candidates="+AudioCapabilities.capture(receiverAudio).size+"; microphone transport not established")
        log("INFO "+DisplayCapabilities.summary(info["displays"]))
        // 车机没有给出可用显示不是"会话失败"，而是"这台车机在这一层能力更少"：视频流与 HID 设备
        // 视频与触摸无从建立；独立按键、控制会话与事件通道照旧 —— docs §9.8 的降级表
        // 把这条写成"无 displays 字段 ⇒ 不开视频，仅音频"。返回 null 让调用方按无屏会话继续。
        val display=runCatching{DisplayCapabilities.select(info["displays"],localFps,log)}.getOrElse{t->
            log("receiver /info offers no usable display ("+t.javaClass.simpleName+"："+(t.message?:"no detail")+"); this session sets up no video stream or touch device")
            null
        }
        val uuid=display?.uuid
        val screenRequest=display?.let{CarPlaySetupRequests.screen(streamId,it.uuid)}
        var hidId:String?=null;var touch:HidTouch?=null
        var touchAmbiguous=false
        val hid=(info["hidDevices"] as? List<*>)?.filterIsInstance<Map<*,*>>().orEmpty()
        log("INFO HID entries="+hid.size)
        // 固件按 `/info.hidDevices` 全数组逐个建 HID client，没有条数上限；这里同样全量遍历，
        // 只在异常大时记一行，避免把"设备很多"当成错误。
        if(hid.size>64)log("HID device list is large ("+hid.size+"); processing all entries")
        for((hidIndex,item) in hid.withIndex()){
            val descriptor=item["hidDescriptor"] as? ByteArray
            if(descriptor==null){log("${"INFO HID #"}${hidIndex}${" no binary descriptor"}");continue}
            // 描述符只是布局元数据（不含报告内容或密钥）。调试构建把**每台**设备的都打出来：
            // 这样某台车机的触摸形态可以直接从日志里读，而不是只在解析失败时才看到第一台。
            if(false)descriptor.asList().chunked(64).forEachIndexed{chunk,bytes->
                log("HID #"+hidIndex+" descriptorHex["+chunk+"]="+bytes.joinToString(""){(it.toInt() and 255).toString(16).padStart(2,'0')})
            }
            log("${"INFO HID #"}${hidIndex}${" descriptorBytes="}${descriptor.size}${" uuidPresent="}"+(item["uuid"] is String)+" displayMatch="+(item["displayUUID"]==uuid)+" displayReferencePresent="+(item["displayUUID"]!=null))
            val deviceUuid=item["uuid"] as? String
            // 描述符里出现的**每个** usage page 都解析一遍：厂商会把按键放在 0x0C / 0x07 之外的页
            // （例如 Telephony 0x0B 上的通话键）。这样不靠"猜页号"，也不因某页失败就丢整个设备。
            val registeredKeys=hidKeys.register(deviceUuid,descriptor)
            // 记录需要武装的设备；武装命令在事件通道建立后统一发（固件把 hidSetInputMode 发到控制连接）。
            if(deviceUuid!=null&&deviceUuid.isNotEmpty())hidArmUuids.add(deviceUuid)
            val keyPageCount=registeredKeys.size
            registeredKeys.forEach{device->log("HID key page registered prefix="+device.prefix)}
            if(keyPageCount==0){
                log("${"INFO HID #"}${hidIndex}${" descriptor has no parseable key page; its reports are still listed while learning (device="}"+deviceUuid+")")
            }
            if(display==null||touchAmbiguous)continue
            if(uuid!=null&&item["displayUUID"]!=null&&item["displayUUID"]!=uuid)continue
            val parsed=try{HidTouch.parse(descriptor)}catch(t:Exception){
                log("${"INFO HID #"}${hidIndex}${" touch unsupported: "}"+t.javaClass.simpleName+" "+(t.message?:"")+if(false)" origin="+t.stackTrace.firstOrNull() else "")
                // HID descriptors contain layout metadata, not report contents or session keys.
                if(descriptor.size<=512)descriptor.asList().chunked(96).forEachIndexed{chunk,bytes->
                    log("${"INFO HID #"}${hidIndex}${" descriptorHex["}${chunk}${"]="}"+bytes.joinToString(""){(it.toInt() and 255).toString(16).padStart(2,'0')})
                }
                continue
            }
            if(touch!=null){touch=null;hidId=null;touchAmbiguous=true;log("Ambiguous touch devices disabled");continue}
            touch=parsed;hidId=deviceUuid
        }
        events.negotiated(securityBinding)
        if(display==null){
            openEventChannel(setup,c)
            setupRuntimeTunnel(setup.enabledFeatures)
            return null
        }
        val w=display.width;val h=display.height;val fps=display.fps
        val result=Screen(w,h,fps,uuid,hidId,touch);screen=result
        log("INFO screen "+w+"x"+h+" fps="+fps+" touch="+(touch!=null))
        openEventChannel(setup,c)
        // 24A446 creates the RCS during endpoint activation, after the initial
        // SETUP response and /info, not after media RECORD (0x251ae2228 and
        // 0x251ae7fc4 -> 0x251ae844c). Keep the event path available first.
        setupRuntimeTunnel(setup.enabledFeatures)
        // Independent re-check on a path that never touches PackageManager: parses the v3 block of
        // the installed archive instead, so hooking the certificate API does not cover this one.
        if(!true)error("No screen stream response")
        val stream=requireNotNull(screenRequest)
        // Log the same descriptor that is serialized below; do not keep a second
        // hand-written copy whose latency/presence can drift from the real request.
        fun valueType(v:Any?):String=when(v){
            null->"null"
            is Number->"number"
            is String->"string"
            is Map<*,*> -> "map"
            is List<*> -> "list"
            is ByteArray->"bytes["+v.size+"]"
            else->v.toString()
        }
        log("video SETUP keys="+stream.entries.joinToString(","){it.key+":"+valueType(it.value)})
        // 四条请求共用会话开始时生成的连接 URL（见 initial SETUP 处的说明）。
        val mediaPath=sessionPath
        val screenReply=try{setupSession.screen(stream)}
            catch(t:RtspClient.StatusException){logSetupEncryptionRoute("video",t.status);throw t}
        // 链路已可用于上行消息：打开 Now Playing 发布器，并把识别阶段暂存的 5000 订阅补上，
        // 否则车机的订阅在 Bootstrap 里被当未知命令丢弃，之后永远不会收到 5001。
        nowPlayingPublisher.linkReady(true)
        NowPlayingUpdate.pendingSubscription?.let{
            nowPlayingPublisher.command(NowPlayingUpdate.START,it)
            NowPlayingUpdate.pendingSubscription=null
            log("Now Playing subscription replayed from bootstrap")
        }
        video=connectedSocket(address,screenReply.dataPort)
        // RECORD 没有正文（第 06 章）；发空 plist 会让对端把已配置的流当成无输入。
        // URL 用连接 URL（固件 0x24fda58ec：'recr' 包不设自己的 URL，落到连接 URL
        // `rtsp://<host>/<u64>`），与控制连接同一条会话，不能用 "/"。
        setupSession.start()
        // 屏幕流保活帧（第 07 章 §2.6.1）：固件在同一个生产者里发 128 字节明文头、`0x04 = 2`、
        // 无正文；它是屏幕数据口上的独立存活信号，不是视频流量的备份。周期未在固件中还原，取 1 s。
        // 单次写失败只记日志：保活失败不该把整个会话判死，拆除由对端决定。
        scope.launch{
            while(isActive){
                delay(1000)
                runCatching{keepAliveFrame()}.onFailure{if(isActive)log("video keepalive failed: "+it.javaClass.simpleName)}
            }
        }
        // 心跳：每秒一行，把"谁停了"直接摆出来。卡顿时一眼就能看出是视频不再出帧、
        // 某条音频不再出包，还是控制面还有请求挂着（ctrlPending>0）。
        scope.launch{
            var loggedAt=System.nanoTime()
            var readLoggedAt:Long?=null
            var signature=""
            while(isActive){
                delay(1000)
                val now=System.nanoTime()
                videoDiagnostics.poll()
                if(readLoggedAt?.let{now-it>=5_000_000_000L}!=false){
                    readLoggedAt=now
                    client?.let{log("Control read "+it.readDiagnostics(now))}
                }
                fun age(ns:Long)=if(ns==0L)"-" else ((now-ns)/1_000_000).toString()+"ms"
                val pending=client?.pendingCount?:0
                val audio=audioTransports.entries.sortedBy{it.key}.joinToString(","){ (cat,t)->
                    "c"+cat+"="+t.packetsSent+"/"+age(t.lastPacketNanos)
                }
                // 只看计数（不看时延）：任一计数前进就每秒记，全都停住则降到每 5 s——
                // 这样"停了"既有最后一条活跃记录，也不会把 2000 行历史刷没。
                val next=videoFrames.toString()+"/"+pending+"/"+
                    audioTransports.entries.sortedBy{it.key}.joinToString(","){ it.key.toString()+":"+it.value.packetsSent }
                if(next!=signature||now-loggedAt>=5_000_000_000L){
                    signature=next;loggedAt=now
                    val control=client
                    log("hb video="+videoFrames+"/"+age(lastVideoNanos)+" ctrlPending="+pending+
                        " ctrlReader="+(control?.readerRunning?:false)+
                        " ctrlRxAge="+age(control?.lastMessageNanos?:0L)+
                        " ctrlResponseAge="+age(control?.lastResponseNanos?:0L)+
                        " ctrlTxAge="+age(control?.lastWriteNanos?:0L)+
                        " ctrlOneWayUnanswered="+(control?.oneWayPendingCount?:0)+" audio["+audio+"]")
                }
            }
        }
        return result
    }
    /** 控制会话的事件通道、初版资源模式上报与 feedback 心跳 —— **与有没有屏幕无关**：无屏会话同样要有
     *  地方应答车机的 setNightMode / requestUI / iAPSendMessage，否则车机面对的是一个不说话的端点。
     *  两种结局（有屏 / 无屏）都调用它。 */
    private fun openEventChannel(setup:AirPlaySetupSession.ControlReply,c:RtspClient){
        val eventPort=setup.eventPort
        if(eventPort!=null){
            val events=connectedSocket(address,eventPort);events.soTimeout=0
            var ei:InputStream=events.getInputStream();var eo:OutputStream=events.getOutputStream()
            shared?.let{
                ei=RecordInput(ei,Crypto.hkdf(it,"Events-Salt","Events-Write-Encryption-Key"))
                eo=RecordOutput(eo,Crypto.hkdf(it,"Events-Salt","Events-Read-Encryption-Key"))
            }
            scope.launch {
                try{while(isActive){val r=RtspWire.read(ei);require(r.status==0){"${"Unexpected response on event connection status="}${r.status}"};val (status,b)=event(r,eventSiri);if(!r.headers.containsKey("cseq"))log("Event notification omitted CSeq; replying without synthetic sequence");RtspClient.reply(eo,r,status,b,allowMissingCSeq=true);eventReplied(r,status,b)}}
                catch(t:Exception){if(isActive)channelFailure("event-reader",t)}
            }
        }else log("SETUP supplied no event channel; control request handler remains active")
        // 低功耗保活（第 13 章 §2.4b）：SETUP 响应带 keepAlivePort 时，另建一条 UDP 流，
        // 按 10 s 发 4 字节包 [type=0x00][len=0x04][2 字节未约束]。
        // 固件：端口读取 _apsession_ensureControlStreamSetup 0x251b5cfe0:66-77；
        //       周期 ___lowPowerKeepAliveController_Resume_block_invoke 0x24fe36730（10s/leeway 1s）；
        //       包布局 _APTPackageLowPowerKeepAliveCreateWithBBuf 0x24fdca3e8 + 0x24fdca744。
        val keepAlivePort=setup.keepAlivePort
        if(keepAlivePort!=null){
            log("low-power keepalive port="+keepAlivePort+" interval=10s")
            scope.launch{
                val socket=try{java.net.DatagramSocket()}catch(t:Exception){channelFailure("keepalive-socket",t);return@launch}
                resources.own(AutoCloseable{socket.close()})
                try{
                    while(isActive){
                        // 类型 0x00、总长 0x04；后两字节固件不校验，置 0。
                        runCatching{socket.send(java.net.DatagramPacket(byteArrayOf(0,4,0,0),4,address,keepAlivePort))}
                            .onFailure{if(isActive)log("low-power keepalive send failed: "+it.javaClass.simpleName)}
                        delay(10_000)
                    }
                }catch(t:Exception){if(isActive)channelFailure("keepalive",t)}
            }
        }else{
            log("low-power keepalive absent (SETUP response has no keepAlivePort)")
        }
        c.plist("POST","/command",modes())
        // HID 武装（第 10 章 §2.6）：固件把 `hidSetInputMode` 发到**传输控制流**，字段是
        // `{uuid, hidInputMode}`；没有武装过的触摸设备，报告可能根本不来。这里逐设备发一次。
        val armTargets=hidArmUuids.distinct()
        var armed=0
        var unsupported=false
        for(uuid in armTargets){
            if(unsupported)break
            val body=mapOf("type" to "hidSetInputMode","params" to mapOf("uuid" to uuid,"hidInputMode" to 0))
            val response=runCatching{c.request("POST","/command",Plist.encode(body))}.getOrElse{
                log("hidSetInputMode transport failure for "+uuid+": "+it.javaClass.simpleName);null
            }?:continue
            when{
                // 501 = 接收端没有实现该命令（例如 RustCarPlay）。不是错误：HID 报告是否可用
                // 取决于对端广告的描述符，不取决于这次“武装”。
                response.status==501->{
                    unsupported=true
                    log("receiver does not implement hidSetInputMode (501); HID reports rely on advertised descriptors")
                }
                response.status in 200..299->armed++
                else->log("hidSetInputMode rejected status="+response.status+" uuid="+uuid)
            }
        }
        if(armed>0)log("HID input mode armed for "+armed+" device(s)")
        // Standard keepalive waits for a reply; low-power UDP above is independent.
        // A control deadline invalidates/aborts the old client before session cleanup,
        // so teardown cannot spend another timeout writing to an already dead socket.
        scope.launch{try{
            CarPlayControlRequests.keepAlive(c)
        }catch(t:Exception){if(isActive)channelFailure("feedback",t)}}
    }
    private fun modes()=mapOf("type" to "modesChanged","params" to screenModes.params())
    /** Compact view of an unsupported command: binary payloads are described, never printed. */
    private fun summarize(values:Map<*,*>):String=values.entries.sortedBy{it.key.toString()}
        .joinToString(",",prefix="{",postfix="}"){
            val value=it.value
            it.key.toString()+"="+when(value){
                is ByteArray->"bytes["+value.size+"]"
                is Map<*,*>->summarize(value)
                is List<*>->"list["+value.size+"]"
                else->value.toString().take(64)
            }
        }
    /** Hex preview of a report, for the observation lines: enough to recognise a device's format later. */
    private fun hex(data:ByteArray,limit:Int)=data.take(limit).joinToString(""){(it.toInt() and 255).toString(16).padStart(2,'0')}
    private fun eventReplied(request:RtspMessage,status:Int,body:ByteArray){
        if(status!=200||body.isEmpty())return
        val command=Plist.dictionary(request.body)
        if(command["type"]!="changeModes")return
        val reply=Plist.dictionary(body)
        // 拒绝模式请求时 command() 回的是 `{status:1}`，没有 params。转发一个 params=null 的
        // modesChanged 会被车机判为 422（实车 carvex-log-20261102-185758：553B 全 200，91B 那次 422），
        // 而 422 又会连带把整条会话判死。拒绝本身已经由事件应答的 status 表达了，这里不再补发。
        val params=reply["params"]?:run{
            log("changeModes declined; no modesChanged notification sent (reply carries status="+reply["status"]+")")
            return
        }
        val notification=mapOf("type" to "modesChanged","params" to params)
        if(modeOutput.trySend(notification).isFailure)throw IOException("Modes notification queue unavailable")
    }
    private fun event(r:RtspMessage,replays:SiriRequests=controlSiri):Pair<Int,ByteArray> =
        events.dispatch{authenticatedEvent(r,replays)}
    private fun authenticatedEvent(r:RtspMessage,replays:SiriRequests):Pair<Int,ByteArray>{
        val path=r.first.split(' ').getOrNull(1)
        if(path=="/feedback")return 200 to byteArrayOf()
        if(path!="/command")return 400 to byteArrayOf()
        log("RTSP event method="+r.first.substringBefore(" ")+" command="+(path=="/command")+" cseqPresent="+r.headers.containsKey("cseq")+" bodyBytes="+r.body.size)
        // Business dispatch is open only after verification and capability registration. A rejected command
        // body is not a broken transport and must receive a reply on this connection.
        return CommandBoundary.handle(r.body,{log(it)}){decoded->
            if(decoded["type"]=="requestSiri"){
                val params=decoded["params"] as? Map<*,*>
                val raw=params?.get("siriAction")
                val result=replays.handle(r)
                log("RTSP Siri action="+raw+" valueType="+(raw?.javaClass?.simpleName?:"null")+
                    " topLevelAction="+decoded["siriAction"]+" triggerTimestamp="+params?.get("siriTriggerTimestamp")+
                    " cseq="+r.headers["cseq"]+" eventConnection="+(replays===eventSiri)+" status="+result.first)
                result
            } else command(decoded)
        }
    }
    private fun command(p:Map<String,Any?>):Pair<Int,ByteArray> {
        // Every event the car sends leaves one observable line: for a message that is not mapped yet the
        // payload is the only evidence a later mapping can be built on, whether or not a branch below
        // handles its type.
        log("OBSERVE rtsp command type="+(p["type"] as? String)+" payload="+summarize(p))
        return synchronized(commandLock){when(p["type"]){
            "changeModes"->{
                val params=p["params"] as? Map<*,*>?:return 400 to byteArrayOf()
                val proposed=screenModes.propose(params)?:return 200 to Plist.encode(mapOf("status" to 1))
                val resource=screenModes.requestedResource(params)?:return 400 to byteArrayOf()
                if(resource==1&&screenOwnership?.invoke(proposed.owner==1)!=true)return 200 to Plist.encode(mapOf("status" to 1))
                screenModes.commit(proposed)
                val changedAudio=resource==2
                if(changedAudio)audioOwnership.receiverDeclaredModes(proposed)
                log("${"Resource modes applied resource="}${resource}${" screenOwner="}${proposed.owner}${" audioOwner="}${proposed.audioOwner}${"; receiver acceptance pending"}")
                200 to Plist.encode(mapOf("status" to 0,"params" to screenModes.params()))
            }
            "forceKeyFrame"->{
                val requested=(p["params"] as? Map<*,*>)?.get("uuid")
                // 固件：未知非空 uuid 不回退、返回成功但不重启（ch10 §2.9）。
                if(requested!=null&&(requested !is String||requested!=screen?.uuid))
                    log("forceKeyFrame for unknown display uuid; acknowledged without restart")
                else requestKeyFrame?.invoke()
                200 to byteArrayOf()
            }
            "iAPSendMessage"->{
                val params=p["params"] as? Map<*,*>?:return 400 to byteArrayOf()
                val data=params["data"] as? ByteArray?:return 400 to byteArrayOf()
                // 200 acknowledges queue acceptance, not completed business handoff.
                val accepted=options.experiment&&runtime.accept(data)
                if(iapEventSeen.getAndIncrement()<5)
                    log("iAP event rx len="+data.size+" accepted="+accepted+" tunnel="+(tunnel!=null))
                (if(accepted)200 else 400) to byteArrayOf()
            }
            "hidSendReport"->{
                val s=screen
                val data=p["hidReport"] as? ByteArray
                val reportUuid=p["uuid"] as? String
                // 通道确认：本会话第一条 HID 报告先留一行（不管认不认识、有没有屏）。
                // 没有这一行，就是"报告根本没进来"——这是分辨"车机没发"与"我们没认"的分界线。
                if(hidReportsSeen.getAndIncrement()==0L)
                    log("OBSERVE hid report first uuid="+reportUuid+" len="+(data?.size?:-1))
                // 每条报告都留原始字节：此前只打首条，所以"车机到底发了哪些索引值"无从判断。
                // 车机侧的发送端只声明了 Shuffle/Repeat（固件 sendCarPlayMusicCmd 仅引用
                // kHIDMediaButtonIndexShuffle / kHIDMediaButtonIndexRepeat），
                // 实测字节才能确认"上一曲/下一曲"是否真的走 HID，以及它用哪个索引。
                log("OBSERVE hid report uuid="+reportUuid+" len="+(data?.size?:-1)+
                    " hex="+(data?.let{hex(it,32)}?:"-"))
                // Report validation is independent of display availability.
                if(data==null){
                    logOnce("hid:bad-report:"+reportUuid,"OBSERVE hid report not-bytes uuid="+reportUuid)
                    return 400 to byteArrayOf()
                }
                val result=hidKeys.report(reportUuid,data,
                    s?.takeIf{it.touchId==reportUuid}?.touch,s?.width?:1,s?.height?:1,input,carPlayKey)
                if(result.recognized){
                    log("OBSERVE HID dispatch uuid="+reportUuid+" touch="+result.touchMatched+" keys="+
                        result.decoded.joinToString("|"){(prefix,usages)->prefix+":"+usages.sorted().joinToString(","){it.toString(16)}}+
                        " forwarded="+result.accepted+" decoderMisses="+result.decodeFailures)
                    // The report is supported and decoded. Local consumer availability is a
                    // separate delivery result, not an unsupported wire command.
                    return 200 to byteArrayOf()
                }
                // An unrecognised device or report is logged with its bytes: the car decides what it
                // sends, so this line is the only evidence of it before someone presses that key.
                // 同时把它记进监听列表，学习模式里能看到"哪个设备在发"（方向键可能就在这里）。
                // 首见才记：未知设备的报告可能很频繁。
                val unknownKey="hid:unknown:"+reportUuid+"|"+data.size+"|"+(data.lastOrNull()?.toInt()?.and(255)?:0)
                logOnce(unknownKey,"OBSERVE hid unknown uuid="+reportUuid+" bytes="+data.size+" hex="+hex(data,48))
                // 但仍要按"内容"再放行一次：首见去重会把同一设备后续**不同**的报告吞掉，
                // 而"按键不来"恰恰要看的就是那一串变化（例如 03→00→04→00）。
                if(hidUnknownObserved.add(unknownKey))
                    log("OBSERVE hid unknown repeat uuid="+reportUuid+" hex="+hex(data,48))
                BridgeStore.recordCarPlayKey(CarPlayEvents.unknownHid(reportUuid))
                return 400 to byteArrayOf()
            }
            // Car -> phone requests observed in the receiver-side reference material. The fields are
            // confirmed there; what they map to locally is decided by the caller, so a request that
            // nobody acts on is answered 501 rather than silently accepted.
            "requestUI"->{
                val nested=p["params"] as? Map<*,*>
                val url=(nested?.get("url") as? String)?.takeIf{it.isNotBlank()}
                    ?:(nested?.get("urls") as? List<*>)?.filterIsInstance<String>()?.firstOrNull()
                    ?:(p["url"] as? String)?.takeIf{it.isNotBlank()}
                // 固件：params/url 可缺，无 URL 仍通知并成功（ch10 §2.5）。
                if(url==null){
                    log("RTSP requestUI without url; acknowledged without action")
                    return 200 to byteArrayOf()
                }
                log("${"RTSP requestUI url="}${url}")
                (if(uiRequest(url))200 else 400) to byteArrayOf()
            }
            "setNightMode"->{
                val nested=p["params"] as? Map<*,*>
                val mode=(p["nightMode"] as? Number)?.toInt()?:(nested?.get("nightMode") as? Number)?.toInt()
                    ?:return 400 to byteArrayOf()
                log("${"RTSP setNightMode mode="}${mode}")
                nightMode(mode)
                200 to byteArrayOf()
            }
            "deviceOfferFocus"->{
                val nested=p["params"] as? Map<*,*>
                fun field(key:String)=(p[key] as? Number)?.toInt()?:(nested?.get(key) as? Number)?.toInt()?:0
                log("RTSP deviceOfferFocus origin=("+field("originXPixels")+","+field("originYPixels")+
                    ") size="+field("widthPixels")+"x"+field("heightPixels"))
                focusOffer()
                200 to byteArrayOf()
            }
            // The voice request carries the action and its trigger time; what the action's values mean
            // is decided by the caller, so an unreadable one is answered 501 rather than guessed at.
            // An event nobody handles is logged with its payload: the field names and shapes the car
            // actually sends are what a later mapping has to be built on.
            else->{log("Unsupported event "+(p["type"] as? String)+" payload="+summarize(p));400 to byteArrayOf()}
        }
    }
    }
    suspend fun createMediaAudio(format:PcmFormat):NegotiatedAudioTransport=createAudio(1,format)
    fun bootstrapAudioPlan():List<CarPlayAudioPlan.Selection> =
        CarPlayAudioPlan.bootstrapPlayback(receiverAudio,PcmFormat(48000,2)){format->
            runCatching{AudioCodecSupport.encoders(format).isNotEmpty()}.getOrDefault(false)
        }
    /** Who owns main audio right now, for status and logs. */
    fun mainAudioOwner()=audioOwnership.state()
    /** 会话是否具备车机麦克风能力（/info 里 type100 的输入掩码），与“流是否已建立”无关。
     *  CarWith 询问“车机麦克风可用吗”时回答的是能力；流仍按需在语音开始时建立。 */
    fun microphoneInputAvailable():Boolean =
        receiverAudio.any{entry->entry.streamType==100&&entry.inputMask!=null&&entry.inputMask!=0L}
    /** Cache real metadata until this Link subscribes. False means no selected update was sent. */
    fun nowPlaying(track:NowPlayingUpdate.Track,replaceMedia:Boolean=false):Boolean =
        nowPlayingPublisher.update(track,replaceMedia)
    /** Firmware message 5201. The caller must have an active 5200 subscription. */
    fun navigationRouteUpdate(values:List<Pair<Int,ByteArray>>):Boolean =
        if(!NavigationGuidance.active) false
        else runtime.send(0x5201,NavigationGuidance.routeGuidanceUpdate(values))
    fun navigationManeuverUpdate(values:List<Pair<Int,ByteArray>>):Boolean =
        if(!NavigationGuidance.active) false else runtime.send(0x5202,NavigationGuidance.maneuverUpdate(values))
    fun navigationLaneUpdate(values:List<Pair<Int,ByteArray>>):Boolean =
        if(!NavigationGuidance.active) false else runtime.send(0x5204,NavigationGuidance.laneGuidance(values))
    /** Prefer implemented wireless codec from the announcement; PCM is an explicit compatibility fallback. */
    suspend fun createAudio(category:Int,source:PcmFormat,onMicrophone:(suspend (ByteArray,PcmFormat)->Boolean)?=null):NegotiatedAudioTransport = withContext(Dispatchers.IO){
        val began=System.nanoTime()
        log("Audio setup gate category=$category phase=wait monoNs=$began")
        audioSetupLock.lock()
        try{
            log("Audio setup gate category=$category phase=entered waitMs="+(System.nanoTime()-began)/1_000_000)
            createAudioLocked(category,source,onMicrophone)
        }finally{audioSetupLock.unlock()}
    }
    private suspend fun createAudioLocked(category:Int,source:PcmFormat,onMicrophone:(suspend (ByteArray,PcmFormat)->Boolean)?):NegotiatedAudioTransport {
        check(!stopping){"Audio session stopping"}
        val wantsInput=category==3&&onMicrophone!=null
        val selected=CarPlayAudioPlan.selectPlayback(receiverAudio,category,source,
            requireDuplex=wantsInput,supportsCapture={format->
                format.codec==CarPlayAudioPlan.Codec.PCM16||runCatching{AudioCodecSupport.decoders(format).isNotEmpty()}.getOrDefault(false)
            },excludedRoutes=if(category==2)rejectedPromptRoutes else emptySet()){format->
            val encoders=runCatching{AudioCodecSupport.encoders(format)}.getOrDefault(emptyList())
            log("Audio category="+category+" local "+format.codec+"/"+format.rate+"/"+format.channels+" encoders="+encoders.joinToString())
            encoders.isNotEmpty()
        }?:throw UnsupportedOperationException("No implemented local/receiver audio intersection for category="+category)
        val route=selected.route
        val candidate=selected.format
        log("Audio category="+category+" selected type="+route.type+"/"+route.purpose+" codec="+candidate.codec+"/"+candidate.rate+"/"+candidate.channels)
        if(category==1){
            // 抓包诊断：媒体优先 102/media；102 无格式或本机无编码器时回退 100/media。
            // 有的车机按 type 而不是 audioType 分类，回退到 100 时会把音乐显示成语音。
            val advertised102=CarPlayAudioPlan.playbackCandidates(receiverAudio,CarPlayAudioPlan.media,source)
            val encodable102=advertised102.count{format->
                runCatching{AudioCodecSupport.encoders(format).isNotEmpty()}.getOrDefault(false)}
            log("Audio category=1 route check: type102 advertised="+advertised102.size+" encodable="+encodable102+
                " selected="+route.type+"/"+route.purpose+
                (if(route.type==100)" (media fell back to type100; receiver may classify by type)" else ""))
        }
        val control=client?:error("No control session")
        val format=candidate.decodedPcm
        val id=Crypto.random.nextLong().and(Long.MAX_VALUE).coerceAtLeast(1)
        // 先固定下来：`shared` 是可变的类属性，Kotlin 不对它做智能转换。
        val homeKitSecret=shared
        // MFi/SAP 路线没有 HomeKit 的 shared secret，但屏幕流已经证明这条路的 KDF 可用：
        // SHA512("AirPlayStreamKey"‖dec(id)‖K)[0:16]（04-kdf，A-46）。
        // 音频沿用同一公式，按流 id 各建一个 AES-CTR 上下文；计数器必须跨包连续，
        // 不能每包重建（06-frame-crypto-aes R-6.1）。HAP 路线仍走原来的 HKDF，行为不变。
        //
        // 诊断开关：`options.audioPlaintext` 时 cipher 传 null，12 字节头照旧、payload 明文直发。
        // 依据是接收端固件在 stream+0xa0 没有密码器时直接放行明文并返回成功
        // （AirPlaySender 0x2519c0acc → 返回 0），所以"我们发了密文、车机按明文解"是可能的状态，
        // 而它在日志上完全无声无息。这一步只用来判定密码层，不改变默认行为。
        val sapAudio=if(homeKitSecret==null){
            // 实车回归的分界线就在这里。2026-11-02 18:13 那次画面正常（只差声音）时，这个分支
            // 抛的就是下面这句，三条音频全部 3ms 内失败，视频随后稳定 30fps；
            // 换成"能建就建"之后音频 SETUP 全部 200，但 CarWith 的虚拟屏从此一帧不提交，
            // 车机上只剩一个图标。所以默认走回这条，把画面先拿稳。见 Options.audioEnabled。
            if(!options.audioEnabled)
                throw UnsupportedOperationException("Audio RTP profile requires HomeKit; MFiSAP audio is not established")
            // 帧密码按 A-44 的 type/G 分派表选，不是全局一种（表见 SapAudioCrypto）：
            //   type 96/102/103 在 G=0 → CBCFrame；type 110/111 在 G=0 → CTR。
            // 本会话是 MFi/SAP（Legacy provider，G=0）——视频用 CTR 能出画面就是 G=0 的实证，
            // 因为 A-44 里 110/111 只在 G=0 给 CTR。媒体音是 MainHighAudio(102)，
            // 帧密码必须是 CBCFrame；此前用 CTR 表现为包在发、日志全绿、全程无声。
            // 本次真机实验只动这一处变量。raw100/101 在 G=0 下 A-44 说是「不建密码器」，
            // 属于下一次单独验证，本次保持原样，不一起改。
            // 先固定下来：`mfi` 是可变的类属性，Kotlin 不对它做智能转换（同 `shared`）。
            val mfiMaterial=mfi
            val built=when{
                options.audioPlaintext->SapAudioCrypto.plain()
                mfiMaterial==null->throw UnsupportedOperationException("Audio RTP profile requires HomeKit or MFi/SAP key material")
                route.type==102->{
                    val (frameKey,frameIv)=mfiMaterial.streamFrameKeyIv(id)
                    SapAudioCrypto.cbcFrame(frameKey,frameIv)
                }
                else->SapAudioCrypto.ctr(mfiMaterial.streamCipher(id))
            }
            built
        }else null
        if(sapAudio?.plaintext==true)
            log("Audio category="+category+" MFi/SAP diagnostic: payload sent as PLAINTEXT; this run cannot confirm the AES-CTR wire format")
        val key=if(homeKitSecret!=null)Crypto.hkdf(homeKitSecret,"DataStream-Salt$id","DataStream-Output-Encryption-Key")
            else ByteArray(0)
        // 麦克风只有 HomeKit 的输入密钥方向实现了。SAP 下不声明 input：宁可少一路麦克风，
        // 也不发一个用错密钥的 dataPort 去换回车机侧的静默失败。
        val duplex=homeKitSecret!=null&&wantsInput&&CarPlayAudioPlan.duplexSupported(receiverAudio,route,candidate)
        if(wantsInput&&!duplex)
            log("Audio category="+category+" microphone not offered: MFi/SAP input key direction is not implemented")
        val streamKey=audioKey(route.type,route.purpose)
        val udp=DatagramSocket(null)
        val rtcp=try{DatagramSocket(null)}catch(t:Throwable){udp.close();throw t}
        val lease=try{audioStreams.reserve(route.type,route.purpose,id)}catch(t:Throwable){udp.close();rtcp.close();throw t}
        var setupAttempted=false
        var microphone:CarPlayMicReceiver?=null
        val owned=java.util.concurrent.atomic.AtomicBoolean(false)
        val released=java.util.concurrent.atomic.AtomicBoolean(false)
        val transportRef=java.util.concurrent.atomic.AtomicReference<NegotiatedAudioTransport?>()
        suspend fun finishClose(needsTeardown:Boolean){
            // A cancelled SETUP must still return its main-audio reservation and finish
            // the closing barrier. RTSP retains its own bounded response timeout.
            withContext(NonCancellable){
            var closeError:Throwable?=null
            try{
                // Serialize ownership bookkeeping, but never hold this lock while waiting for TEARDOWN.
                if(category==1||category==3){
                    audioOwnershipLock.lock()
                    try{
                        if(owned.compareAndSet(true,false)&&mainAudioUsers.decrementAndGet()==0&&!stopping)audioOwnership.releaseOwn()
                    }finally{audioOwnershipLock.unlock()}
                }
            }catch(t:Throwable){closeError=t}
            try{
                if(needsTeardown&&!stopping)control.plist("TEARDOWN",connectionPath?:"/",mapOf("streams" to listOf(AudioTeardownDescriptor.forType(route.type))))
            }catch(t:Throwable){if(closeError==null)closeError=t else closeError.addSuppressed(t)}
            finally{
                transportRef.get()?.let{audioTransports.remove(category,it)}
                if(closeError==null)audioStreamIds.remove(streamKey)
                audioStreams.completeClose(lease,closeError)
            }
            closeError?.let{log("Audio category="+category+" release not confirmed: "+it.javaClass.simpleName+"; same-type replacement suppressed")}
            }
        }
        try{
            // Count a main-audio reservation before SETUP, not only after its first PCM packet.
            // Otherwise an older stream can release ownership while this one is still starting.
            if(category==1||category==3){
                val ownershipBegan=System.nanoTime()
                log("Audio ownership category=$category phase=wait monoNs=$ownershipBegan")
                audioOwnershipLock.lock()
                try{
                    log("Audio ownership category=$category phase=entered waitMs="+(System.nanoTime()-ownershipBegan)/1_000_000)
                    audioOwnership.takeOwn();mainAudioUsers.incrementAndGet();owned.set(true)
                    log("Audio ownership category=$category phase=reserved users="+mainAudioUsers.get())
                }
                finally{audioOwnershipLock.unlock()}
            }
            Bootstrap.bind(network,udp,options.bindNetwork,::pinningRefused);Bootstrap.bind(network,rtcp,options.bindNetwork,::pinningRefused)
            val localAddress=requireNotNull(localMediaAddress)
            udp.bind(InetSocketAddress(localAddress,0));rtcp.bind(InetSocketAddress(localAddress,0))
            val retransmit=AudioRtpRetransmit(rtcp,address,log)
            // Prepare the real input socket/decoder before advertising input=true.
            // duplex 在 SAP 下恒为 false，所以这里的 secret 只会在 HAP 路线被解引用。
            if(duplex){
                val secret=requireNotNull(shared){"Microphone path requires a HomeKit shared secret"}
                microphone=CarPlayMicReceiver.create(udp,address,0,
                    key=Crypto.hkdf(secret,"DataStream-Salt$id","DataStream-Input-Encryption-Key"),
                    alternateKey=key,format=candidate,controlPort=null,
                    output=requireNotNull(onMicrophone),log=log)
                check(requireNotNull(microphone).ready){"Microphone receiver stopped before SETUP"}
            }
            // Offer the implemented route capability, without a diagnostic override.
            // Writers receive only the validated result, never a second local override.
            // SAP 只实现了“主块 + CTR”，RED 的历史块封装还是 AEAD 那一套。
            // 不提供做不到的能力，比协商成功后再让接收端拿到解不开的冗余块要好。
            val offeredRed=if(sapAudio!=null)false else AudioRedundancyPolicy.offerFor(route)
            val description=AudioSetupDescription.playback(route,candidate,id,rtcp.localPort,
                inputPort=udp.localPort.takeIf{duplex},offerRedundancy=offeredRed)
            log("Audio latency category="+category+" requestedMs="+description["audioLatencyMs"]+
                " receiver="+AudioLatencyCapabilities.summary(receiverAudioLatencies,route.type,route.purpose)+
                "; declared values, not measured speaker delay")
            log("Audio SETUP fields category="+category+" spf="+(description["spf"]?:"absent")+
                " controlPort="+(description["controlPort"]?:"absent")+
                " input="+(description["input"]?:"absent")+" dataPort="+(description["dataPort"]?:"none")+
                " offeredRED="+offeredRed)
            log("${"CarPlay audio SETUP category="}${category}${" type="}${route.type}${"/"}${route.purpose}${" codec="}${candidate.codec}${" "}${format.rate}${"/"}${format.channels}${"; receiver raw-AU profile"}")
            // 音频流与屏幕流同一条连接 URL（固件：'stup' 不设自己的 URL，回落到连接 URL）。
            setupAttempted=true
            val response=control.plist("SETUP",connectionPath?:"/",mapOf("streams" to listOf(description)))
            check(!stopping){"Audio session stopping"}
            val stream=(response["streams"] as? List<*>)?.filterIsInstance<Map<*,*>>()?.singleOrNull{(it["type"] as? Number)?.toInt()==route.type}
                ?:error("${"No unique audio SETUP response for category="}${category}")
            log("Audio SETUP accepted category="+category+" type="+route.type+" dataPort="+stream["dataPort"]+
                " controlPort="+stream["controlPort"]+" RED="+stream["supportsRTPPacketRedundancy"]+
                " highAccuracy="+stream["supportsHighAccuracyTimestamps"])
            // 把应答里这条流的**全部**字段打一次。以前只挑了 4 个：如果车机在返回里把
            // audioFormat（ct/spf/ch/ss）换成了别的，我们看不见，而"按自己的格式发"
            // 恰好就是那种"日志全绿、车上全程静音"的失败。
            log("Audio SETUP reply category="+category+" "+stream.entries.sortedBy{it.key.toString()}
                .joinToString(" "){e->when(val v=e.value){
                    is ByteArray->e.key.toString()+"=bytes["+v.size+"]"
                    is Map<*,*>->e.key.toString()+"={"+v.entries.joinToString(","){"${it.key}=${it.value}"}+"}"
                    is List<*>->e.key.toString()+"=["+v.joinToString(",")+"]"
                    else->e.key.toString()+"="+v}})
            // An offer in our request is not an enabled mode. Firmware treats a missing
            // or false reply as ordinary cumulative-sample timing for main/alt output.
            // 音量命令不挡 SETUP 热路径：它只是收端增益，晚几十毫秒到没有差别，
            // 但在这里做一次额外 RTSP 往返会把后面的建流排在它后面。RtspClient 按 seq
            // 分发响应、写侧只串行化 write，所以并发发一条 SET_PARAMETER 是安全的。
            if(category==1)scope.launch{announceVolume(control)}
            val receiverHighAccuracyTimestamps=AudioRtpTimestampClock.receiverSupportsHighAccuracy(stream)
            val clockFields=listOf("sampleTime","timestamp","timestampRawNs","audioLatencyMs")
                .mapNotNull{name->(stream[name] as? Number)?.let{"$name=$it"}}.joinToString(",").ifEmpty{"none"}
            log("Audio clock category="+category+" setupFields="+clockFields+
                "; local sample pacing only, receiver playback clock/flush not established")
            val port=(stream["dataPort"] as? Number)?.toInt()?:error("Missing audio dataPort")
            require(port in 1..65535)
            require(stream["streamConnectionID"]==null||(stream["streamConnectionID"] as? Number)?.toLong()==id){"Audio stream identity mismatch"}
            stream["audioType"]?.let{require(it==route.purpose){"Audio purpose mismatch"}}
            (stream["streamID"] as? Number)?.toLong()?.let{audioStreamIds[streamKey]=it
                log("Audio streamID category="+category+" type="+route.type+"/"+route.purpose+" id="+it)}
            microphone?.updateControlPort((stream["controlPort"] as? Number)?.toInt()?.takeIf{it in 1..65535})
            // A contradictory success is not an explicit RTSP refusal: clean up
            // this stream using the existing TEARDOWN path, without a route retry.
            val redEnabled=try{
                AudioRedundancyPolicy.responseEnables(route,stream,
                    offered=description["supportsRTPPacketRedundancy"]==true)
            }catch(t:UnsupportedOperationException){
                log("Audio category="+category+" type="+route.type+" RED negotiation rejected offeredRED="+offeredRed+
                    " responseRED="+stream["supportsRTPPacketRedundancy"]+
                    "; no output writer created; closing this stream")
                throw t
            }
            log("Audio category="+category+" type="+route.type+" RED offered="+offeredRed+" response="+
                stream["supportsRTPPacketRedundancy"]+" sendRED="+redEnabled)
            val takeOwnership:suspend ()->Unit={
                if(category==1||category==3){
                    audioOwnershipLock.lock()
                    try{
                        check(!released.get()){"Audio lease closed"}
                        check(owned.get()){"Main audio reservation was released"}
                    }finally{audioOwnershipLock.unlock()}
                }
            }
            val releaseStream:()->Unit={
                if(released.compareAndSet(false,true)&&audioStreams.beginClose(lease)){
                    scope.launch{finishClose(needsTeardown=true)}
                }
            }
            val transport=if(candidate.codec==CarPlayAudioPlan.Codec.PCM16){
                // SAP 只实现了压缩音频（AAC）的 CTR 封装。PCM 分支还没接 CTR，
                // 明确失败而不是退回明文或错密钥，免得给车机送一包解不开的东西。
                if(sapAudio!=null)throw UnsupportedOperationException(
                    "SAP audio: PCM16 output path is not implemented; the receiver must offer a compressed media format")
                CarPlayMediaAudio(udp,address,port,format,category,route.type,key,retransmit,takeOwnership,releaseStream,log,redEnabled,
                    canSend={!stopping&&(category==2||audioOwnership.allowsMainSend())},
                    receiverHighAccuracyTimestamps=receiverHighAccuracyTimestamps)
            }
            else CarPlayCompressedAudio.create(udp,address,port,category,route.type,candidate,key,retransmit,takeOwnership,releaseStream,log,
                canSend={!stopping&&(category==2||audioOwnership.allowsMainSend())},redEnabled=redEnabled,
                receiverHighAccuracyTimestamps=receiverHighAccuracyTimestamps,sapCrypto=sapAudio,
                // 线上 PT 默认走规范映射（raw102→main 100，rtp_timing A03.1 的发送侧
                // FourCC→PT 硬编码表里没有 gen）；audioRawWirePt 只用于真机 A/B。
                wirePtOverride=if(options.audioRawWirePt)route.type else null,
                // 车机 SETUP 回的 output control port：RTCP SR 发到这里帮车机建播放时钟
                controlPort=(stream["controlPort"] as? Number)?.toInt()?.takeIf{it in 1..65535})
            transportRef.set(transport)
            val combined=microphone?.let{DuplexAudioTransport(transport,it)}?:transport
            audioTransports[category]=transport
            check(transport.ready&&!released.get()){"Audio transport closed during setup"}
            resources.own(combined)
            // T1：音频流是在 `prepare()` 的 RECORD 之后才 SETUP 的（真机日志：RECORD cseq=10
            // 比音频 SETUP cseq=36/39/42 早 6.4 秒，之后没再 RECORD）。这里在流真正 ready 之后
            // 补敲一次闸。bootstrap 顺序是 3→2→1，所以 category=1 落在三条流的最后，
            // 一次 RECORD 就能覆盖全部已建音频流，不必每条流各敲一枪。
            if(category==1&&AudioDebugConfig.recordAfterAudio){
                val repeated=setupLifecycle?.recordAgain("audio main ready type="+route.type+"/"+route.purpose)==true
                log("Audio origin gate category=1 recordRepeated="+repeated+
                    " codec="+candidate.codec+"; receiver playback acceptance still unmeasured")
            }
            return combined
        }catch(t:Exception){
            microphone?.close()
            udp.close();rtcp.close();transportRef.get()?.close();released.set(true)
            if(audioStreams.beginClose(lease)){
                // No teardown for an explicit refusal: another purpose may still use this type.
                // A timeout/malformed success is uncertain and requires an acknowledged teardown.
                finishClose(needsTeardown=setupAttempted&&t !is RtspClient.StatusException)
            }else runCatching{audioStreams.awaitClose(lease)}
            transportRef.get()?.let{audioTransports.remove(category,it)}
            // An explicit refusal is retryable on the next advertised prompt route. A timeout,
            // crypto error or malformed successful response must not trigger another SETUP.
            if(category==2&&t is RtspClient.StatusException&&t.status in setOf(400,406,415)&&rejectedPromptRoutes.add(route)){
                log("Audio prompt route refused type="+route.type+"/"+route.purpose+" status="+t.status+
                    "; local recovery tries the next advertised route")
                return createAudioLocked(category,source,onMicrophone)
            }
            throw t
        }
    }

    suspend fun createMicrophoneReceiver(onPcm:suspend (ByteArray,PcmFormat)->Boolean):Pair<CarPlayMicReceiver,PcmFormat>? = withContext(Dispatchers.IO){
        audioSetupLock.lock()
        try{createMicrophoneLocked(onPcm)}finally{audioSetupLock.unlock()}
    }
    private suspend fun createMicrophoneLocked(onPcm:suspend (ByteArray,PcmFormat)->Boolean):Pair<CarPlayMicReceiver,PcmFormat>? {
        check(!stopping){"Audio session stopping"}
        if(audioStreams.isActive(100,"speechRecognition")){log("Separate microphone SETUP skipped: type100/speechRecognition is reserved; duplex readiness must be checked on its transport");return null}
        // 麦克风与主音频共用 CarPlayAudioPlan 的一套优先序：此前这里自带的 `ELD -> 其它` 排序会让
        // "回退到 AAC-LC / OPUS" 在麦克风路径上静默失效（车机只广告 LC/OPUS 时被误判成无可用编码）。
        // 麦克风没有源 PCM 可对齐（与本改动前的排序一致：它也不看 source），故以 48k/2 作中性参考，
        // 拉平时序后仍按采样率降序取最高可用档。
        val candidates=CarPlayAudioPlan.microphoneCandidates(receiverAudio,PcmFormat(48000,2))
        // The receiver's input mask is only one half of the negotiation. Keep the
        // profile order while removing formats for which this phone has no decoder.
        val candidate=CarPlayAudioPlan.orderedIntersection(candidates){format->
            format.codec==CarPlayAudioPlan.Codec.PCM16||runCatching{AudioCodecSupport.decoders(format)}
                .getOrDefault(emptyList()).isNotEmpty()
        }.firstOrNull()?:return null
        val secret=shared?:return null;val control=client?:return null
        val id=Crypto.random.nextLong().and(Long.MAX_VALUE).coerceAtLeast(1)
        val udp=DatagramSocket(null)
        val lease=try{audioStreams.reserve(100,"speechRecognition",id)}catch(t:Throwable){udp.close();throw t}
        var setupAttempted=false
        fun finishClose(needsTeardown:Boolean){
            val failure=runCatching{if(needsTeardown&&!stopping)control.plist("TEARDOWN",connectionPath?:"/",mapOf("streams" to listOf(micTeardownDescriptor())))}.exceptionOrNull()
            audioStreams.completeClose(lease,failure)
            failure?.let{log("Microphone release not confirmed: "+it.javaClass.simpleName+"; same-type replacement suppressed")}
        }
        try{
            Bootstrap.bind(network,udp,options.bindNetwork,::pinningRefused)
            val localAddress=requireNotNull(localMediaAddress)
            udp.bind(InetSocketAddress(localAddress,0))
            val desc=mapOf("input" to true,"type" to 100,"audioType" to "speechRecognition",
                "audioFormat" to candidate.bit,"streamConnectionID" to id,"dataPort" to udp.localPort,
                "supportsHighAccuracyTimestamps" to true,"supportsRTPPacketRedundancy" to true)
            setupAttempted=true
            val response=control.plist("SETUP",connectionPath?:"/",mapOf("streams" to listOf(desc)))
            check(!stopping){"Audio session stopping"}
            val stream=(response["streams"] as? List<*>)?.filterIsInstance<Map<*,*>>()?.singleOrNull{(it["type"] as? Number)?.toInt()==100}?:error("No microphone stream response")
            // 响应 dataPort 对 sink-only 可省略，且不是麦克风监听/源端口（ch09 §2.3）：
            // 只校验范围，不拿它过滤来源；来源过滤交给 peerPort=0。
            (stream["dataPort"] as? Number)?.toInt()?.let{require(it in 1..65535){"Microphone response dataPort out of range"}}
            micStreamId=(stream["streamID"] as? Number)?.toLong()
            require(stream["streamConnectionID"]==null||(stream["streamConnectionID"] as? Number)?.toLong()==id)
            if(stream["supportsHighAccuracyTimestamps"]==true)
                log("Microphone response advertises high-accuracy timestamps; host clock mapping not established")
            val key=Crypto.hkdf(secret,"DataStream-Salt$id","DataStream-Input-Encryption-Key")
            val receiver=CarPlayMicReceiver.create(udp,address,0,key=key,format=candidate,
                controlPort=(stream["controlPort"] as? Number)?.toInt()?.takeIf{it in 1..65535},
                output=onPcm,log=log){
                if(audioStreams.beginClose(lease))scope.launch{finishClose(needsTeardown=true)}
            }
            resources.own(receiver);log("${"CarPlay microphone SETUP type100 input-only codec="}${candidate.codec}${" "}${candidate.rate}${"/"}${candidate.channels}")
            return receiver to candidate.decodedPcm
        }catch(t:Exception){
            udp.close()
            if(audioStreams.beginClose(lease))finishClose(needsTeardown=setupAttempted&&t !is RtspClient.StatusException)
            else runCatching{audioStreams.awaitClose(lease)}
            if(t is CancellationException)throw t
            log("CarPlay microphone unavailable: "+t.javaClass.simpleName+" "+(t.message?:""));return null
        }
    }
    /** 单调时钟 → 墙钟毫秒的偏移，只在首帧量一次。第 07 章 §2.6 要求 0x08 是"已转换的 presentation NTP"，
     *  而 0x10 才是原始单调时间戳；没有时钟同步时用这个偏移做一次保守换算。 */
    @Volatile private var wallOffsetMs:Long?=null
    private fun presentationWallMs(monotonicUs:Long):Long{
        val offset=wallOffsetMs?:run{
            val value=System.currentTimeMillis()-monotonicUs/1000
            wallOffsetMs=value;value
        }
        return monotonicUs/1000+offset
    }
    /** 屏幕流保活帧（第 07 章 §2.6.1）：128 字节明文头、`0x04=2`、无正文，不消耗帧 nonce。 */
    private fun keepAliveFrame():Unit=videoDiagnostics.trace("keepalive"){ticket->synchronized(this){
        if(stopping)return@synchronized
        videoDiagnostics.phase(ticket,VideoSendDiagnostics.Phase.ENCRYPT)
        val socket=video?:return@synchronized
        val packet=VideoWire.keepAlive()
        videoDiagnostics.phase(ticket,VideoSendDiagnostics.Phase.SOCKET_WRITE)
        socket.getOutputStream().write(packet)
        videoDiagnostics.phase(ticket,VideoSendDiagnostics.Phase.SOCKET_FLUSH)
        socket.getOutputStream().flush()
    }}
    fun send(packet:ByteArray,config:Boolean,ptsMonoUs:Long?):Unit=videoDiagnostics.trace(if(config)"config" else "frame"){ticket->synchronized(this){
        if(stopping)return@synchronized
        videoDiagnostics.phase(ticket,VideoSendDiagnostics.Phase.ENCRYPT)
        check(screen!=null);val socket=video?:error("No video socket")

        val result=if(config)packet else {
            val sourceUs=requireNotNull(ptsMonoUs){"Missing encoded frame timestamp"}
            val previous=lastVideoMonoUs
            val monotonicUs=if(previous==null)sourceUs else maxOf(sourceUs,previous+1)
            lastVideoMonoUs=monotonicUs
            val timestamp=Math.multiplyExact(monotonicUs,1000L)
            val key=shared?.let{Crypto.hkdf(it,"DataStream-Salt"+streamId,"DataStream-Output-Encryption-Key")}
            val ntp=VideoWire.ntp(presentationWallMs(monotonicUs))
            if(key!=null)VideoWire.frame(packet,key,videoCounter++,timestamp,ntp,ntp)
            else {
                val cipher=aes?:mfi?.streamCipher(streamId)?.also{aes=it}?:error("Missing media crypto")
                val encrypted=cipher.update(packet)
                // 同一套头语义：0x08 是 presentation NTP，0x10 才是原始单调时间戳（第 07 章 §2.6）。
                ByteArray(128).also{ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(0,encrypted.size).putLong(8,ntp).putLong(16,timestamp)}+encrypted
            }
        }
        videoDiagnostics.phase(ticket,VideoSendDiagnostics.Phase.SOCKET_WRITE)
        socket.getOutputStream().write(result)
        videoDiagnostics.phase(ticket,VideoSendDiagnostics.Phase.SOCKET_FLUSH)
        socket.getOutputStream().flush()
        if(!config){videoFrames++;lastVideoNanos=System.nanoTime()}
    }}
    private var aes:javax.crypto.Cipher?=null
    /** 音量命令每个会话只发一次（再补一次延迟重发），别让它成为每类音频的重复动作。 */
    private val volumeAnnounced=java.util.concurrent.atomic.AtomicBoolean()
    /** AirPlay 的收端音量由发送方设定：`SET_PARAMETER /volume`，单位 dB，-144 约等于静音。
     *  只发一次、不重发。固定延迟的无条件重发属于盲试（AGENT.md §3）；音量是幂等的状态
     *  设置，丢了就丢了，不该用重发去掩盖，也不该让它占住 SETUP 热路径。 */
    private suspend fun announceVolume(control:RtspClient){
        if(!AudioDebugConfig.volume)return
        val configured=options.audioVolumeDb
        // iPhone 建流后一定会送一条音量。此前 options.audioVolumeDb 默认 NaN 时直接 return，
        // 于是本实现从未宣告过音量（历史日志 `Audio volume announced` 零次）。接收端若把
        // "未宣告" 当成 0 增益，载荷再正确也是静音，所以 NaN 时落到一个明确默认值。
        val db=if(configured.isNaN())AudioDebugConfig.DEFAULT_VOLUME_DB else configured
        if(!volumeAnnounced.compareAndSet(false,true))return
        runCatching{control.plist("SET_PARAMETER",connectionPath?:"/",mapOf("volume" to db))}
            .onSuccess{log("Audio volume announced volume="+db+"dB"+
                (if(configured.isNaN())" (debug default; options.audioVolumeDb was NaN)" else "")+" accepted")}
            .onFailure{log("Audio volume announcement failed: "+it.javaClass.simpleName+" "+(it.message?:""))}
    }
    /** 拆屏幕流：文档 06 章 §2.5 要求"先注销本地流、再发 TEARDOWN"，失败不回滚注销。
     *  正文是 `streams` 数组；`teardownType` 是固件里的**条件字段**（会话 `+0x10 != 0` 才写），
     *  其取值语义仍 `[OPEN]`，所以这里不发，避免发出猜的枚举值。 */
    fun teardownScreen(reason:String){
        setupLifecycle?.closeScreen()
    }
    fun closeVideo(){
        // 先拆流再关媒体 socket：顺序反了会让接收端把"连接断"当成异常而不是正常结束。
        teardownScreen("close")
        runCatching{video?.close()}
    }
}
