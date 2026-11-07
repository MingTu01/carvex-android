package io.github.iamr0s.carvex.ui.page

import io.github.iamr0s.carvex.ui.component.*
import io.github.iamr0s.carvex.ui.model.*
import io.github.iamr0s.carvex.ui.screen.Nav

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.iamr0s.carvex.ShizukuBluetoothMac
import io.github.iamr0s.carvex.SplashAsset
import io.github.iamr0s.carvex.ui.model.DeviceViewModel
import io.github.iamr0s.carvex.ui.model.Method
import io.github.iamr0s.carvex.ui.model.ThemeMode
import io.github.iamr0s.carvex.ui.model.UiState
import io.github.iamr0s.carvex.R
import io.github.iamr0s.carvex.ui.tool.LocalAppInsets
import io.github.iamr0s.carvex.ui.tool.toPaddingValues
import kotlin.math.roundToInt

@Composable
fun SettingsPage(
    state: UiState,
    vm: DeviceViewModel,
    nav: Nav,
    modifier: Modifier = Modifier,
) {
    val s = state.settings
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val safe = LocalAppInsets.current.content.toPaddingValues()
    val direction = LocalLayoutDirection.current

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = safe.calculateLeftPadding(direction),
                top = safe.calculateTopPadding(),
                bottom = safe.calculateBottomPadding() + 16.dp,
                end = safe.calculateRightPadding(direction),
            ),
    ) {
        ScreenTitle(stringResource(R.string.nav_settings))


        // Local device name + switches
        Panel {
            var name by remember(s.deviceName) { mutableStateOf(s.deviceName) }
            fun commit() {
                val clean = name.trim().ifEmpty { s.deviceName }
                name = clean
                if (clean != s.deviceName) {
                    vm.updateSettings("device.name=\"$clean\"") { it.copy(deviceName = clean) }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(24) },
                label = { Text(stringResource(R.string.field_device_name)) },
                supportingText = { Text(stringResource(R.string.device_name_supporting)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .onFocusChanged { if (!it.isFocused) commit() },
            )
            var phoneMac by remember(s.phoneMac) { mutableStateOf(s.phoneMac) }
            OutlinedTextField(
                value = phoneMac,
                onValueChange = { value ->
                    val next = value.uppercase().take(17)
                    phoneMac = next
                    if (next.isEmpty() || BluetoothAdapter.checkBluetoothAddress(next)) {
                        vm.updatePhoneMac(next)
                    }
                },
                label = { Text(stringResource(R.string.phone_mac)) },
                supportingText = { Text(stringResource(R.string.phone_mac_supporting)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            )
            if (remember { ShizukuBluetoothMac.available() }) {
                OutlinedButton(
                    onClick = vm::readPhoneMacWithShizuku,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                ) { Text(stringResource(R.string.read_phone_mac)) }
            }
            // 实验认证、静帧重发、音频投送、音频明文这四个开关已经从界面上去掉：
            // 前两个是"必须开"，第三个是这个版本的正常功能，第四个只是排查密文问题的一次性诊断。
            // 摆在这里的结果要么是用户误关后没声音没画面，要么是没人知道它干什么。
            // 对应 Options 的默认值已改为开；存量用户由 DefaultOnSettingsMigration 一次性提升。
            // 通知使用权入口已经撤掉：它只是车机显示歌名/歌手的数据来源，属于"能开就开、
            // 开不了也不该妨碍用"的一类，摆在设置里只会让人以为关掉它就会出问题。
            // 权限本身照旧读取，系统设置里授予之后仍然生效。
            SwitchRow(stringResource(R.string.settings_auto_reconnect), s.autoReconnect) { v ->
                vm.updateSettings("auto_reconnect=$v") { it.copy(autoReconnect = v) }
            }
            SwitchRow(stringResource(R.string.settings_connect_on_launch), s.connectOnLaunch) { v ->
                vm.updateSettings("connect_on_launch=$v") { it.copy(connectOnLaunch = v) }
            }
            SwitchRow(stringResource(R.string.settings_keep_screen_on), s.keepScreenOn) { v ->
                vm.updateSettings("keep_screen_on=$v") { it.copy(keepScreenOn = v) }
            }
        }

        // Slider
        Panel {
            var value by remember(s.scanIntervalSec) { mutableFloatStateOf(s.scanIntervalSec.toFloat()) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row {
                    Text(
                        stringResource(R.string.settings_scan_interval),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        stringResource(R.string.seconds_short, value.roundToInt()),
                        color = cs.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 1f..60f,
                    steps = 58, // 1, 2, ... 60
                    onValueChangeFinished = {
                        val seconds = value.roundToInt()
                        vm.updateSettings("scan.interval=${seconds}s") { it.copy(scanIntervalSec = seconds) }
                    },
                )
            }
        }

        // Multi-select
        SectionLabel(stringResource(R.string.settings_connect_using))
        Panel {
            Method.entries.forEach { m ->
                CheckRow(stringResource(m.labelRes), m in s.methods) { vm.toggleMethod(m) }
            }
        }

        // Theme
        SectionLabel(stringResource(R.string.settings_theme))
        Panel {
            SingleChoiceSegmentedButtonRow(Modifier
                .fillMaxWidth()
                .padding(12.dp)) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = s.theme == mode,
                        onClick = { vm.updateSettings("theme=${mode.name}") { it.copy(theme = mode) } },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ThemeMode.entries.size
                        ),
                    ) { Text(stringResource(mode.labelRes)) }
                }
            }
        }

        // 「三路音频分离」开关已经撤掉。它从来没有真正生效过 —— 这个接收端能发 PCM 的只有
        // `100/media` 和 `101/default` 两条，第三条路（`101/compatibility`）SETUP 一律 400；
        // 而每一次为第三条路发出的请求都会把车机的输入焦点拿走，导致触屏失效。
        // 现在会话中途根本不建任何音频流（见 SessionController.requestAudio），
        // 小爱的声音并进活着的通道里出来 —— 音量分不开，但触屏和声音都在。

        // 画质：车机画面糊的时候，剩下的唯一变量就是码率。档位直接乘在像素率上，
        // 所以换分辨率时码率会跟着走，不会出现"画布变大、每像素变少"的二次退化。
        SectionLabel("画质（码率）")
        Panel {
            val labels = listOf("标准", "清晰", "极致")
            SingleChoiceSegmentedButtonRow(Modifier
                .fillMaxWidth()
                .padding(12.dp)) {
                labels.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = s.videoQuality == index,
                        onClick = { vm.setVideoQuality(index) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = labels.size),
                    ) { Text(label) }
                }
            }
            Text(
                "码率按像素数换算：标准 0.13、清晰 0.20、极致 0.30 bit/像素/帧。" +
                    "720p/30 分别约 3.6 / 5.5 / 8.3 Mbps。画面发虚就往上调一档；" +
                    "调高后若出现卡顿，说明链路带宽不够，调回来即可。改动在下次连接车机时生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // 启动画面素材：连上车机时车机屏幕上先放的那段画面。
        // 上传的文件复制进私有目录（SAF 的授权只在当次进程内有效，直接引用原文件会在下次
        // 连接时失效），5 MB 以内；清掉就回到内置视频。
        SectionLabel("启动画面素材")
        Panel {
            val app = LocalContext.current.applicationContext
            var refresh by remember { mutableIntStateOf(0) }
            var message by remember { mutableStateOf<String?>(null) }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    val error = SplashAsset.save(app, uri)
                    message = if (error == null) "已替换：下次连接车机时生效" else error
                    refresh++
                }
            }
            ListItem(
                headlineContent = { Text("当前使用") },
                supportingContent = { Text(remember(refresh) { SplashAsset.describe(app) }) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("video/*", "image/*")) },
                    modifier = Modifier.weight(1f),
                ) { Text("选择视频或图片") }
                OutlinedButton(
                    onClick = {
                        SplashAsset.clear(app)
                        message = "已恢复内置视频"
                        refresh++
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("恢复默认") }
            }
            Text(
                "支持 mp4 / webm / mov 视频与 jpg / png / webp 图片，单个文件不超过 5 MB。" +
                    "视频静音播放；图片按原比例完整显示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        // Link to the log page (phones only; tablets show the log in the right pane)
        Panel {
            ListItem(
                headlineContent = { Text(stringResource(R.string.screen_logs)) },
                supportingContent = { Text(stringResource(R.string.settings_logs_supporting)) },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(onClick = nav.openLogs),
            )
        }
        Panel {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_clear_logs)) },
                supportingContent = { Text(stringResource(R.string.settings_clear_logs_supporting)) },
                trailingContent = {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = stringResource(R.string.settings_clear_logs),
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(
                    enabled = state.logs.isNotEmpty(),
                    onClick = vm::clearLogs,
                ),
            )
        }
    }
}
