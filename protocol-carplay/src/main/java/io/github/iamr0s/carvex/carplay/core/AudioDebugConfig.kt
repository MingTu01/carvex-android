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

    /** T1：音频 SETUP 成功后补发一次 RECORD。
     *
     *  为什么：日志里 RECORD（cseq=10）比音频 SETUP（cseq=36/39/42）早 6.4 秒，会话内再没有
     *  第二次 RECORD。AirPlay/RAOP 的 RECORD 是"开闸"，建流晚于闸门时接收端可能只登记不消费
     *  —— 表现就是 SETUP 全 200、端口有效、包在发、车机零 RTCP 回包、全程静音。
     *  真相未证实（research `OPEN-AP-30`），所以做成开关，方便真车 A/B。 */
    @Volatile var recordAfterAudio: Boolean = true

    /** T3：音频 SETUP 后补发 SET_PARAMETER volume。
     *
     *  为什么：iPhone 建流后一定会送一条音量；本实现因为 `options.audioVolumeDb` 默认 NaN
     *  而从未发送（历史日志 `Audio volume announced` 零次）。接收端若把未宣告的流当 0 增益，
     *  载荷再对也是静音。 */
    @Volatile var volume: Boolean = true

    /** 未显式配置音量时使用的默认值（dB）。iPhone 常用 0 dB 表示原始增益。 */
    const val DEFAULT_VOLUME_DB: Double = 0.0

    /** PCM 优先：三条音频流（media/alert/speechRecognition）都走车机广告的裸 PCM16。
     *
     *  **已在实车证实**（build 20030）：`100/media` + `audioFormat=0x8000` + 无帧密码，
     *  车机出声。此前 `type=102/media` + AAC-LC + CBCFrame 一直是"包在发、日志全绿、全程静音"。
     *
     *  为什么 category 2/3 也一起走：车机 `/info` 对三条流都广告了 PCM，
     *  而两套压缩编码在本端建不起来（20030 真机：category 3 报
     *  `AAC configuration differs from announced audioFormat: objectType=39 rate=24000 ch=1
     *  frames=512`，category 2 报 `ELD resilience/SBR configuration unsupported`），
     *  于是提示音与语音输出完全不工作。PCM 是唯一有实证的选择。
     *
     *  代价是带宽（48k/2 → 192 kB/s，AAC 的 8 倍），且放弃压缩编码的容错。
     *  这两点应该在压缩编码修好之后重新评估，而不是把 PCM 当成最终形态。
     *
     *  ADB 开关名沿用 `pcmMedia`（已在真机用过，不改以免破坏记录里的命令）。 */
    @Volatile var pcmMedia: Boolean = true
}
