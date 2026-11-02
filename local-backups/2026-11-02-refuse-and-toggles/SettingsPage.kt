package io.github.iamr0s.carvex.ui.page

import io.github.iamr0s.carvex.ui.component.*
import io.github.iamr0s.carvex.ui.model.*
import io.github.iamr0s.carvex.ui.screen.Nav

import android.bluetooth.BluetoothAdapter
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.iamr0s.carvex.ShizukuBluetoothMac
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
            SwitchRow(stringResource(R.string.experimental_auth), s.experimentalAuth, vm::updateExperimentalAuth)
            Text(
                stringResource(R.string.experimental_auth_supporting),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Text(stringResource(R.string.airplay_security_automatic), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
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
