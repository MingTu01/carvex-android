package io.github.iamr0s.carvex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.iamr0s.carvex.host.mixing.service.MixingBrand
import io.github.iamr0s.carvex.host.mixing.service.MixingServiceDelegate
import io.github.iamr0s.carvex.host.mixing.service.MixingServiceSession
import io.github.iamr0s.carvex.host.mixing.service.MixingSessionStatus
import io.github.iamr0s.carvex.hostregistry.HostRegistry
import io.github.iamr0s.carvex.session.CastSession
import io.github.iamr0s.carvex.session.SessionController
import io.github.iamr0s.carvex.sessioncore.BridgeStore
import io.github.iamr0s.carvex.sessioncore.Stage
import io.github.iamr0s.carvex.sessioncore.progressText
import io.github.iamr0s.carvex.ui.activity.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

/** Composition root: adapts app facilities to the independent CarLife service contract. */
internal class AppMixingServiceDelegate(private val service: Service) : MixingServiceDelegate {
    override val brand = MixingBrand.forFlavor(BuildConfig.FLAVOR)

    private val controller: CastSession = SessionController(service, LocalVideoDisplay, { context, events ->
        // A flavor always selects its own host, including when that host cannot be bound.
        val plugin = HostRegistry.byId(brand.pluginId)
            ?: error("No host plugin registered for flavor ${brand.id}")
        plugin.create(context, events)
    }, GlobalContext.get().get())

    override val session = object : MixingServiceSession {
        override val active: Boolean get() = controller.active
        override val status = BridgeStore.state.map { state ->
            MixingSessionStatus(state.progressText.ifBlank { state.stage.title }, state.stage == Stage.FAILED)
        }

        override suspend fun start(fromHost: Boolean) {
            val options = BridgeStore.options.value
            if (ShizukuBluetoothMac.needed(options.phoneMac) && ShizukuBluetoothMac.granted()) {
                withContext(Dispatchers.IO) { ShizukuBluetoothMac.read() }?.let { mac ->
                    BridgeStore.configure(BridgeStore.options.value.copy(phoneMac = mac))
                    log("entry", "Shizuku read local Bluetooth MAC: $mac")
                }
            }
            controller.start(fromHost)
        }

        override suspend fun recycle() = controller.recycle()

        override suspend fun disconnect() = controller.disconnect()
        override fun close() = controller.close()
        override fun setVideoBitrate(value: Int) = controller.setVideoBitrate(value)
        override fun sendHostKey(code: Int) = controller.triggerCarWithKey(code)
        override fun startHostAudioProbe() = controller.startCarWithAudioProbe()
        override fun toggleProtocolAudioTest() = controller.toggleCarPlayAudioTest()
    }

    init {
        service.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("connection", "车机连接", NotificationManager.IMPORTANCE_LOW))
    }

    override fun missingPermissions(): List<String> = Permissions.missing(service)
    override fun log(tag: String, message: String) = BridgeStore.log(tag, message)

    override fun notification(text: String, disconnect: PendingIntent): Notification {
        val open = PendingIntent.getActivity(service, 0, Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(service, "connection")
            .setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle(service.getString(R.string.app_name))
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_bridge, "断开", disconnect).build()
    }
}
