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
class ReceiverDiscovery(private val context:Context,private val path:NetworkPath,private val bootstrap:WifiBootstrap,
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
    /** 每个服务名解析失败的次数，用于"重试有限次而不是永久拉黑"。 */
    private val resolveFailures=java.util.concurrent.ConcurrentHashMap<String,Int>()
    /** 重开浏览的次数，只用于日志编号。 */
    private val rebrowses=java.util.concurrent.atomic.AtomicInteger()
    /** 主浏览当前的注册。重开浏览之前要先把它停掉，否则同一个网络会挂两份浏览。 */
    @Volatile private var browse:CallbackRegistration?=null
    /** MulticastLock 是否真的拿到（厂商可能拒绝）。只用于日志与心跳诊断。 */
    @Volatile private var multicastHeld=false
    private var multicast:AutoCloseable?=null
    /** 出现过的服务类型 —— 世代判据的一部分，见 [ReceiverMetadata.services]。 */
    private val services=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** 只用于"这个服务类型在不在"的辅助浏览：多一条线索就少一次无谓的配对尝试。 */
    private val address=bootstrap.endpoint?.let{Bootstrap.address(path.properties,it.address)}
    /** 仅用于诊断比对；第三方接收端的 0x4301 receiver 可能不是 12 位十六进制，解析失败就当作“未知”。 */
    private val expected=bootstrap.endpoint?.receiver?.let{runCatching{ReceiverIdentity.canonical(it)}.getOrNull()}
    private val lost=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** 每次重开浏览都换一个新的监听器对象：NsdManager 拿监听器实例当注册键，
     *  同一个对象第二次 discoverServices 会直接抛 "listener already in use"。
     *
     *  [primary] 只影响"起不来算不算致命"：第一道浏览起不来说明这台机器根本不支持，
     *  而为了重新发问重开的那些失败只记日志——不能因为一次重开失败就把整轮发现作废。 */
    private fun browseListener(primary:Boolean)=object:NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type:String){
            if(closed.get()){runCatching{nsd.stopServiceDiscovery(this)};return}
            log("Bonjour receiver browse started"+
                (if(primary)"" else " (re-issue #"+rebrowses.get()+", screenOn="+screenOn()+")"))
        }
        override fun onStartDiscoveryFailed(type:String,error:Int){
            if(primary)ready.completeExceptionally(java.io.IOException("Bonjour discovery start $error"))
            else log("Bonjour re-issued browse failed to start: "+error+
                "; this re-issue is lost, the next tick tries again")
        }
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
    private val listener=browseListener(true)
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
            override fun onResolveFailed(info:NsdServiceInfo,error:Int){
                if(!closed.get())log("${"Bonjour candidate resolve failed "}${error}")
                // `seen` 是"这个名字我处理过了"的去重表，解析失败也留在里面的话，
                // 这个名字就被永久拉黑了：车机后面再怎么重播（我们自己也每 6 秒重问一次），
                // 都不会再解析第二次。给有限次重试机会，超过就不再纠缠。
                val attempts=resolveFailures.merge(info.serviceName,1,{old,n->old+n})!!
                if(attempts<RESOLVE_ATTEMPTS){
                    // 放回 `seen` 之外，下一次公告（或我们自己 6 秒后的重问）会再排一次。
                    seen.remove(info.serviceName)
                    log("Bonjour will retry the resolve for "+info.serviceName+
                        " (attempt "+attempts+" of "+RESOLVE_ATTEMPTS+")")
                }else log("Bonjour giving up on "+info.serviceName+" after "+attempts+" failed resolves")
                next()
            }
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
    private fun registerBrowse(type:String,callback:NsdManager.DiscoveryListener):CallbackRegistration{
        val registration=CallbackRegistration({
            if(Build.VERSION.SDK_INT>=33)nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,path.network,Executor{it.run()},callback)
            else nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,callback)
        },{nsd.stopServiceDiscovery(callback)})
        registrations.own(registration).start()
        return registration
    }
    /** 只用于日志，却是我早该有的那一条：**锁屏导致的 `found=0` 和"车机没广播"在日志里
     *  长得一模一样**。2026-11-05 那三轮全 `found=0` 的尝试，真正原因是我们自己听不见，
     *  而不是车机安静——当时只能靠用户说"手机没解锁"才反推出来。 */
    private fun screenOn():Boolean?=runCatching{
        context.getSystemService(android.os.PowerManager::class.java)?.isInteractive
    }.getOrNull()
    /** 灭屏后 Wi-Fi 会把组播整片丢掉：2026-11-05 22:02:16 起（`ScreenOn:false`）连续三轮
     *  `found=0`，22:04:52.986 亮屏之后下一轮 7 秒就发现了车机。`MulticastLock` 是 Android
     *  给的唯一开关，而且它作用在**整个 Wi-Fi 接口**上——即使 mdns 的套接字开在系统进程里，
     *  我们举着它，广播也能重新送进来。清单里 `CHANGE_WIFI_MULTICAST_STATE` 一直声明着，
     *  只是从没被用过。 */
    private fun holdMulticast():AutoCloseable?{
        val manager=runCatching{context.getSystemService(android.net.wifi.WifiManager::class.java)}.getOrNull()
        if(manager==null){
            log("Wi-Fi manager unavailable; mDNS reception may go deaf when the screen turns off")
            return null
        }
        val lock=runCatching{manager.createMulticastLock(MULTICAST_TAG)}.getOrNull()
        if(lock==null){
            log("Multicast lock unavailable; mDNS reception may go deaf when the screen turns off")
            return null
        }
        // 我们只关心"这一轮按住没按住"，不需要配对计数：计数模式下重复 acquire
        // 要 release 同样多次，中途异常就会把锁漏到进程结束。
        lock.setReferenceCounted(false)
        return try{
            lock.acquire();multicastHeld=true
            log("Wi-Fi multicast lock held; mDNS reception survives the screen turning off")
            AutoCloseable{multicastHeld=false;runCatching{lock.release()}}
        }catch(t:Exception){
            log("Wi-Fi multicast lock refused: "+t.javaClass.simpleName+" "+(t.message?:"")+
                "; mDNS reception may go deaf when the screen turns off")
            null
        }
    }
    /** 重开 `_airplay` 浏览。
     *
     *  mDNS 的查询间隔是 1s/2s/4s…一路退避到一小时，所以浏览跑得越久、问得越少；而车机的
     *  "我在这儿"是无条件组播，**丢掉的公告不会补发**。重开浏览等于立刻重新发问，车机必须
     *  回答——这是把"我们已经聋了"和"车机还没起来"彻底分开的唯一办法，也是把车机叫醒的
     *  唯一手段（等它自己重播要等两小时）。 */
    private fun rebrowse(){
        if(closed.get()||ready.isCompleted)return
        val index=rebrowses.incrementAndGet()
        log("Bonjour re-issuing the _airplay browse (#"+index+"): found="+found.get()+
            " seen="+seen.size+" screenOn="+screenOn()+" multicastHeld="+multicastHeld+
            "; the daemon's queries have backed off and a missed announcement is never repeated")
        browse?.let{registration->runCatching{registration.close()}}
        browse=null
        try{browse=registerBrowse("_airplay._tcp.",browseListener(false))}
        catch(t:Exception){
            log("Bonjour re-issued browse unavailable: "+t.javaClass.simpleName+" "+(t.message?:"")+
                "; will retry on the next tick")
        }
    }
    fun start(){
        check(!closed.get()){"Discovery already closed"}
        if(!started.compareAndSet(false,true))return
        ReceiverDiscoveryScope.requireSupported(Build.VERSION.SDK_INT,bootstrap.endpoint!=null)
        resources.own(this)
        // 必须在浏览之前拿：浏览一开，daemon 就进入退避节奏，而组播过滤打开之前
        // 被丢掉的每一轮广播都不会再来第二遍。
        multicast=holdMulticast()
        log("Bonjour browse starting: screenOn="+screenOn()+" multicastHeld="+multicastHeld+
            " sdk="+Build.VERSION.SDK_INT)
        // 世代/实现线索先铺开：它们是**附加**证据，任何一条起不来都不影响能否连上车机
        // （browseClue 内部自己吞掉异常）。_airplay 的浏览才是拿到 endpoint 的那一条。
        CLUE_SERVICES.forEach{type->browseClue(type)}
        try{
            browse=registerBrowse("_airplay._tcp.",listener)
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
            var rebrowsedAt=0L
            while(isActive){
                delay(HEARTBEAT_MS)
                val elapsedMs=(System.nanoTime()-startedAt)/1_000_000L
                // 定期重开浏览，见 rebrowse()。放在心跳里是想让它的生命周期和等待完全一致：
                // 等待结束（拿到端点或窗口到期）就自然不再重开。
                if(elapsedMs-rebrowsedAt>=REBROWSE_MS){rebrowsedAt=elapsedMs;rebrowse()}
                log("Bonjour still searching after "+elapsedMs+"ms: found="+found.get()+
                    " seen="+seen.size+" pending="+pending.size+" resolving="+resolving.get()+
                    " screenOn="+screenOn()+" multicastHeld="+multicastHeld+
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
            // 先停浏览再放锁：倒过来的话，停浏览的那一刻可能正好有一帧广播在路上。
            multicast?.let{runCatching{it.close()}};multicast=null
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
        /** 心跳间隔。顺带承担"多久检查一次要不要重新发问"。 */
        const val HEARTBEAT_MS=3_000L
        /** 重开浏览的间隔。一次 stop/start 换一次全新的 mDNS 查询风暴，
         *  代价可以忽略，换来的是"车机只要还在广播，最迟这么久就会被听见"。
         *  取 6 秒 = 两个心跳：能让日志里 3 秒一格的状态看得出"重开过没有"，
         *  又不会在 15 秒的窗口里重开到让 daemon 自己都来不及回答。 */
        const val REBROWSE_MS=6_000L
        /** MulticastLock 的名字，只会出现在 `dumpsys wifi` 里，用于事后确认我们举着它。 */
        const val MULTICAST_TAG="carvex-mdns"
        /** 同一个服务名最多解析几次。解析失败多半是 daemon 刚起来还没准备好，
         *  重试几次就好；但也不能无限重试，那会变成对着一个坏候选空转。 */
        const val RESOLVE_ATTEMPTS=4
        /** 只要出现就记录的服务类型；`_raop`/`_hap` 判 AirPlay 2，`_mfi-config` 是 Cinemo 系（GM）的实现线索。
         *  逐个浏览而不是只查一个：三个车机样本的服务集本来就不同（t3-carplay-spec §9.2）。 */
        val CLUE_SERVICES=listOf("_raop._tcp.","_hap._tcp.","_mfi-config._tcp.")
    }
}
