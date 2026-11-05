package io.github.iamr0s.carvex.sessioncore

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 跨"尝试"保留宿主的投屏核心。
 *
 * 为什么需要：每次尝试结束（`SessionController.attempt` 的 `finally`）都会释放资源，
 * 宿主实现会在 `close()` 里调 `stopCast()`。小米 CarWith 收到之后会依次走
 * `carlinkDisconnected → CMD_DISCONNECT_CAST → releaseCastCore → displayDisconnected`，
 * 把整个投屏核心拆掉；**紧接着它把我们 forceStop**。
 *
 * 2026-11-05 23:23:30 实测（Wi-Fi 断连后第一次重连）：
 * ```
 * 23:23:30.289  我们 attempt 结束
 * 23:23:30.303  CarlifeConnService: launcher receiver stopCast   ← 只因我们发了这一句
 * 23:23:30.313  forceStopApp:com.baidu.carlife.xiaomi             ← 进程被 SIGKILL
 * 23:23:30.364  releaseCastCore → displayDisconnected
 * 23:23:49.833  CarWith 重新拉起我们（间隔 19.5 秒）
 * ```
 * 进程一死，上层重试循环根本没有第二次机会，失败原因也来不及写进日志——这就是之前
 * 查不到 `failure:` 行、以及"重连要一分钟"的真实成因。
 *
 * 所以重试期间只**推迟** stopCast：投屏核心留着，下一轮尝试可以直接复用宿主状态；
 * 整段会话真的结束了（[release]）再补发，不留下一个永不释放的僵尸投屏。
 */
object CastRetention {
    /** 会话仍在重试中：宿主 `close()` 应当把 stopCast 交给我们推迟，而不是立即发出。 */
    @Volatile
    var retain: Boolean = false
        private set

    private val pending = CopyOnWriteArrayList<() -> Unit>()

    /** 进入可重试的会话循环。 */
    fun begin() {
        retain = true
    }

    /**
     * 记录一个被推迟的"停投屏"动作。
     * 返回 true 表示已接管，调用方不要再自己发；返回 false 表示当前不需要保留，调用方照常发。
     */
    fun defer(action: () -> Unit): Boolean {
        if (!retain) return false
        pending.add(action)
        return true
    }

    /**
     * 会话真正结束：先清标志（后续 close 不再推迟），再补发所有被推迟的动作。
     * 补发用守护线程 + 有限等待，避免宿主无响应时把收尾流程堵死。
     */
    fun release() {
        retain = false
        if (pending.isEmpty()) return
        val actions = pending.toList()
        pending.clear()
        val threads = actions.map { action ->
            Thread({ runCatching { action() } }, "cast-release-deferred").apply {
                isDaemon = true
                start()
            }
        }
        threads.forEach { runCatching { it.join(1_000) } }
    }

    /** 不复发、只清状态：宿主本身已经不可达时用，避免对着死 binder 请求。 */
    fun reset() {
        retain = false
        pending.clear()
    }
}
