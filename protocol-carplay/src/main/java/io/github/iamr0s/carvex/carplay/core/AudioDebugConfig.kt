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

    /** T2：主音频改走 `type=100/media` + 裸 PCM16（`audioFormat=0x8000` = bit15 `lpcm` 48000/2）。
     *
     *  为什么值得单独试一次：车机 `/info` 的条目里 `#4 type=100/media outputMask=8000`，
     *  只广告**裸 PCM16 48k/2** 这一种格式。而当前走的是 `#8 type=102/media outputMask=800000`
     *  = AAC-LC 48k/2/1024 —— 那条路上同时叠了四层未验证的东西：AAC 编码器产出、
     *  ADTS 封装、A-44 的 CBCFrame 帧密码、`spf=1024` 分包。真实音频数据从 CarWith 到本端
     *  已经实测健康（7680B/40ms 实时），所以问题只可能在后面这四层里；PCM 路径把四层
     *  一次性去掉三（无编码、无 ADTS、A-44 里 100 在 G=0 下**不建密码器**）。
     *
     *  代价是带宽：48k/2/16bit = 192 kB/s，是 AAC 的 8 倍，但同一条 UDP 已经在跑，
     *  而且这是诊断而非产品形态。 */
    @Volatile var pcmMedia: Boolean = true
}
