package io.github.iamr0s.carvex.carplay.core

/** 进程级 + 落盘的 `NO_SYN` 记忆：下一次蓝牙连接前强制重做一次 SDP。
 *
 *  **为什么不能只放在 `SessionController` 的重试循环里**（2026-11-03 真车 build 20047）：
 *  那段 `catch` 从来没执行过 —— 整份 `diagnostic.log` 里 `failure:` 的计数是 0。失败传到那儿
 *  之前，服务已被 `stopSelf()` 销毁、`job` 被取消，异常变成 `CancellationException` 被原样抛掉。
 *
 *  **为什么也不能只放进程内存里**（同晚 build 20048）：真机上这个 app 进程在每次尝试之间就会死掉，
 *  服务是它唯一的组件，`stopSelf()` 之后很快被系统回收。于是 `IapNoSynHint.mark()` 刚落下的标记
 *  下一次尝试就读不到，日志里每次都是 `freshness=unverified`：
 *
 *  ```
 *  16:02:24  [84929d55] iAP NO_SYN: no peer SYN within 7000ms
 *  16:02:42  [559af57e] ... freshness=unverified        ← 换了进程，内存标记没了
 *  ```
 *
 *  所以必须落盘。库模块不认识 `Context`，落盘钩子由 `:app` 在 `BridgeApp.onCreate` 里装一次。
 *
 *  为什么怀疑缓存通道号：`createRfcommSocketToServiceRecord(uuid)` 用的是蓝牙栈里**缓存的 SDP
 *  记录中的 RFCOMM 通道号**，而公开 API 只暴露 UUID 集合、看不到通道号 —— 集合没变推不出通道
 *  没变。车机重启过之后通道号很可能已经改了；表现恰好就是"能连上 RFCOMM、对端却不说 iAP"。
 *  刷新一次 SDP 的代价只有一次最多 5 秒的查询，失败也不改变原有流程。 */
object IapNoSynHint {
    /** 多久之内认为"缓存可能已经作废"。 */
    private const val WINDOW_MS=10*60_000L
    @Volatile private var atMs:Long=0L
    /** 落盘钩子，由 `:app` 装一次。 */
    private class Store(val persist:(Long)->Unit,val read:()->Long)
    @Volatile private var store:Store?=null
    fun install(persist:(Long)->Unit,read:()->Long){store=Store(persist,read)}
    fun mark(){
        val at=System.currentTimeMillis()
        atMs=at
        runCatching{store?.persist?.invoke(at)}
    }
    /** 最近是否发生过 NO_SYN；不消耗标记，一次刷新之后仍可继续命中。 */
    fun recent():Boolean{
        val at=atMs.takeIf{it!=0L}?:runCatching{store?.read?.invoke()}.getOrNull()?:0L
        return at!=0L&&System.currentTimeMillis()-at<WINDOW_MS
    }
    fun clear(){
        atMs=0L
        runCatching{store?.persist?.invoke(0L)}
    }
}
