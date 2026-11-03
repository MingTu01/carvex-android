package io.github.iamr0s.carvex

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.iamr0s.carvex.sessioncore.BridgeStore
import io.github.iamr0s.carvex.carplay.core.AudioDebugConfig
import io.github.iamr0s.carvex.session.ScreenRestartHook

/** ADB 运行时音频调试广播：免重编/重装切音频参数。
 *
 *  用法（adb shell）：
 *  ```
 *  # 切明文模式
 *  am broadcast -a io.github.iamr0s.carvex.AUDIO_DEBUG --ez audioPlaintext true
 *  # 开关 ADTS（true=ADTS 封装, false=raw AU）
 *  am broadcast -a io.github.iamr0s.carvex.AUDIO_DEBUG --ez adts true
 *  # 开关 RTCP SR 到 controlPort
 *  am broadcast -a io.github.iamr0s.carvex.AUDIO_DEBUG --ez rtcpSr true
 *  # T1：音频 SETUP 后补发 RECORD（默认 true）
 *  am broadcast -a io.github.iamr0s.carvex.AUDIO_DEBUG --ez recordAfterAudio false
 *  # T3：补发 SET_PARAMETER volume（默认 true）
 *  am broadcast -a io.github.iamr0s.carvex.AUDIO_DEBUG --ez volume false
 *  # 组合：改完自动重连
 *  am broadcast -a io.github.iamr0s.carvex.AUDIO_DEBUG --ez audioPlaintext true --ez adts false --ez reconnect true
 *  # 立刻重建一次屏幕流（不动配置、不重连；导航态触屏失效时用来连续试探）
 *  am broadcast -a io.github.iamr0s.carvex.AUDIO_DEBUG --es restartScreen adb
 *  ```
 *
 *  所有参数都是可选的，只改传了的那些。`reconnect=true` 改完自动断开重连。
 */
class AudioDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val prefs = context.getSharedPreferences("options", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var changed = false
        val sb = StringBuilder("AudioDebug:")
        val o = BridgeStore.options.value

        // audioPlaintext: 明文模式
        if (intent.hasExtra("audioPlaintext")) {
            val v = intent.getBooleanExtra("audioPlaintext", false)
            editor.putBoolean("audioPlaintext", v)
            BridgeStore.configure(o.copy(audioPlaintext=v))
            sb.append(" audioPlaintext=$v")
            changed = true
        }
        // adts: ADTS 帧封装开关
        if (intent.hasExtra("adts")) {
            val v = intent.getBooleanExtra("adts", true)
            AudioDebugConfig.adts = v
            editor.putBoolean("audioAdts", v)
            sb.append(" adts=$v")
            changed = true
        }
        // rtcpSr: RTCP Sender Report 开关
        if (intent.hasExtra("rtcpSr")) {
            val v = intent.getBooleanExtra("rtcpSr", true)
            AudioDebugConfig.rtcpSr = v
            editor.putBoolean("audioRtcpSr", v)
            sb.append(" rtcpSr=$v")
            changed = true
        }
        // recordAfterAudio: 音频 SETUP 后补发 RECORD（T1 开闸实验）
        if (intent.hasExtra("recordAfterAudio")) {
            val v = intent.getBooleanExtra("recordAfterAudio", true)
            AudioDebugConfig.recordAfterAudio = v
            editor.putBoolean("audioRecordAfterAudio", v)
            sb.append(" recordAfterAudio=$v")
            changed = true
        }
        // volume: 音频 SETUP 后补发 SET_PARAMETER volume（T3 音量闸实验）
        if (intent.hasExtra("volume")) {
            val v = intent.getBooleanExtra("volume", true)
            AudioDebugConfig.volume = v
            editor.putBoolean("audioVolumeAnnounce", v)
            sb.append(" volume=$v")
            changed = true
        }
        // pcmMedia: 主音频走 100/media 裸 PCM16（默认开，实车已验证）
        if (intent.hasExtra("pcmMedia")) {
            val v = intent.getBooleanExtra("pcmMedia", true)
            AudioDebugConfig.pcmMedia = v
            editor.putBoolean("audioPcmMedia", v)
            sb.append(" pcmMedia=$v")
            changed = true
        }
        // pcmAlert: 告警音走 101/default 裸 PCM16（默认关；100/alert 的 PCM 会被车机 400 拒绝）
        if (intent.hasExtra("pcmAlert")) {
            val v = intent.getBooleanExtra("pcmAlert", false)
            AudioDebugConfig.pcmAlert = v
            editor.putBoolean("audioPcmAlert", v)
            sb.append(" pcmAlert=$v")
            changed = true
        }
        // speechAltPcm: 语音（category 3）走 101/default 裸 PCM16（默认开）。
        // 关掉它 = 语音只认 100/speechRecognition，用于真车 A/B 那条"语音把 type 100 顶掉"的结论。
        if (intent.hasExtra("speechAltPcm")) {
            val v = intent.getBooleanExtra("speechAltPcm", true)
            AudioDebugConfig.speechAltPcm = v
            editor.putBoolean("audioSpeechAltPcm", v)
            sb.append(" speechAltPcm=$v")
            changed = true
        }
        // audioVolumeDb: 主音频增益（dB）。NaN 表示不宣告，此时走 AudioDebugConfig 的 0 dB 默认值。
        if (intent.hasExtra("volumeDb")) {
            val v = intent.getDoubleExtra("volumeDb", 0.0)
            editor.putFloat("audioVolumeDb", v.toFloat())
            BridgeStore.configure(o.copy(audioVolumeDb = v))
            sb.append(" volumeDb=$v")
            changed = true
        }
        if (changed) editor.apply()

        // restartScreen: 立刻重建一次屏幕流（TEARDOWN → SETUP → 重连数据口 → RECORD）。
        //
        // 不放进上面的 `changed` 分支：这一项不改任何持久配置，是一次性动作，也就不能跟
        // `reconnect` 联动（重连会把会话换掉，重建就白做了）。
        //
        // 必须离开主线程：onReceive 在主线程，而重建里有两条阻塞 RTSP 往返，
        // 真机若遇到对端不响应会直接把广播接收器拖到 ANR。
        if (intent.hasExtra("restartScreen")) {
            val reason = intent.getStringExtra("restartScreen")?.takeIf { it.isNotBlank() } ?: "adb"
            val hook = ScreenRestartHook.trigger
            if (hook == null) {
                sb.append(" restartScreen=no-session")
                Log.i(TAG, "restartScreen ignored: no live AirPlay session")
            } else {
                sb.append(" restartScreen=queued")
                Thread({
                    val ok = runCatching { hook(reason) }.getOrElse { t ->
                        Log.i(TAG, "restartScreen threw: ${t.javaClass.simpleName} ${t.message ?: ""}")
                        false
                    }
                    Log.i(TAG, "restartScreen reason=$reason -> $ok")
                }, "adb-screen-restart").start()
            }
        }

        Log.i(TAG, sb.toString())

        // reconnect=true 时自动断开重连让新参数生效
        if (intent.getBooleanExtra("reconnect", false) && changed) {
            Log.i(TAG, "AudioDebug: reconnecting to apply changes")
            com.baidu.carlife.service.CarlifeConnectService.disconnect(context)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                com.baidu.carlife.service.CarlifeConnectService.connect(context)
            }, 2000)
        }

        // 返回结果给 adb
        if (isOrderedBroadcast) {
            setResultCode(if (changed) android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED)
            setResultData(sb.toString())
        }
    }

    companion object {
        const val ACTION = "io.github.iamr0s.carvex.AUDIO_DEBUG"
        private const val TAG = "AudioDebug"
    }
}
