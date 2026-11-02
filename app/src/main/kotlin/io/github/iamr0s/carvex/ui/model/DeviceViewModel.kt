package io.github.iamr0s.carvex.ui.model

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.iamr0s.carvex.R
import io.github.iamr0s.carvex.ShizukuBluetoothMac
import io.github.iamr0s.carvex.sessioncore.BridgeStore
import io.github.iamr0s.carvex.sessioncore.Stage
import io.github.iamr0s.carvex.sessioncore.ConnectionMode
import io.github.iamr0s.carvex.sessioncore.DeviceHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private fun ts(): String = LocalTime.now().format(TS)

/** View model for the DeviceApp-style UI backed by the real bridge session. */
class DeviceViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app.applicationContext
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val transport: DeviceTransport = RealDeviceTransport(app)
    private val _state = MutableStateFlow(UiState(settings = loadSettings()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** One-shot messages for the snackbar. */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private data class ConnectionIntent(val deviceId: Int, val method: Method)
    private var selected: ConnectionIntent? = null
    private var nextLogId = 0L
    private var connectJob: Job? = null

    init {
        observeBridgeState()
        observeBridgeLogs()
        BridgeStore.log("ui", appContext.getString(R.string.log_app_started))
        refresh()
    }

    fun say(message: String) {
        _events.tryEmit(message)
    }

    /** Subscribe to the real SessionController state instead of inventing a local connected flag. */
    private fun observeBridgeState() {
        viewModelScope.launch {
            BridgeStore.state.collect { session ->
                applySessionState(session)
            }
        }
    }

    private fun applySessionState(session: io.github.iamr0s.carvex.sessioncore.SessionState) {
        val intent = selected ?: resolveIntent()
        val before = _state.value
        _state.update { it.withSession(session, intent?.deviceId, intent?.method ?: Method.Bluetooth) }
        val after = _state.value.link
        if (after is Link.Connected && before.link !is Link.Connected) {
            _state.value.device(after.deviceId)?.let { DeviceHistory.record(it.name, it.address) }
        }
        if (session.stage == Stage.FAILED && before.link !is Link.Idle) {
            say(session.error ?: session.detail)
        }
        if (session.stage == Stage.IDLE) selected = null
    }

    /** BridgeStore is the single diagnostic source; the UI log page renders its entries directly. */
    private fun observeBridgeLogs() {
        viewModelScope.launch {
            BridgeStore.logs.collect { raw ->
                _state.update { it.copy(logs = toLogGroups(raw)) }
            }
        }
    }

    private fun resolveIntent(): ConnectionIntent? {
        val target = BridgeStore.options.value.target.trim()
        if (target.isBlank()) return null
        val device = _state.value.devices.firstOrNull { it.address.equals(target, true) } ?: return null
        return ConnectionIntent(device.id, Method.Bluetooth).also { selected = it }
    }

    // ---------------------------------------------------------------- scan / connect

    fun refresh() {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true) }
        viewModelScope.launch {
            runCatching { transport.scan() }
                .onSuccess { found ->
                    _state.update { current ->
                        val merged = found.map { fresh ->
                            current.devices.firstOrNull { it.id == fresh.id }
                                ?.let { fresh.copy(name = it.name) } ?: fresh
                        }
                        current.copy(
                            devices = merged,
                            scanning = false,
                            lastScan = System.currentTimeMillis(),
                        )
                    }
                    applySessionState(BridgeStore.state.value)
                    BridgeStore.log("ui", appContext.getString(R.string.log_scan_result, found.size))
                }
                .onFailure { error ->
                    _state.update { it.copy(scanning = false) }
                    BridgeStore.log(
                        "ui",
                        "${appContext.getString(R.string.scan_failed)}: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
        }
    }

    /** Start the real foreground service and let BridgeStore drive the resulting state. */
    fun connect(deviceId: Int, method: Method) {
        val current = _state.value
        if (current.link is Link.Connecting) return
        if (method !in RealDeviceTransport.supportedMethods) {
            say(appContext.getString(R.string.method_not_supported, appContext.getString(method.labelRes)))
            return
        }
        val device = current.device(deviceId) ?: return
        selected = ConnectionIntent(deviceId, method)
        _state.update { it.copy(
            link = Link.Connecting(deviceId, method),
            progress = ConnectionProgress(stage = Stage.HOST, detail = Stage.HOST.title),
        ) }
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            val previous = (current.link as? Link.Connected)?.let { current.device(it.deviceId) }
            val group = newGroup(
                appContext.getString(
                    R.string.log_connect_via,
                    device.name,
                    appContext.getString(method.labelRes),
                ),
            )
            try {
                // CarlifeConnectService serializes stop/start. Wait for the old controller to
                // finish before requesting a new session, otherwise the service intentionally
                // ignores the second request while disconnecting.
                if (previous != null) {
                    transport.disconnect(previous)
                    withTimeoutOrNull(15_000) {
                        BridgeStore.state.filter { it.stage == Stage.IDLE }.first()
                    }
                }
                // The UI keeps the legacy Method key for preference migration, but the
                // transport boundary receives the protocol/carrier pair.
                transport.connect(device, method.mode)
                appendLog(group, "INFO" to "real session requested target=${device.address}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appendLog(group, "ERROR" to "connect failed: ${error.message ?: error.javaClass.simpleName}")
                _state.update { it.copy(
                    link = Link.Idle,
                    progress = ConnectionProgress(stage = Stage.FAILED, detail = error.message ?: Stage.FAILED.title),
                ) }
                say(error.message ?: appContext.getString(R.string.device_connect_failed, device.name))
            }
        }
    }

    fun disconnect() {
        val link = _state.value.link
        val deviceId = when (link) {
            is Link.Connected -> link.deviceId
            is Link.Connecting -> link.deviceId
            Link.Idle -> return
        }
        val device = _state.value.device(deviceId) ?: return
        if (link is Link.Connecting) connectJob?.cancel()
        viewModelScope.launch {
            runCatching { transport.disconnect(device) }
                .onSuccess { BridgeStore.log("ui", appContext.getString(R.string.log_disconnect_device, device.name)) }
                .onFailure { error -> say(error.message ?: appContext.getString(R.string.disconnect_failed)) }
        }
    }

    /** There is no truthful standalone ping in the current CarLife session API. */
    fun ping() {
        val link = _state.value.link as? Link.Connected ?: return
        BridgeStore.log(
            "ui",
            appContext.getString(
                R.string.log_test_device,
                _state.value.device(link.deviceId)?.name ?: appContext.getString(R.string.status_no_device),
            ),
        )
        say(appContext.getString(R.string.session_ping_unavailable))
    }

    fun renameDevice(id: Int, name: String) {
        val clean = name.trim().ifEmpty { return }
        _state.update { s -> s.copy(devices = s.devices.map { if (it.id == id) it.copy(name = clean) else it }) }
        BridgeStore.log("ui", appContext.getString(R.string.log_settings_changed))
    }

    fun updatePhoneMac(raw: String) {
        val mac = raw.trim().uppercase()
        if (mac.isNotEmpty() && !BluetoothAdapter.checkBluetoothAddress(mac)) {
            say(appContext.getString(R.string.phone_mac_invalid))
            return
        }
        if (mac == _state.value.settings.phoneMac) return
        BridgeStore.configure(BridgeStore.options.value.copy(phoneMac = mac))
        _state.update { it.copy(settings = it.settings.copy(phoneMac = mac)) }
    }

    fun updateExperimentalAuth(enabled: Boolean) {
        if (enabled == _state.value.settings.experimentalAuth) return
        BridgeStore.configure(BridgeStore.options.value.copy(experiment = enabled))
        _state.update { it.copy(settings = it.settings.copy(experimentalAuth = enabled)) }
    }

    /** 静帧重发。开关直接落到会话选项里，下一次连接生效（不打断当前会话）。 */
    fun updateFrontSurface(enabled: Boolean) {
        if (enabled == _state.value.settings.frontSurface) return
        BridgeStore.configure(BridgeStore.options.value.copy(frontSurface = enabled))
        _state.update { it.copy(settings = it.settings.copy(frontSurface = enabled)) }
    }

    /** 音频投送。打开才建立 CarPlay 音频流（默认关）。改动下一次连接生效。 */
    fun updateAudioEnabled(enabled: Boolean) {
        if (enabled == _state.value.settings.audioEnabled) return
        BridgeStore.configure(BridgeStore.options.value.copy(audioEnabled = enabled))
        _state.update { it.copy(settings = it.settings.copy(audioEnabled = enabled)) }
    }

    /** MFi/SAP 音频明文直发（诊断用）。默认关，改动下一次连接生效。 */
    fun updateAudioPlaintext(enabled: Boolean) {
        if (enabled == _state.value.settings.audioPlaintext) return
        BridgeStore.configure(BridgeStore.options.value.copy(audioPlaintext = enabled))
        _state.update { it.copy(settings = it.settings.copy(audioPlaintext = enabled)) }
    }

    fun readPhoneMacWithShizuku() {
        viewModelScope.launch {
            if (!ShizukuBluetoothMac.available()) {
                say(appContext.getString(R.string.shizuku_not_available))
                return@launch
            }
            if (!ShizukuBluetoothMac.granted()) {
                withContext(Dispatchers.Main.immediate) { ShizukuBluetoothMac.request() }
                say(appContext.getString(R.string.shizuku_permission_requested))
                return@launch
            }
            val mac = withContext(Dispatchers.IO) { ShizukuBluetoothMac.read() }
            if (mac == null) {
                say(appContext.getString(R.string.phone_mac_read_failed))
            } else {
                updatePhoneMac(mac)
                say(appContext.getString(R.string.phone_mac_read))
            }
        }
    }

    fun updateManage(deviceId: Int, label: String, change: (ManagePrefs) -> ManagePrefs) {
        _state.update { s ->
            s.copy(manage = s.manage + (deviceId to change(s.manage[deviceId] ?: ManagePrefs())))
        }
        BridgeStore.log("ui", appContext.getString(R.string.log_settings_changed))
    }

    // ---------------------------------------------------------------- settings

    fun updateSettings(label: String, change: (Settings) -> Settings) {
        val next = change(_state.value.settings).let {
            it.copy(scanIntervalSec = it.scanIntervalSec.coerceIn(1, 60))
        }
        saveSettings(next)
        _state.update { it.copy(settings = next) }
        BridgeStore.log("ui", appContext.getString(R.string.log_settings_changed))
    }

    fun toggleMethod(method: Method) {
        if (method !in RealDeviceTransport.supportedMethods) {
            say(appContext.getString(R.string.method_not_supported, appContext.getString(method.labelRes)))
            return
        }
        val current = _state.value.settings.methods
        if (method in current && current.size == 1) {
            say(appContext.getString(R.string.keep_one_method))
            return
        }
        updateSettings("method.${method.label}=${method !in current}") {
            it.copy(methods = if (method in current) current - method else current + method)
        }
    }

    private fun loadSettings() = Settings(
        // 默认值取本机设备名（系统设置里的名字，取不到则 Build.MODEL），不再依赖 strings.xml。
        deviceName = prefs.getString("name", null)?.takeIf{it.isNotBlank()}
            ?: io.github.iamr0s.carvex.sessioncore.Options.localDeviceName(appContext),
        phoneMac = BridgeStore.options.value.phoneMac,
        experimentalAuth = BridgeStore.options.value.experiment,
        frontSurface = BridgeStore.options.value.frontSurface,
        audioPlaintext = BridgeStore.options.value.audioPlaintext,
        audioEnabled = BridgeStore.options.value.audioEnabled,
        autoReconnect = prefs.getBoolean("auto", true),
        connectOnLaunch = prefs.getBoolean("launch", false),
        keepScreenOn = prefs.getBoolean("awake", true),
        scanIntervalSec = prefs.getInt("interval", 15).coerceIn(1, 60),
        methods = prefs.getStringSet("methods", null)
            ?.mapNotNull { stored ->
                // Settings contain only protocol/carrier ids, for example carplay-bluetooth.
                val mode = ConnectionMode.parse(stored)
                Method.entries.firstOrNull { it.mode == mode }
            }
            ?.toSet()
            ?.intersect(RealDeviceTransport.supportedMethods)
            ?.takeIf { it.isNotEmpty() }
            ?: RealDeviceTransport.supportedMethods,
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "System") ?: "System") }
            .getOrDefault(ThemeMode.System),
    )

    private fun saveSettings(s: Settings) {
        prefs.edit()
            .putString("name", s.deviceName)
            .putBoolean("auto", s.autoReconnect)
            .putBoolean("launch", s.connectOnLaunch)
            .putBoolean("awake", s.keepScreenOn)
            .putInt("interval", s.scanIntervalSec)
            .putStringSet("methods", s.methods.map { it.mode.id }.toSet())
            .putString("theme", s.theme.name)
            .apply()
    }

    // ---------------------------------------------------------------- logs

    fun toggleGroup(id: Long) {
        _state.update { s -> s.copy(logs = s.logs.map { if (it.id == id) it.copy(open = !it.open) else it }) }
    }

    fun setAllGroups(open: Boolean) {
        _state.update { s -> s.copy(logs = s.logs.map { it.copy(open = open) }) }
    }

    fun clearLogs() {
        BridgeStore.clearLogs()
        say(appContext.getString(R.string.log_cleared))
    }

    /** Raw retained diagnostics, not the 400-line UI preview. Disk reads stay off the UI. */
    suspend fun logText(): String = withContext(Dispatchers.IO) { BridgeStore.export() }

    private fun newGroup(title: String, vararg lines: Pair<String, String>): Long {
        val id = nextLogId++
        val suffix = lines.joinToString(" ") { "${it.first} ${it.second}" }
        BridgeStore.log("ui", if (suffix.isBlank()) title else "$title $suffix")
        return id
    }

    private fun appendLog(id: Long, vararg lines: Pair<String, String>) {
        lines.forEach { (level, message) -> BridgeStore.log("ui", "$level $message") }
    }

    private fun toLogGroups(raw: List<String>): List<LogGroup> {
        if (raw.isEmpty()) return emptyList()
        val lines = raw.takeLast(400).map { line ->
            val message = line.substringAfter("] ", line)
            val level = when {
                message.startsWith("ERROR", true) || message.contains("failed", true) -> "ERROR"
                message.startsWith("WARN", true) || message.contains("warning", true) -> "WARN"
                else -> "INFO"
            }
            LogLine(line.substringBefore(" [").takeLast(12), level, message)
        }
        return listOf(
            LogGroup(
                id = 0L,
                time = lines.lastOrNull()?.time.orEmpty().take(5),
                title = appContext.getString(R.string.log_title),
                lines = lines,
                open = true,
            ),
        )
    }
}
