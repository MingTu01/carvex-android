package io.github.iamr0s.carvex.host.mixing.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import kotlinx.coroutines.flow.Flow

/** Implemented by the consuming Application. Library code does not initialize a DI container. */
interface MixingServiceOwner {
    fun createMixingServiceDelegate(service: Service): MixingServiceDelegate
}

/** Application facilities needed by the CarLife service; no concrete UI, store or protocol types. */
interface MixingServiceDelegate {
    val brand: MixingBrand
    val session: MixingServiceSession
    fun missingPermissions(): List<String>
    fun notification(text: String, disconnect: PendingIntent): Notification
    fun log(tag: String, message: String)
}

data class MixingSessionStatus(val text: String, val failed: Boolean)

/** One service-owned session. The application adapts its orchestration layer to this contract. */
interface MixingServiceSession : AutoCloseable {
    val active: Boolean
    val status: Flow<MixingSessionStatus>
    /** Includes application-specific connection preparation before starting the session. */
    suspend fun start(fromHost: Boolean)
    /** 清理旧会话（发 TEARDOWN + 释放资源），使后续 start() 能干净地建立新连接。 */
    suspend fun recycle()
    suspend fun disconnect()
    fun setVideoBitrate(value: Int)
    fun sendHostKey(code: Int)
    fun startHostAudioProbe()
    fun toggleProtocolAudioTest()
}
