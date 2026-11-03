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

    /** PCM 优先（**只对 category 1 / 主音频**）：走车机广告的 `100/media` 裸 PCM16。
     *
     *  **已在实车证实**（build 20030）：`100/media` + `audioFormat=0x8000` + 无帧密码，
     *  车机出声。此前 `type=102/media` + AAC-LC + CBCFrame 一直是"包在发、日志全绿、全程静音"。
     *
     *  **不要再往 category 2/3 扩**。20032 试过，结果是主音频被顶掉：
     *  车机在 `type 100` 上只有一个 PCM 上下文，category 3 先用掉 24k/1 之后，
     *  `100/alert` 与 `100/media` 的 48k/2 SETUP 全部 400。
     *
     *  代价是带宽（48k/2 → 192 kB/s，AAC 的 8 倍）。等压缩编码修好之后应重新评估，
     *  不要把 PCM 当成最终形态。
     *
     *  ADB 开关名沿用 `pcmMedia`（已在真机用过，不改以免破坏记录里的命令）。 */
    @Volatile var pcmMedia: Boolean = true

    /** 告警音（category 2）改用 `101/default` 的裸 PCM16。
     *
     *  为什么是 101 而不是它自己的 `100/alert`：真机 20032 上 `100/alert` + PCM 48k/2 被
     *  车机以 **400 拒绝**，而同一次尝试里 `101/default` + 同样的 PCM 48k/2 被接受。
     *  101 是独立的 type，不抢 `type 100` 那个唯一的 PCM 上下文，所以不会影响主音频。
     *
     *  默认关：合成器行为没有实车听感证据，只在明确要试提示音时打开。 */
    @Volatile var pcmAlert: Boolean = false
}
