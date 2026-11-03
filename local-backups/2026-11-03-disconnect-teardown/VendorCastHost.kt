package io.github.iamr0s.carvex.host.vendor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.baidu.carlife.mixing.CastConfig
import com.baidu.carlife.mixing.MusicInfo
import com.baidu.carlife.mixing.aidl.ICarLifeCastCallback
import com.baidu.carlife.mixing.aidl.ICarLifeCastManager
import com.baidu.carlife.mixing.aidl.IRemoteAudioListener
import com.baidu.carlife.mixing.aidl.IRemoteKeyListener
import com.baidu.carlife.mixing.aidl.IRemoteTouchListener
import com.baidu.carlife.mixing.aidl.ISystemAudioCallback
import io.github.iamr0s.carvex.host.MixingMicrophonePcm
import io.github.iamr0s.carvex.pcm.PcmFormat
import io.github.iamr0s.carvex.sessioncore.CastHost
import io.github.iamr0s.carvex.sessioncore.HostCallbacks
import io.github.iamr0s.carvex.sessioncore.HostCapabilities
import io.github.iamr0s.carvex.sessioncore.HostMusic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * One implementation for every verified CarLife mixing endpoint.
 *
 * The APKs differ in the package/component used to reach this manager, while the
 * callback contract and the AIDL descriptor are the same. All transport behaviour
 * therefore lives here; [VendorHostConfig] only describes the endpoint.
 */
