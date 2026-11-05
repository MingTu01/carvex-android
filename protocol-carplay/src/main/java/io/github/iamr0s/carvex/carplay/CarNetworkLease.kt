package io.github.iamr0s.carvex.carplay

import android.content.Context
import io.github.iamr0s.carvex.sessioncore.Resources

/**
 * 车机 Wi-Fi 的**会话级**租约：它跟着"整段会话"活，不跟着"某一次尝试"死。
 *
 * 为什么必须这样：`WifiNetworkSpecifier` 请求出来的网络一旦撤掉，CarWith 会立刻判定
 * "投屏结束了"，于是 `releaseCastCore` 并让车机把无线 CarPlay 子系统整个拆掉再重建。
 * 真车 2026-11-05 22:47 的实测代价：
 *
 * ```
 * 22:47:2x  我们放开租约（一次尝试失败）
 * 22:47:19  连车机 7000 端口 → ECONNREFUSED     ← 车机的 AirPlay 服务整个不在了
 * 22:47:25  5702 发出去，车机不 ACK              ← 服务回来了但还占着旧会话
 * 22:48:23  5702 才被 ACK                        ← 旧会话这时才释放
 * ```
 *
 * 也就是说：**失败一次，就要赔掉一分半钟，而赔掉的正是重试要等的那段时间**。
 * 而 CarWith 自己的预算只有 40 秒——这 40 秒里如果网络一直被我们举着，
 * 它就不会介入，我们可以在同一个进程里一直重试到它松口，而不是每秒被强杀一次。
 *
 * 释放时机只有一个：整段会话真的结束了（成功跑完，或者重试链判定终止）。
 */
class CarNetworkLease(private val context:Context,private val log:(String)->Unit):AutoCloseable {
    /** 回调登记在这里，所以它会活过 [io.github.iamr0s.carvex.session.Resources] 的每轮清理。 */
    private val callbacks=Resources()
    private val closed=java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var ssid:String?=null
    @Volatile private var path:NetworkPath?=null

    /** 有可用的旧租约就复用，否则请求一条新的。 */
    suspend fun join(bootstrap:WifiBootstrap,onFailure:(Throwable)->Unit):NetworkPath {
        check(!closed.get()){"Wi-Fi lease already closed"}
        // 复用只在"同一个 SSID"时成立：换车机就该重新协商，不能拿旧网络硬套。
        val reuse={ (if(ssid==bootstrap.ssid)path else null) }
        val fresh=Bootstrap.network(context,bootstrap,callbacks,log,onFailure,lease=callbacks,reuse=reuse)
        ssid=bootstrap.ssid;path=fresh
        return fresh
    }

    override fun close(){
        if(!closed.compareAndSet(false,true))return
        path=null
        log("releasing the session-scoped Wi-Fi lease; CarWith will now see the cast end")
        callbacks.close()
    }
}
