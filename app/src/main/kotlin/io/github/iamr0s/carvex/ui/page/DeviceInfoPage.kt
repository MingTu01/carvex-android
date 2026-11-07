package io.github.iamr0s.carvex.ui.page

import io.github.iamr0s.carvex.ui.component.*
import io.github.iamr0s.carvex.ui.model.*
import io.github.iamr0s.carvex.ui.screen.Nav

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.iamr0s.carvex.R
import io.github.iamr0s.carvex.ui.model.DeviceViewModel
import io.github.iamr0s.carvex.ui.model.Link
import io.github.iamr0s.carvex.ui.model.ManagePrefs
import io.github.iamr0s.carvex.ui.model.UiState
import io.github.iamr0s.carvex.ui.tool.LocalAppInsets
import io.github.iamr0s.carvex.ui.tool.toPaddingValues

/**
 * Manage page for the device we are connected (or connecting) to.
 * On a tablet it sits in the right pane and shows an empty state when nothing is connected.
 */
@Composable
fun DeviceInfoPage(
    state: UiState,
    vm: DeviceViewModel,
    nav: Nav,
    showBack: Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val now = rememberNow()
    val focus = LocalFocusManager.current
    val link = state.link
    val deviceId = when (link) {
        is Link.Connected -> link.deviceId
        is Link.Connecting -> link.deviceId
        Link.Idle -> null
    }
    val device = deviceId?.let { state.device(it) }
    val safe = LocalAppInsets.current.content.toPaddingValues()
    val direction = LocalLayoutDirection.current

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(
            start = safe.calculateLeftPadding(direction),
            top = safe.calculateTopPadding(),
            end = safe.calculateRightPadding(direction),
            bottom = safe.calculateBottomPadding() + 16.dp,
        ),
    ) {
        ScreenTitle(device?.name ?: stringResource(R.string.screen_manage), onBack = if (showBack) nav.back else null)

        if (device == null) {
            EmptyCard(stringResource(R.string.screen_no_device), stringResource(R.string.screen_connect_device))
            return@Column
        }

        val connected = link as? Link.Connected
        val method = when (link) {
            is Link.Connected -> link.method
            is Link.Connecting -> link.method
            Link.Idle -> null
        }

        StatusCard(state, onClick = null, onDisconnect = vm::disconnect)


        SectionLabel(stringResource(R.string.section_connection))
        Panel {
            KeyValueRow(stringResource(R.string.field_method), method?.let { stringResource(it.labelRes) }.orEmpty())
            KeyValueRow(stringResource(R.string.field_signal), stringResource(device.signal.labelRes))
            KeyValueRow(
                stringResource(R.string.field_latency),
                connected?.let {
                    if (it.latencyMs > 0) stringResource(R.string.milliseconds_short, it.latencyMs)
                    else stringResource(R.string.latency_pending)
                } ?: stringResource(R.string.latency_pending),
            )
            KeyValueRow(stringResource(R.string.field_address), device.address)
            KeyValueRow(
                stringResource(R.string.field_connected_for),
                connected?.let {
                    val up = ((now - it.since) / 1000).coerceAtLeast(0)
                    stringResource(R.string.connected_for_value, up / 60, up % 60)
                } ?: stringResource(R.string.status_connecting),
            )
        }

        SectionLabel(stringResource(R.string.section_this_device))
        val prefs = state.manage[device.id] ?: ManagePrefs()
        Panel {
            var name by remember(device.id, device.name) { mutableStateOf(device.name) }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(24) },
                label = { Text(stringResource(R.string.field_device_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .onFocusChanged {
                        if (!it.isFocused && name.trim().isNotEmpty() && name.trim() != device.name) {
                            vm.renameDevice(device.id, name)
                        }
                    },
            )
            SwitchRow(stringResource(R.string.auto_connect_device), prefs.autoConnect) { v ->
                vm.updateManage(device.id, "auto_connect=$v") { it.copy(autoConnect = v) }
            }
            SwitchRow(stringResource(R.string.notify_disconnect), prefs.notifyOnDrop) { v ->
                vm.updateManage(device.id, "notify_on_drop=$v") { it.copy(notifyOnDrop = v) }
            }
        }

        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledTonalButton(
                onClick = vm::ping,
                enabled = connected != null,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.action_run_test)) }
            FilledTonalButton(
                onClick = { nav.askMethod(device.id, true) },
                enabled = connected != null,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.action_change_method)) }
        }
        OutlinedButton(
            onClick = vm::disconnect,
            enabled = connected != null,
            border = BorderStroke(1.5.dp, cs.error),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.error),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) { Text(stringResource(R.string.action_disconnect)) }
    }
}
