package io.github.iamr0s.carvex.sessioncore

import android.content.SharedPreferences

/**
 * 一次性把「本来就该开着」的开关提升到开。
 *
 * 为什么光改 [Options] 的默认值不够：
 * 默认值只对新装生效。已经装过旧版本的用户，`options.xml` 里早就落了
 * `audioEnabled=false`、`experiment=false` 这两个**字面值**，
 * 改 `Options(audioEnabled = true)` 对他们完全不发生作用 ——
 * 他们升级后依然是关的，而且现在界面上已经没有这个开关可点了，
 * 表现就是「装完新版本，车机还是没声音，设置里也找不到能开的地方」。
 *
 * 用 [REVISION] 做闸门，只搬一次。搬完之后用户自己在 `run-as` 里改回去，
 * 不会被下次启动再覆盖 —— 一次性的意思是「补上历史欠账」，不是「强制锁死」。
 */
internal object DefaultOnSettingsMigration {
    /** 当前「默认开」的修订号。往后再有同类调整就在这里 +1 并补一条提升。 */
    const val REVISION = 1
    private const val REVISION_KEY = "defaultsRevision"

    /** 这几个键在界面上已经没有开关了，只能靠这里补齐。 */
    private val promoted = listOf("audioEnabled", "experiment")

    /**
     * @return 本次是否真的走了迁移（便于日志/测试断言）。
     *
     * 幂等：修订号已到 [REVISION] 就直接返回，不碰任何键。
     */
    fun applyTo(p: SharedPreferences): Boolean {
        if (p.getInt(REVISION_KEY, 0) >= REVISION) return false
        val editor = p.edit()
        // 键不存在时 getBoolean 给 true：新装设备不必写一堆与默认值相同的字面量。
        promoted.forEach { key -> if (!p.getBoolean(key, true)) editor.putBoolean(key, true) }
        editor.putInt(REVISION_KEY, REVISION).apply()
        return true
    }
}
