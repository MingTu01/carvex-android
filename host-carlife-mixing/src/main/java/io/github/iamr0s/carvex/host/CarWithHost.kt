package io.github.iamr0s.carvex.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import io.github.iamr0s.carvex.host.vendor.VendorCastHost
import io.github.iamr0s.carvex.host.vendor.XiaomiHostPlugin
import io.github.iamr0s.carvex.sessioncore.CastHost
import io.github.iamr0s.carvex.sessioncore.HostCallbacks
import io.github.iamr0s.carvex.sessioncore.HostCapabilities
import io.github.iamr0s.carvex.sessioncore.HostKeyMapper

/**
 * Source compatibility facade for old callers.
 *
 * Xiaomi is no longer a second implementation: this class only supplies the Xiaomi
 * endpoint config to the shared [VendorCastHost].
 */
@Deprecated("Use VendorCastHost with XiaomiHostPlugin")
class CarWithHost(context: Context, events: HostCallbacks) : CastHost {
    private val delegate = XiaomiHostPlugin.create(context, events)

    override suspend fun bind() = delegate.bind()
    override suspend fun selectCarLifePlusAudio() = delegate.selectCarLifePlusAudio()
    override suspend fun microphone(pcm: ByteArray, rate: Int, channels: Int, encoding: Int) =
        delegate.microphone(pcm, rate, channels, encoding)
    override suspend fun surface(surface: Surface, width: Int, height: Int, dpi: Int) =
        delegate.surface(surface, width, height, dpi)
    override suspend fun format(width: Int, height: Int): Bundle? = delegate.format(width, height)
    override suspend fun invoke(action: String, configure: (Intent) -> Intent): Bundle? =
        delegate.invoke(action, configure)
    override fun keyMapper(): HostKeyMapper? = delegate.keyMapper()
    override fun capabilities(): HostCapabilities? = delegate.capabilities()
    override fun key(event: KeyEvent) = delegate.key(event)
    override fun touch(event: MotionEvent) = delegate.touch(event)
    override suspend fun requestForeground() = delegate.requestForeground()
    override fun close() = delegate.close()
}
