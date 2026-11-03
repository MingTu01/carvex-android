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

    /** 语音（category 3）改走 `101/default` 的裸 PCM16 48000/2，而不是 `100/speechRecognition`。
     *
     *  为什么（2026-11-03 真车 session `9829c6ba` / `76cfb21d`）：车机在 `type 100` 上只容忍
     *  **一条**音频流，而主音频 PCM 48k/2 常年占着它。于是：
     *
     *  ```
     *  06:14:01  cat1 100/media PCM 48k/2        → accepted（主音频，出声）
     *  06:14:57  cat3 100/speechRecognition ELD  → status 400（type 100 已被占）
     *  06:15:01  cat2 100/alert                  → 400 → 改走 101/default → accepted
     *  ```
     *
     *  语音因此永远起不来；而车机一旦为语音让出输入焦点就再也不还回来 —— 现象就是
     *  「用完小爱之后触屏失效，只有重启车机能恢复」。`101/default` 是车机自己在
     *  `/info` 里广告的（`outputMask=2008000` → lpcm 48000/2 | ELD 48000/2），也是独立上下文，
     *  不会顶掉主音频。
     *
     *  关掉它就回到"语音只认 `100/speechRecognition`"的旧行为，用于真车 A/B。 */
    @Volatile var speechAltPcm: Boolean = true

    /** 允许导航语音（category 2）在**会话中途**做 SETUP。默认 **false**，不要随便打开。
     *
     *  【真车结论，2026-11-03 会话 `2f8114ca`】这一条是触屏失效的根因。
     *
     *  每次点"开始导航"，最先发生的不是导航通知，而是 category 2 要流：
     *  ```
     *  14:49:36.53  category=2 selected 100/alert → SETUP 400 → 回退 101/default → 激活失败
     *  14:49:36.62  mix_notify_start_navi（比音频晚 0.1 秒）
     *  ```
     *  而车机一旦为语音让出输入焦点就**再也不还回来**，现象就是"一开导航触屏就死"。
     *  这和历史记录里小爱（cat3）那个坑是同一个机制，当初的修法就是不让它去抢。
     *
     *  单变量验证（同一次装包、同一台车、基线核实过）：
     *  - 拦掉这次 SETUP（`navAudio=false`）：导航开始后触摸照常，97 条报告横跨 15:15:31→15:17:12
     *  - 放开：导航一开始触摸就归零，退出导航也不恢复
     *
     *  所以这个开关只在**复现问题**时打开。正常运行路径上，category 2 只允许会话建立阶段的
     *  预热（`prime=true`）—— 那次 SETUP 是安全的，日志里 15:15:23 被接受后触摸照常。 */
    @Volatile var navAudio: Boolean = false
}
