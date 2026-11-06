package io.github.iamr0s.carvex.sessioncore

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class DefaultOnSettingsMigrationTest {
    /** 只实现迁移真正用到的那几个方法，别的一律报错——防止将来顺手写了无关的键。 */
    private class Store(initial: Map<String, Any>) {
        val values = initial.toMutableMap()
        private val staged = mutableMapOf<String, Any>()
        val editor: SharedPreferences.Editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putBoolean" -> { staged[args!![0] as String] = args[1] as Boolean; proxy }
                "putInt" -> { staged[args[0] as String] = args[1] as Int; proxy }
                "apply" -> { values.putAll(staged); staged.clear(); null }
                else -> error("迁移不该调用 Editor." + method.name)
            }
        } as SharedPreferences.Editor
        val prefs: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getInt" -> (values[args!![0] as String] as? Int) ?: (args[1] as Int)
                "getBoolean" -> (values[args[0] as String] as? Boolean) ?: (args[1] as Boolean)
                "edit" -> editor
                else -> error("迁移不该调用 SharedPreferences." + method.name)
            }
        } as SharedPreferences
    }

    /** 老用户盘上躺着的 false 必须被翻成 true —— 否则改默认值对他们是空操作。 */
    @Test fun legacyFalseIsPromotedAndEverythingElseIsLeftAlone() {
        val store = Store(mapOf(
            "audioEnabled" to false, "experiment" to false,
            "target" to "synthetic-car", "dpi" to 200, "frontSurface" to true,
        ))
        assertTrue(DefaultOnSettingsMigration.applyTo(store.prefs))
        assertEquals(mapOf(
            "audioEnabled" to true, "experiment" to true,
            "target" to "synthetic-car", "dpi" to 200, "frontSurface" to true,
            "defaultsRevision" to DefaultOnSettingsMigration.REVISION,
        ), store.values)
    }

    /** 新装：键根本不存在，就别往盘上写一堆和默认值相同的字面量。 */
    @Test fun freshInstallWritesOnlyTheRevisionMarker() {
        val store = Store(emptyMap())
        assertTrue(DefaultOnSettingsMigration.applyTo(store.prefs))
        assertEquals(mapOf("defaultsRevision" to DefaultOnSettingsMigration.REVISION), store.values)
    }

    /** 跑过一次之后手工改回去，不能被下次启动再覆盖 —— 一次性是「补欠账」，不是「锁死」。 */
    @Test fun secondRunIsANoOpAndRespectsLaterManualChanges() {
        val store = Store(emptyMap())
        DefaultOnSettingsMigration.applyTo(store.prefs)
        store.values["audioEnabled"] = false
        assertFalse(DefaultOnSettingsMigration.applyTo(store.prefs))
        assertEquals(mapOf(
            "audioEnabled" to false,
            "defaultsRevision" to DefaultOnSettingsMigration.REVISION,
        ), store.values)
    }

    /** 已经全是 true 的老用户，除了标记位不该产生任何写入。 */
    @Test fun alreadyOnSettingsAreNotRewritten() {
        val store = Store(mapOf("audioEnabled" to true, "experiment" to true, "dpi" to 160))
        assertTrue(DefaultOnSettingsMigration.applyTo(store.prefs))
        assertEquals(mapOf(
            "audioEnabled" to true, "experiment" to true, "dpi" to 160,
            "defaultsRevision" to DefaultOnSettingsMigration.REVISION,
        ), store.values)
    }

    /** 迁移与 Options 的默认值必须一致，否则「默认开」这句话只对新装成立。 */
    @Test fun promotedKeysActuallyDefaultToOn() {
        val defaults = Options()
        assertTrue("audioEnabled 默认必须为开，否则车机没声音", defaults.audioEnabled)
        assertTrue("experiment 默认必须为开，否则会话建不起来", defaults.experiment)
    }
}