class VendorCastHost(
    private val context: Context,
    private val events: HostCallbacks,
    private val config: VendorHostConfig,
) : CastHost {
    companion object {
        const val MANAGER_DESCRIPTOR = "com.baidu.carlife.mixing.aidl.ICarLifeCastManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ready = CompletableDeferred<ICarLifeCastManager>()
    @Volatile private var remote: ICarLifeCastManager? = null
    @Volatile private var bound = false
    @Volatile private var closed = false
    @Volatile private var touch: IRemoteTouchListener? = null
    @Volatile private var key: IRemoteKeyListener? = null
    private class MicrophoneBinding(val listener: IRemoteAudioListener)
    @Volatile private var microphone: MicrophoneBinding? = null
    private val microphonePcm = MixingMicrophonePcm()

    private fun allowed(): Boolean {
        val packages = context.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if (config.hostPackage !in packages) {
            throw SecurityException("Unexpected ${config.id} host callback UID")
        }
        return !closed
    }

    private val systemAudio = object : ISystemAudioCallback.Stub() {
        override fun onAudioStreamReady(pcm: ByteArray?, category: Int, sampleRate: Int, channels: Int, encoding: Int) {
            if (!allowed()) return
            if (pcm == null || category !in 1..3 || sampleRate !in 8000..192000 ||
                channels !in 1..2 || encoding != 2 || pcm.size !in 1..32768 ||
                pcm.size % (channels * 2) != 0) {
                events.event("invalid-pcm")
                return
            }
            events.pcm(pcm, category, sampleRate, channels, encoding)
        }

        override fun onAudioStateChange(category: Int, state: Int) {
            if (!allowed()) return
            if (category !in 1..3 || state !in 0..1) {
                events.event("unknown-audio-state", state)
                return
            }
            events.audioState(category, state)
        }
    }

    private val callback = object : ICarLifeCastCallback.Stub() {
        override fun onServiceReady() { if (allowed()) events.event("host-ready") }
        override fun onCastPrepared(displayId: Int) { if (allowed()) events.event("host-display", displayId) }
        override fun addTouchListener(listener: IRemoteTouchListener?) {
            if (allowed()) {
                touch = listener
                events.event("touch-listener", if (listener != null) 1 else 0)
            }
        }
        override fun removeTouchListener(listener: IRemoteTouchListener?) {
            if (allowed() && touch?.asBinder() == listener?.asBinder()) {
                touch = null
                events.event("touch-listener", 0)
            }
        }
        override fun addKeyListener(listener: IRemoteKeyListener?) {
            if (allowed()) {
                key = listener
                events.event("key-listener", if (listener != null) 1 else 0)
            }
        }
        override fun removeKeyListener(listener: IRemoteKeyListener?) {
            if (allowed() && key?.asBinder() == listener?.asBinder()) {
                key = null
                events.event("key-listener", 0)
            }
        }
        override fun addAudioListener(listener: IRemoteAudioListener?) {
            if (allowed()) {
                microphone = listener?.let { MicrophoneBinding(it) }
                events.event("microphone-listener", if (listener != null) 1 else 0)
            }
        }
        override fun removeAudioListener(listener: IRemoteAudioListener?) {
            if (allowed() && microphone?.listener?.asBinder() == listener?.asBinder()) {
                microphone = null
                events.event("microphone-listener", 0)
            }
        }
        override fun sendMusicInfo(info: MusicInfo?) {
            if (allowed()) events.music(info?.let {
                HostMusic(it.source, it.song, it.artist, it.duration, it.playlistSize, it.index)
            })
        }
        override fun notifyMusicProgress(progress: Long) { if (allowed()) events.musicProgress(progress) }

        override fun invokeMethod(intent: Intent?): Bundle? {
            if (!allowed()) return null
            return when (intent?.action) {
                "carlife_dynamic_params" -> {
                    events.fps(intent.getIntExtra("carlife_frame", 0)); null
                }
                "carlife_vr_hu_wake_up_enable" -> Bundle().apply {
                    putBoolean("hu_wake_up_enable", false)
                }
                "carlife_vr_use_phone_mic" -> Bundle().apply {
                    putBoolean("is_use_phone_mic", !(microphone != null && events.vehicleMicrophoneReady()))
                }
                "carlife_music_sample_rate" -> events.musicSampleRate()
                    ?.takeIf { it in 8000..192000 }
                    ?.let { Bundle().apply { putInt("music_sample_rate", it) } }
                else -> {
                    intent?.let {
                        events.event("host-action:${it.action ?: "null"}")
                        events.optionalAction(it)
                    }
                    null
                }
            }
        }

        override fun notifyAudioState(state: Int) { if (allowed()) events.event("host-wakeup-audio-state", state) }
        override fun onServiceDestroy() { if (allowed()) events.event("host-destroy") }
    }

    private val death = IBinder.DeathRecipient { events.event("host-binder-died") }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed) return
            scope.launch {
                try {
                    check(binder.interfaceDescriptor == MANAGER_DESCRIPTOR) {
                        "${config.id} binder descriptor mismatch: ${binder.interfaceDescriptor}"
                    }
                    binder.linkToDeath(death, 0)
                    val manager = ICarLifeCastManager.Stub.asInterface(binder)
                    if (closed) {
                        binder.unlinkToDeath(death, 0)
                        return@launch
                    }
                    remote = manager
                    manager.setCastCallback(callback)
                    manager.setSystemAudioCallback(systemAudio)
                    if (!closed) {
                        ready.complete(manager)
                        events.event("host-bound")
                    }
                } catch (t: Throwable) {
                    ready.completeExceptionally(t)
                    events.event("host-bind-error")
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) { events.event("host-disconnected") }
        override fun onBindingDied(name: ComponentName) { events.event("host-binding-died") }
        override fun onNullBinding(name: ComponentName) {
            ready.completeExceptionally(IllegalStateException("Null host binding"))
            events.event("host-null-binding")
        }
    }

    override suspend fun bind() = withContext(Dispatchers.Main.immediate) {
        check(!closed && !bound)
        val classes = listOf(config.serviceClass) + config.fallbackServiceClasses
        var last: Throwable? = null
        for (serviceClass in classes) {
            val intent = Intent().setClassName(config.hostPackage, serviceClass)
            config.serviceAction?.let(intent::setAction)
            config.protocolType?.let { intent.putExtra("protocolType", it) }
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) continue
            bound = true
            try {
                withTimeout(10000) { ready.await() }
                return@withContext
            } catch (t: Throwable) {
                last = t
                break
            }
        }
        throw IllegalStateException("${config.displayName} rejected binding", last)
    }

    private suspend fun manager(): ICarLifeCastManager? =
        if (!bound || closed) null else ready.await()

    override suspend fun format(width: Int, height: Int): Bundle? = withContext(Dispatchers.IO) {
        manager()?.invokeMethod(Intent("carlife_encode_format").putExtra(
            "params", JSONObject().put("width", width).put("height", height)
                .put("real_width", width).put("real_height", height).toString()))
    }

    override suspend fun surface(surface: Surface, width: Int, height: Int, dpi: Int) = withContext(Dispatchers.IO) {
        check(!closed && surface.isValid)
        check(bound) { "${config.displayName} host is not bound" }
        ready.await().prepareCast(CastConfig(width, height, dpi, surface))
    }

    /**
     * 绑定成功不等于接管画面：实测小米 CarWith 只在自己界面处于投屏状态时才回
     * `onCastPrepared`，所以没收到回声时把它的入口带到前台，再让调用方重试一次。
     */
    override suspend fun requestForeground(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (closed) return@withContext false
        val launch = context.packageManager.getLaunchIntentForPackage(config.hostPackage)
            ?: return@withContext false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val ok = runCatching { context.startActivity(launch) }.isSuccess
        events.event(if (ok) "foreground-requested" else "foreground-request-failed")
        ok
    }

    /** Preserve the existing PCM route. This bridge does not negotiate Bluetooth routing. */
    override suspend fun selectCarLifePlusAudio() {
        check(!closed)
        events.event(if (bound) "audio-route-default-pcm" else "audio-route-skipped-unbound")
    }

    override suspend fun invoke(action: String, configure: (Intent) -> Intent): Bundle? = withContext(Dispatchers.IO) {
        val manager = manager() ?: return@withContext null
        try {
            manager.invokeMethod(configure(Intent(action)))
        } catch (_: RemoteException) {
            events.event("host-invoke-error:$action")
            null
        }
    }

    override suspend fun microphone(pcm: ByteArray, rate: Int, channels: Int, encoding: Int): Boolean =
        withContext(Dispatchers.IO) {
            if (closed) return@withContext false
            require(rate in 8000..192000 && channels in 1..2 && encoding == 2 &&
                pcm.size in 1..32768 && pcm.size % (channels * 2) == 0)
            val binding = microphone ?: return@withContext false
            val converted = microphonePcm.convert(pcm, PcmFormat(rate, channels, encoding), binding)
            if (closed || microphone !== binding) return@withContext false
            if (converted.isEmpty()) return@withContext true
            try {
                binding.listener.onReceiveAudioStream(converted, MixingMicrophonePcm.OUTPUT.rate,
                    MixingMicrophonePcm.CHANNEL_CONFIG, MixingMicrophonePcm.OUTPUT.encoding)
                true
            } catch (_: RemoteException) {
                if (microphone === binding) {
                    microphone = null
                    events.event("microphone-listener", 0)
                }
                events.event("microphone-send-failed")
                false
            }
        }

    override fun keyMapper() = config.keyMapper
    override fun capabilities() = HostCapabilities(
        touch = true,
        keys = true,
        microphone = true,
        displayCount = 1,
        maxFps = null,
    )

    override fun touch(event: MotionEvent): Boolean = if (closed) false else touch?.onTouchEvent(event) ?: false
    override fun key(event: KeyEvent): Boolean = if (closed) false else key?.onKeyEvent(event) ?: false

    override fun close() {
        if (closed) return
        closed = true
        runCatching { remote?.stopCast() }
        runCatching { remote?.asBinder()?.unlinkToDeath(death, 0) }
        remote = null
        touch = null
        key = null
        microphone = null
        microphonePcm.reset()
        ready.cancel()
        scope.cancel()
        Handler(Looper.getMainLooper()).post {
            if (bound) runCatching { context.unbindService(connection) }
            bound = false
        }
    }
}
