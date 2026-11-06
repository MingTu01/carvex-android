package io.github.iamr0s.carvex.ui.model

import androidx.annotation.StringRes
import io.github.iamr0s.carvex.R
import io.github.iamr0s.carvex.sessioncore.ConnectionMode

/** How the phone talks to a device. */
enum class Method(
    val hint: String,
    @StringRes val labelRes: Int,
    @StringRes val hintRes: Int,
) {
    Bluetooth("Nearby, low power", R.string.method_bluetooth, R.string.method_bluetooth_hint),
    WiFi("Same network, fast", R.string.method_wifi, R.string.method_wifi_hint),
    Usb("Cable, most reliable", R.string.method_usb, R.string.method_usb_hint);

    /** The protocol/carrier pair, rather than a carrier pretending to be a protocol. */
    val mode: ConnectionMode
        get() = when (this) {
            Bluetooth -> ConnectionMode.CARPLAY_BLUETOOTH
            WiFi -> ConnectionMode.CARPLAY_WIFI
            Usb -> ConnectionMode.CARPLAY_USB
        }

    /** UI-facing label: protocol and carrier are always visible together. */
    val displayName: String get() = mode.displayName

    /** Compatibility accessor for existing log/settings code. */
    val label: String get() = displayName
}

enum class Signal(val label: String, @StringRes val labelRes: Int) {
    Strong("Strong signal", R.string.signal_strong),
    Medium("Medium signal", R.string.signal_medium),
    Weak("Weak signal", R.string.signal_weak),
    Unknown("Paired device", R.string.signal_paired),
}

data class Device(
    val id: Int,
    val name: String,
    val signal: Signal,
    /** Real Bluetooth address returned by the framework. */
    val address: String,
)

/** The single connection the app can hold: only one device at a time. */
sealed interface Link {
    data object Idle : Link
    data class Connecting(val deviceId: Int, val method: Method) : Link
    data class Connected(
        val deviceId: Int,
        val method: Method,
        val latencyMs: Int,
        val since: Long,
    ) : Link
}

data class LogLine(val time: String, val level: String, val message: String)

data class LogGroup(
    val id: Long,
    val time: String,
    val title: String,
    val lines: List<LogLine>,
    val open: Boolean,
)

enum class ThemeMode(@StringRes val labelRes: Int) {
    System(R.string.theme_system),
    Light(R.string.theme_light),
    Dark(R.string.theme_dark),
}

data class Settings(
    val deviceName: String = "",
    val phoneMac: String = "",
    val autoReconnect: Boolean = true,
    val connectOnLaunch: Boolean = false,
    val keepScreenOn: Boolean = true,
    val scanIntervalSec: Int = 15,
    val methods: Set<Method> = setOf(Method.Bluetooth),
    val theme: ThemeMode = ThemeMode.System,
    // 实验认证 / 静帧重发 / 音频投送 / 音频明文 曾有对应的界面开关，现已在 UI 上去掉：
    // 前两个必须开、第三个是正常功能、第四个只是一次性诊断。它们仍留在 [BridgeStore] 的
    // Options 里（要落盘、要能在 run-as 下手改），只是不再经过 UI 状态。
)

/** Per-device options shown on the manage page. */
data class ManagePrefs(val autoConnect: Boolean = true, val notifyOnDrop: Boolean = true)

data class UiState(
    val devices: List<Device> = emptyList(),
    val link: Link = Link.Idle,
    val progress: ConnectionProgress = ConnectionProgress(),
    val scanning: Boolean = false,
    val lastScan: Long = System.currentTimeMillis(),
    val settings: Settings = Settings(),
    val logs: List<LogGroup> = emptyList(),
    val manage: Map<Int, ManagePrefs> = emptyMap(),
) {
    fun device(id: Int): Device? = devices.firstOrNull { it.id == id }
}
