package io.github.iamr0s.carvex.carplay.core

/** 进程级的 `NO_SYN` 记忆：下一次蓝牙连接前强制重做一次 SDP。
 *
 *  **为什么必须记在这里**（2026-11-03 真车 build 20047 抓到）：`SessionController` 的重试循环里
 *  那段 `catch` 从来没执行过 —— 整份 `diagnostic.log` 里 `failure:` 的计数是 0。原因是失败传到
 *  那儿之前，服务已经被 `stopSelf()` 销毁、`job` 被取消，异常变成了 `CancellationException`
 *  被原样抛掉。于是 `refreshSdpAfterNoSyn` 永远是 false，日志里每次都是 `freshness=unverified`：
 *
 *  ```
 *  15:56:07.997  [972ac97a] iAP NO_SYN: no peer SYN within 7000ms
 *  15:56:11.355  [entry] Intent extras: ...            ← CarWith 又拉起一次服务
 *  15:56:11.381  [2420cedb] ... freshness=unverified   ← 刷新依旧没发生
 *  ```
 *
 *  所以判据必须写在做判定的那一层：`IapLink` 判出 NO_SYN 的那一刻。
 *
 *  为什么怀疑缓存通道号：`createRfcommSocketToServiceRecord(uuid)` 用的是蓝牙栈里**缓存的 SDP
 *  记录中的 RFCOMM 通道号**，而公开 API 只暴露 UUID 集合、看不到通道号 —— 集合没变推不出通道没变。
 *  车机今天重启过多次，通道号很可能已经变了；表现恰好就是"能连上 RFCOMM、对端却不说 iAP"。
 *  刷新一次 SDP 的代价只有一次最多 5 秒的查询，失败也不改变原有流程。 */
object IapNoSynHint {
    /** 多久之内认为"缓存可能已经作废"。 */
    private const val WINDOW_MS=10*60_000L
    @Volatile private var atMs:Long=0L
    fun mark(){atMs=System.currentTimeMillis()}
    /** 最近是否发生过 NO_SYN；不消耗标记，一次刷新之后仍可继续命中。 */
    fun recent():Boolean{
        val at=atMs
        return at!=0L&&System.currentTimeMillis()-at<WINDOW_MS
    }
    fun clear(){atMs=0L}
}
