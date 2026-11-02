package io.github.iamr0s.carvex.carplay.core

/** ADB 运行时音频调试开关。
 *
 *  由 AudioDebugReceiver（app 模块）写入，CarPlayCompressedAudio（protocol 模块）读取。
 *  进程生命周期内有效，app 重启后恢复默认值。
 *  配合 `adb shell am broadcast -a io.github.iamr0s.carvex.AUDIO_DEBUG` 使用。 */
object AudioDebugConfig {
    /** ADTS 帧封装开关。true = AAC AU 包 7 字节 ADTS 头（0xFFF1），false = raw AU。 */
    @Volatile var adts: Boolean = true
    /** RTCP Sender Report 开关。true = 定期发 RTCP SR 到 controlPort。 */
    @Volatile var rtcpSr: Boolean = true
}
