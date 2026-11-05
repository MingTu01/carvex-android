package io.github.iamr0s.carvex.carplay
import io.github.iamr0s.carvex.sessioncore.*
import android.content.Context
import android.net.nsd.*
import android.os.Build
import kotlinx.coroutines.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import io.github.iamr0s.carvex.carplay.core.*

/** [protocolVersion]/[sourceVersion] 是对端自报的版本标识（Bonjour TXT 的 protovers / srcvers）。
 *  它们是运行时**值**而不是常量：固件文档里的 210.81 只是本车所带 SDK 的版本，别的车机会是别的值，
 *  因此读进来交给上层按值判断，绝不在代码里假设。
 *
 *  [services] 是这台车机在网络上**出现过的服务类型集合**，即使其中没有我们要连的那一个：
 *  它是世代判定的一条证据（`_raop`/`_hap` ⇒ AirPlay 2；`_mfi-config` ⇒ Cinemo 系实现线索），
 *  单独看它不足以连上车机，但足以让配对少走一条弯路。*/
data class ReceiverMetadata(val pairingId:String?,val publicKey:ByteArray?,val features:ULong?,
                            val protocolVersion:String?=null,val sourceVersion:String?=null,
                            val services:Set<String> =emptySet(),
                            /** Bonjour 解析到的车机地址。可能与 0x4301 自报的地址**不同族或不同值**
                             *  （现场一台 DiPlay：0x4301 报 IPv6 link-local，mDNS 通告 IPv4 192.168.43.1），
                             *  所以它只作为**连接的候选地址**，不参与身份判定。 */
                            val endpoint:java.net.InetAddress?=null,val port:Int?=null,val deviceId:String?=null,val endpoints:List<java.net.InetAddress> =emptyList(),
                            val featureEvidence:AirPlayFeatureEvidence?=null) {
    val effectiveFeatureEvidence:AirPlayFeatureEvidence
        get()=featureEvidence?:AirPlayFeatureEvidence.fromLegacyFlags(features)
    val effectiveFeatures:ULong? get()=effectiveFeatureEvidence.set?.legacyFlags
}
/** One serialized resolver, restricted network on API33+; older APIs require endpoint matching. */
class ReceiverDiscovery(context:Context,private val path:NetworkPath,private val bootstrap:WifiBootstrap,
                        private val resources:Resources,private val log:(String)->Unit):AutoCloseable {
    private val nsd=context.getSystemService(NsdManager::class.java)
    private val ready=CompletableDeferred<ReceiverMetadata>()
    private val closed=AtomicBoolean(false)
    private val started=AtomicBoolean(false)
    private val registrations=Resources()
    private val resolving=AtomicBoolean(false)
    private val pending=java.util.concurrent.ConcurrentLinkedQueue<NsdServiceInfo>()
    private val seen=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** 主浏览（_airplay）累计看到的服务数。只用于诊断：它区分"车机没广播"和"广播了但我们筛掉了"。 */
    private val found=java.util.concurrent.atomic.AtomicInteger()
    /** 出现过的服务类型 —— 世代判据的一部分，见 [ReceiverMetadata.services]。 */
    private val services=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** 只用于"这个服务类型在不在"的辅助浏览：多一条线索就少一次无谓的配对尝试。 */
    private val address=bootstrap.endpoint?.let{Bootstrap.address(path.properties,it.address)}
    /** 仅用于诊断比对；第三方接收端的 0x4301 receiver 可能不是 12 位十六进制，解析失败就当作“未知”。 */
    private val expected=bootstrap.endpoint?.receiver?.let{runCatching{ReceiverIdentity.canonical(it)}.getOrNull()}
    private val lost=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val listener=object:NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type:String){
            if(closed.get()){runCatching{nsd.stopServiceDiscovery(this)};return}
            log("Bonjour receiver browse started")
        }
        override fun onStartDiscoveryFailed(type:String,error:Int){ready.completeExceptionally(java.io.IOException("Bonjour discovery start $error"))}
        override fun onStopDiscoveryFailed(type:String,error:Int){log("${"Bonjour discovery stop "}${error}")}
        override fun onDiscoveryStopped(type:String){}
        override fun onServiceLost(info:NsdServiceInfo){
            if(closed.get())return
            lost.add(info.serviceName);seen.remove(info.serviceName)
            pending.removeIf{it.serviceName==info.serviceName}
        }
        override fun onServiceFound(info:NsdServiceInfo){
            if(closed.get())return
            found.incrementAndGet()
            lost.remove(info.serviceName)
            info.serviceType?.let{services.add(it)}
            if(closed.get()||ready.isCompleted||!seen.add(info.serviceName))return
            if(seen.size>64){log("Bonjour candidate limit reached");return}
            log("${"Bonjour candidate found name="}${info.serviceName}${" type="}${info.serviceType}")
            pending.add(info);resolveNext()
        }
    }
    /** 辅助浏览：只听"存在"，不做 endpoint 匹配。失败只记日志，绝不影响主发现路径。 */
    private fun browseClue(type:String){
        val clue=object:NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type:String){
                if(closed.get()){runCatching{nsd.stopServiceDiscovery(this)};return}
                log("${"Bonjour hint browse started "}${type}")
            }
            override fun onStartDiscoveryFailed(type:String,error:Int){log("${"Bonjour hint browse "}${type}${" failed to start: "}${error}")}
            override fun onStopDiscoveryFailed(type:String,error:Int){log("${"Bonjour hint browse "}${type}${" stop failed: "}${error}")}
            override fun onDiscoveryStopped(type:String){}
            override fun onServiceLost(info:NsdServiceInfo){}
            override fun onServiceFound(info:NsdServiceInfo){
                if(closed.get())return
                services.add(type)
                log("${"Bonjour generation/implementation hint: saw "}${type}${" (service="}${info.serviceName}${")"}")
            }
        }
        try{
            registerBrowse(type,clue)
        }catch(t:Exception){log("${"Bonjour hint browse "}${type}${" unavailable: "}"+t.javaClass.simpleName+" "+(t.message?:""))}
    }
    private fun resolveNext(){
        if(closed.get()||ready.isCompleted||!resolving.compareAndSet(false,true))return
        val item=pending.poll()
        if(item==null){resolving.set(false);if(pending.isNotEmpty())resolveNext();return}
        fun next(){resolving.set(false);resolveNext()}
        try{nsd.resolveService(item,object:NsdManager.ResolveListener {
            override fun onResolveFailed(info:NsdServiceInfo,error:Int){if(!closed.get())log("${"Bonjour candidate resolve failed "}${error}");next()}
            override fun onServiceResolved(info:NsdServiceInfo){
                if(closed.get()||info.serviceName in lost){next();return}
                try{
                    val hosts=if(Build.VERSION.SDK_INT>=34)info.hostAddresses else listOfNotNull(info.host)
                    val direct=bootstrap.endpoint
                    val scopedNetwork=Build.VERSION.SDK_INT>=33&&info.network==path.network
                    val directAddressMatch=address!=null&&hosts.any{it.address.contentEquals(address.address)}
                    val networkMatch=scopedNetwork||((Build.VERSION.SDK_INT<33||info.network==null)&&directAddressMatch)
                    // 保留 Bonjour SRV 自身的地址和端口，不要求与 0x4301 相同。
                    // 两种来源的端点成对交给 ControlEndpointCandidates 选择，不交叉拼接。
                    // 下面每个 return 都是一道会把候选丢掉的门；以前它们全是静默的，日志只剩
                    // "浏览已开始、然后什么都没有"，无法区分车机没广播还是我们筛掉了。
                    if(!networkMatch||info.port !in 1..65535){
                        log("Bonjour candidate rejected name="+info.serviceName+" port="+info.port+
                            " networkMatch="+networkMatch+" scoped="+scopedNetwork+" addressMatch="+directAddressMatch+
                            " sdk="+Build.VERSION.SDK_INT)
                        return
                    }
                    require(info.attributes.values.sumOf{it.size}<=4096)
                    fun attribute(key:String)=info.attributes.entries.firstOrNull{it.key.equals(key,true)}?.value?.let{
                        require(it.size<=1024);it.toString(Charsets.UTF_8)}
                    val observed=attribute("deviceid")?:run{
                        log("Bonjour candidate rejected name="+info.serviceName+" reason=no deviceid txtKeys="+
                            info.attributes.keys.joinToString(","));return}
                    val canonical=ReceiverIdentity.canonical(observed)
                    if(canonical.isEmpty()){
                        log("Bonjour candidate rejected name="+info.serviceName+" reason=deviceid not canonical value="+observed)
                        return
                    }
                    // 0x4301 的 receiver 字段与 Bonjour 的 deviceid 在第三方接收端上**可以不同**
                    // 邀请另有十进制ID解析；它不证明这两个身份等价，完整固件身份融合仍待闭合。
                    if(expected!=null&&canonical!=expected)
                        log("Bonjour deviceid differs from 0x4301 receiver id (bonjour="+canonical+" iap="+expected+"); accepting candidate")
                    val scoped=hosts.mapNotNull{host->runCatching{
                        Bootstrap.address(path.properties,requireNotNull(host.hostAddress))}.getOrNull()}
                    val endpoint=scoped.firstOrNull{candidate->address?.let{candidate.address.contentEquals(it.address)}==true}
                        ?:scoped.firstOrNull()?:run{
                            log("Bonjour candidate rejected name="+info.serviceName+" reason=no link-local address on this network hosts="+
                                hosts.joinToString(","){it.hostAddress})
                            return}
                    val advertisedKey=ReceiverIdentity.publicKey(attribute("pk"))
                    if(bootstrap.publicKey!=null&&advertisedKey!=null&&!java.security.MessageDigest.isEqual(bootstrap.publicKey,advertisedKey))
                        throw SecurityException()
                    val key=ReceiverIdentity.reconcile(bootstrap.publicKey,advertisedKey)
                    // Presence is significant, including an empty fex value. A malformed fex
                    // stays attached to this candidate so selection cannot silently use legacy.
                    val fex=info.attributes.entries.firstOrNull{it.key.equals("fex",true)}
                    val featureEvidence=if(fex!=null)AirPlayFeatureEvidence.fromBonjour(
                        fex.value?.toString(Charsets.UTF_8)?:"",null)
                    else AirPlayFeatureEvidence.fromBonjour(null,attribute("features"))
                    val metadata=ReceiverMetadata(attribute("pi"),key,featureEvidence.set?.legacyFlags,
                        attribute("protovers"),attribute("srcvers"),services.toSet(),endpoint,info.port,observed,
                        endpoints=scoped,featureEvidence=featureEvidence)
                    // TXT and service names remain candidates; pair/signature verification establishes identity.
                    if(!closed.get()&&info.serviceName !in lost)ready.complete(metadata)
                    log("Bonjour endpoint candidate on selected network; keyPresent="+(key!=null)+
                        " features="+featureEvidence.diagnostic())
                }catch(t:SecurityException){ready.completeExceptionally(t)}
                catch(t:Exception){/* An untrusted malformed candidate does not own the discovery result. */}
                finally{next()}
            }
        })}catch(t:Exception){ready.completeExceptionally(t);resolving.set(false)}
    }
    private fun registerBrowse(type:String,callback:NsdManager.DiscoveryListener){
        registrations.own(CallbackRegistration({
            if(Build.VERSION.SDK_INT>=33)nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,path.network,Executor{it.run()},callback)
            else nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,callback)
        },{nsd.stopServiceDiscovery(callback)})).start()
    }
    fun start(){
        check(!closed.get()){"Discovery already closed"}
        if(!started.compareAndSet(false,true))return
        ReceiverDiscoveryScope.requireSupported(Build.VERSION.SDK_INT,bootstrap.endpoint!=null)
        resources.own(this)
        // 世代/实现线索先铺开：它们是**附加**证据，任何一条起不来都不影响能否连上车机
        // （browseClue 内部自己吞掉异常）。_airplay 的浏览才是拿到 endpoint 的那一条。
        CLUE_SERVICES.forEach{type->browseClue(type)}
        try{
            registerBrowse("_airplay._tcp.",listener)
        }catch(t:Exception){ready.completeExceptionally(t)}
    }
    /** 15 秒的等待以前是纯黑箱：日志只有"浏览已开始"，之后要么拿到端点要么什么都没有。
     *  真车 2026-11-03 连续三次都停在这里，只能靠猜。现在每 3 秒报一次内部状态：
     *  found=0 且 services 也没有 _airplay ⇒ 车机这一轮根本没广播；
     *  found>0 但 ready 未完成 ⇒ 是我们自己的筛选门把它丢了（上面那些 rejected 行会说明是哪道门）。
     *
     *  [extendOnInvitation] 由调用方在"本轮已收到车机邀请"时为 true。理由是真车 2026-11-05 的
     *  开机序列：第 1、2 次尝试各等 15 秒都没看到 _airplay，第 3 次在浏览开始后 1.05 秒就看到了
     *  —— 车机的广播是**晚到**的（约 45 秒才起来），不是"连不上"。而每次等不到就放开
     *  WifiNetworkSpecifier，CarWith 立刻判定投屏结束（`launcher receiver stopCast` →
     *  `forceStopApp`），于是进程被杀、3 秒后重新从蓝牙走一遍。多等一会儿就能省掉整轮重来。 */
    suspend fun await(extendOnInvitation:()->Boolean={false}):ReceiverMetadata=coroutineScope{
        val startedAt=System.nanoTime()
        val heartbeat=launch{
            while(isActive){
                delay(3_000)
                val elapsedMs=(System.nanoTime()-startedAt)/1_000_000L
                log("Bonjour still searching after "+elapsedMs+"ms: found="+found.get()+
                    " seen="+seen.size+" pending="+pending.size+" resolving="+resolving.get()+
                    " services=["+services.joinToString(",")+"] ready="+ready.isCompleted)
            }
        }
        try{
            var metadata=withTimeoutOrNull(DISCOVERY_WINDOW_MS){ready.await()}
            // 邀请往往在窗口**中间**才到，所以这里必须在到期的这一刻才问，不能入口处取一次快照。
            val invited=metadata==null&&extendOnInvitation()
            if(invited){
                val waitedMs=(System.nanoTime()-startedAt)/1_000_000L
                log("Bonjour: the car asked us to start (invitation accepted) but has not advertised "+
                    "_airplay after "+waitedMs+"ms; keeping the Wi-Fi lease and waiting "+
                    (EXTENSION_WINDOW_MS/1000)+"s more instead of dropping the lease and being restarted")
                metadata=withTimeout(EXTENSION_WINDOW_MS){ready.await()}
            }
            metadata?:throw java.net.SocketTimeoutException(
                "Bonjour endpoint deadline: no receiver candidate within "+(DISCOVERY_WINDOW_MS/1000)+"s"+
                (if(invited)" and no advertisement after an accepted invitation" else ""))
        }finally{heartbeat.cancel()}
    }
    override fun close(){
        if(closed.compareAndSet(false,true)){
            pending.clear()
            ready.cancel();registrations.close()
        }
    }
    private companion object {
        /** 端点发现窗口。真车成功样本里 _airplay 在浏览开始后 82ms~1.05s 出现，所以这个窗口
         *  不是在"抢时间"，而是在"给车机自救"。它到期会以 SocketTimeoutException 形式失败，
         *  由上层重试（不是取消整条链）。 */
        const val DISCOVERY_WINDOW_MS=15_000L
        /** 已经收到车机邀请时的追加等待。车机明说了"开始吧"，它只是还没把 _airplay 广播起来。
         *  上限由宿主决定：真车 2026-11-05 实测 CarWith 每次连接尝试的硬预算是 **40 秒**
         *  （三段会话 40.1 / 40.0 / 40.0 秒，误差 0.1 秒），而浏览大约在服务启动后 7 秒才开始，
         *  所以真正能用的窗口约 33 秒。15+15=30 秒留出一点余量让我们自己干净收尾，
         *  而不是超时后被 SIGKILL——被杀掉时连失败原因都写不进日志。 */
        const val EXTENSION_WINDOW_MS=15_000L
        /** 只要出现就记录的服务类型；`_raop`/`_hap` 判 AirPlay 2，`_mfi-config` 是 Cinemo 系（GM）的实现线索。
         *  逐个浏览而不是只查一个：三个车机样本的服务集本来就不同（t3-carplay-spec §9.2）。 */
        val CLUE_SERVICES=listOf("_raop._tcp.","_hap._tcp.","_mfi-config._tcp.")
    }
}
