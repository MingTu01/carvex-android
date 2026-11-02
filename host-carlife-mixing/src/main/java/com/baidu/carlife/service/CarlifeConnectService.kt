package com.baidu.carlife.service
import android.app.*
import android.content.*
import android.os.*
import kotlinx.coroutines.flow.distinctUntilChanged
import io.github.iamr0s.carvex.host.mixing.service.MixingServiceDelegate
import io.github.iamr0s.carvex.host.mixing.service.MixingServiceOwner
import io.github.iamr0s.carvex.host.mixing.service.MixingServiceSession
import kotlinx.coroutines.*
import java.util.UUID

class CarlifeConnectService:Service() {
    private lateinit var delegate:MixingServiceDelegate
    private val controller:MixingServiceSession get()=delegate.session
    private val binder=Binder()
    private var disconnectJob:Job?=null
    private var sessionJob:Job?=null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    override fun onCreate(){
        super.onCreate()
        val owner=application as? MixingServiceOwner
            ?: error("Application must implement MixingServiceOwner")
        delegate=owner.createMixingServiceDelegate(this)
        activeInstance=this
    }
    override fun onBind(intent:Intent?):IBinder=binder // KeepAlive does not establish a session.
    /** 只记录宿主 extras 的 key 和类型，不记录值。 */
    @Suppress("DEPRECATION")
    private fun Intent.describeExtras():String {
        val extras=extras?:return ""
        return extras.keySet().filterNot{it=="internal_token"}.joinToString(","){key->key+"="+(extras.get(key)?.javaClass?.simpleName?:"null")}
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        // 落在任何提前返回之前：被拒的请求也要留痕。
        delegate.log("entry","Intent extras: "+(intent?.describeExtras()?:"none"))
        val internal=intent?.getStringExtra("internal_token")==token(this)
        if(internal&&intent?.action==STOP){
            // 用户点"断开"是明确意图，必须留住：否则 CarWith 会在几百毫秒后再发一次
            // host 意图把我们拉起来（实车 11:29:44 断开 → 11:29:44.88 就又收到了 host 意图），
            // 在用户眼里就是"点了断开没退出 / 一直闪 / 像崩溃"。
            markUserStop()
            if(disconnectJob?.isActive!=true)disconnectJob=scope.launch{
                try{sessionJob?.cancelAndJoin();controller.disconnect()}
                catch(t:CancellationException){throw t}
                catch(t:Exception){delegate.log("entry","Local disconnect failed "+t.javaClass.simpleName+" "+(t.message?:""))}
                finally{stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
            }
            return START_NOT_STICKY
        }
        if(disconnectJob?.isActive==true){delegate.log("entry","Connection request ignored while disconnecting");return START_NOT_STICKY}
        val hostRequest = delegate.brand.acceptsHostIntent(
            intent?.getStringExtra("bindPackageName"),
            intent?.getStringExtra("bindClassName"),
            intent?.getStringExtra("manufacturer"),
            intent?.getIntExtra("usb_type", -1) ?: -1,
            intent?.getBooleanExtra("is_box", false) ?: false,
        )
        if(!((internal&&intent?.action==CONNECT)||hostRequest)){delegate.log("entry","Unsupported or invalid service entry");if(!controller.active&&sessionJob?.isActive!=true)stopSelf(startId);return START_NOT_STICKY}
        if(internal&&intent?.action==CONNECT)clearUserStop()
        // 只有"宿主自己找上门"的入口会被这道闩挡住；用户在本应用里点连接走的是 internal CONNECT，
        // 不受影响。窗口有限而不是永久：CarWith 发起的连接本身就是正常用法，永久拉黑会让那条路彻底废掉。
        if(hostRequest&&!internal){
            val left=userStopRemainingMs()
            if(left>0L){
                delegate.log("entry","Host-initiated connection ignored: the user disconnected "+((USER_STOP_GRACE_MS-left)/1000)+"s ago")
                stopSelf(startId);return START_NOT_STICKY
            }
        }
        val missing=delegate.missingPermissions()
        if(missing.isNotEmpty()){delegate.log("entry","Required permission missing: "+missing.joinToString());stopSelf(startId);return START_NOT_STICKY}
        try{startForeground(1,notification("准备连接"))}catch(t:Exception){delegate.log("entry","Foreground start failed "+t.javaClass.simpleName);stopSelf(startId);return START_NOT_STICKY}
        if(!controller.active&&sessionJob?.isActive!=true)startSession(hostRequest)
        return START_NOT_STICKY
    }
    /** The application owns preparation and session implementation; the service owns their lifetime. */
    private fun startSession(hostRequest:Boolean){
        sessionJob=scope.launch{
            try {
                controller.start(hostRequest)
                controller.status.distinctUntilChanged().collect{status->
                    getSystemService(NotificationManager::class.java).notify(1,notification(status.text))
                    if(status.failed){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
                }
            }catch(t:CancellationException){throw t}
            catch(t:Exception){
                delegate.log("entry","Session start failed "+t.javaClass.simpleName+" "+(t.message?:""))
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
            }
        }
    }
    private fun notification(text:String):Notification {
        val stop=PendingIntent.getService(this,1,Intent(this,CarlifeConnectService::class.java).setAction(STOP).putExtra("internal_token",token(this)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return delegate.notification(text,stop)
    }
    /** 记下"用户刚刚要求停止"的时刻。存进 prefs，服务被销毁重建后依然有效。 */
    private fun markUserStop(){
        entryPrefs(this).edit().putLong(KEY_USER_STOP_AT,System.currentTimeMillis()).apply()
    }
    /** 用户在本应用里点了连接，闩立即解除。 */
    private fun clearUserStop(){
        entryPrefs(this).edit().remove(KEY_USER_STOP_AT).apply()
    }
    /** 距用户主动断开还剩多少毫秒；<=0 表示没有闩。 */
    private fun userStopRemainingMs():Long {
        val at=entryPrefs(this).getLong(KEY_USER_STOP_AT,0L)
        if(at<=0L)return 0L
        return (at+USER_STOP_GRACE_MS-System.currentTimeMillis()).coerceAtLeast(0L)
    }
    override fun onDestroy(){
        if(activeInstance===this)activeInstance=null
        scope.cancel()
        try{if(::delegate.isInitialized)controller.close()}finally{super.onDestroy()}
    }
    companion object {
        const val CONNECT="io.github.iamr0s.carvex.CONNECT";const val STOP="io.github.iamr0s.carvex.STOP"
        /** 用户主动断开后，忽略"宿主自己找上门"的连接请求的时间窗。 */
        private const val USER_STOP_GRACE_MS=90_000L
        private const val KEY_USER_STOP_AT="user_stop_at"
        private fun entryPrefs(c:Context)=c.getSharedPreferences("entry",Context.MODE_PRIVATE)
        @Synchronized private fun token(c:Context):String {
            val p=c.getSharedPreferences("entry",Context.MODE_PRIVATE)
            return p.getString("token",null)?:UUID.randomUUID().toString().also{p.edit().putString("token",it).apply()}
        }
        fun connect(c:Context){c.startForegroundService(Intent(c,CarlifeConnectService::class.java).setAction(CONNECT).putExtra("internal_token",token(c)))}
        fun disconnect(c:Context){c.startService(Intent(c,CarlifeConnectService::class.java).setAction(STOP).putExtra("internal_token",token(c)))}
        @Volatile private var activeInstance:CarlifeConnectService?=null
        fun setVideoBitrate(value:Int){activeInstance?.controller?.setVideoBitrate(value)}
        /** 手动模拟触发一个 CarWith 键码（按键调节区的“模拟”按钮）。 */
        fun triggerCarWithKey(code:Int){activeInstance?.controller?.sendHostKey(code)}
        fun startCarWithAudioProbe(){
            activeInstance?.let{it.controller.startHostAudioProbe()}
        }
        fun toggleCarPlayAudioTest(){
            activeInstance?.let{it.controller.toggleProtocolAudioTest()}
        }
    }
}
