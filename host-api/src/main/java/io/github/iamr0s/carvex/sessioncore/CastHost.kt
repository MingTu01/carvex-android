package io.github.iamr0s.carvex.sessioncore

import android.view.Surface

/**
 * 「内容宿主」抽象：本端把车机的画面/音频**来源**（CarLife mixing 宿主）
 * 与投屏协议解耦的唯一接缝。
 *
 * 为什么需要它（而不是直接依赖 `CarWithHost`）：
 *  1. **分层**：`:host-api` 与具体的 mixing 实现分离，所有品牌共用同一实现；
 *  2. **多宿主**：新增品牌只需新增 endpoint 配置，编排层与协议层都不用改；
 *  3. **可测试**：`HostAudioBridge` 等类可以注入假宿主，不必拉起 AIDL 与 `Context`。
 *
 * 这里的成员是**当前实际被用到的最小面**，不是某个 CarLife APK 的完整镜像 ——
 * 有意保持窄，避免把宿主特有的能力（AIDL 细节、Intent 动作表）泄漏进编排层。
 * 需要更宽能力的调用方应当显式扩展本接口，而不是绕过它去引用具体类。
 */
interface CastHost : AutoCloseable {
    /** 绑定到宿主服务。失败应抛出，由编排层决定是否降级。 */
    suspend fun bind()

    /** 请求宿主把系统音频路由到本端（CarLife mixing 音频通道）。 */
    suspend fun selectCarLifePlusAudio()

    /** 把本端采集/转发的 PCM 交回宿主（车机侧作为麦克风输入）。返回是否被接受。 */
    suspend fun microphone(pcm: ByteArray, rate: Int, channels: Int, encoding: Int): Boolean

    /** 把一块 Surface 交给宿主作为投屏目标（宿主侧创建编码/显示链）。 */
    suspend fun surface(surface: Surface, width: Int, height: Int, dpi: Int)

    /** 询问宿主建议的编码参数；未绑定时返回 null（不阻塞）。 */
    suspend fun format(width: Int, height: Int): android.os.Bundle?

    /** 宿主扩展动作（CarLife mixing 的 Intent 动作表）。 */
    suspend fun invoke(action: String, configure: (android.content.Intent) -> android.content.Intent = { it }): android.os.Bundle?

    /** 宿主的键码映射；不提供时协议侧按键事件按"不支持"处理。 */
    fun keyMapper(): HostKeyMapper? = null

    /** 宿主能力声明；返回 null 表示未声明，编排层维持既有行为。 */
    fun capabilities(): HostCapabilities? = null

    /** 把车机按键交给宿主；返回宿主是否接受。 */
    fun key(event: android.view.KeyEvent): Boolean

    /** 把车机触摸事件交给宿主；返回宿主是否接受。 */
    fun touch(event: android.view.MotionEvent): Boolean

    /**
     * 宿主没有主动接管画面时的兜底：把它自己的界面带到前台，再请它准备投屏。
     *
     * 实测（小米 CarWith）绑定服务本身不会让 `onCastPrepared` 发生，只有它自己的界面
     * 在前台进入投屏状态时才会。编排层只在"已经绑定成功但没收到 onCastPrepared"时调用，
     * 返回 false 表示宿主没有可启动的入口，调用方应维持现状。
     */
    suspend fun requestForeground(): Boolean = false
}
