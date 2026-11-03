package io.github.iamr0s.carvex

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.iamr0s.carvex.sessioncore.BridgeStore
import io.github.iamr0s.carvex.carplay.core.AudioDebugConfig

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
        if (changed) editor.apply()

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
