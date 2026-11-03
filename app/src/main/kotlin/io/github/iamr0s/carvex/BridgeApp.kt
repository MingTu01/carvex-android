package io.github.iamr0s.carvex
import android.Manifest
import android.app.Application
import android.app.Service
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.iamr0s.carvex.sessioncore.BridgeStore
import io.github.iamr0s.carvex.sessioncore.DeviceHistory
import io.github.iamr0s.carvex.host.mixing.service.MixingServiceDelegate
import io.github.iamr0s.carvex.host.mixing.service.MixingServiceOwner
import org.koin.android.ext.koin.androidContext

class BridgeApp:Application(),MixingServiceOwner{
    override fun createMixingServiceDelegate(service:Service):MixingServiceDelegate = AppMixingServiceDelegate(service)

    override fun onCreate(){
        super.onCreate()
        BridgeStore.init(this)
        // NO_SYN 的判据必须落盘：真机上这个进程在每次连接尝试之间就会被回收，内存里的标记
        // 下一次读不到，于是"重连前重做一次 SDP"这个兜底永远不触发。见 IapNoSynHint。
        val sessionPrefs=getSharedPreferences("session",Context.MODE_PRIVATE)
        io.github.iamr0s.carvex.carplay.core.IapNoSynHint.install(
            persist={at->sessionPrefs.edit().putLong("no_syn_at",at).apply()},
            read={sessionPrefs.getLong("no_syn_at",0L)})
        // 连接历史要在任何界面读到它之前装好：首页第一段直接读它的 StateFlow。
        DeviceHistory.init(this)
        // 组装层启动 Koin：协议栈与宿主插件都从这里取；库模块自身不依赖 Koin。
        org.koin.core.context.startKoin {
            androidContext(this@BridgeApp)
            modules(appModules)
        }
        // 宿主注册表仍然显式安装：它只描述"有哪些宿主"，与 DI 容器解耦。
        io.github.iamr0s.carvex.hostregistry.HostRegistry.install(
            *org.koin.core.context.GlobalContext.get().getAll<io.github.iamr0s.carvex.hostregistry.HostPlugin>().toTypedArray())
    }
}
object Permissions {
    /** 连接真正依赖的权限：缺任何一项都不该继续。 */
    fun required()=buildList {
        if(Build.VERSION.SDK_INT>=31)add(Manifest.permission.BLUETOOTH_CONNECT)
        if(Build.VERSION.SDK_INT>=33)add(Manifest.permission.NEARBY_WIFI_DEVICES)
        else {add(Manifest.permission.ACCESS_COARSE_LOCATION);add(Manifest.permission.ACCESS_FINE_LOCATION)}
        // API 37 asks for local network access separately. Only from that version on counts: an
        // older platform does not know the permission, and demanding it there would keep granted()
        // false forever and take the whole entry path down with it.
        if(Build.VERSION.SDK_INT>=37)add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }.toTypedArray()
    /** 不阻塞连接、但影响体验的权限：Android 13 起前台服务的通知也要用户授权，没有它通知栏里
     *  看不到配对与连接的状态，所以放在同一个入口一起要。 */
    fun optional()=buildList {
        if(Build.VERSION.SDK_INT>=33)add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    /** 要申请的完整清单：阻塞性的在前，可选的在后。 */
    fun requested()=required()+optional()
    /** 只有 required() 决定能不能连：拒绝通知权限不该挡住连接本身。 */
    fun granted(c:Context)=missing(c).isEmpty()
    /** Which required permissions are still missing; a refusal has to name them. */
    fun missing(c:Context)=required().filter{c.checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}
    /** 可选权限里还缺哪些。它们不阻塞连接，但要顺带要一次，否则通知栏里一直看不到状态。 */
    fun optionalMissing(c:Context)=optional().filter{c.checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}
}

