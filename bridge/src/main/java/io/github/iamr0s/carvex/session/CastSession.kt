package io.github.iamr0s.carvex.session

/**
 * 编排层对 `:app` 的唯一接口。
 *
 * `:app` 在组装适配器中创建 `SessionController`，并通过本接口连接应用状态与 Service 契约；
 * CarLife Service 位于独立宿主模块，通过应用提供的接口运行，不依赖本模块。将来出现第二个消费者时，
 * 再把本接口挪进独立的 `:bridge-api` 模块。
 */
interface CastSession {
    /** 是否处于连接/断开过程中。 */
    val active: Boolean

    /** 请求建立会话；`fromHost` 表示由宿主界面发起（影响提示文案，不影响协议）。 */
    fun start(fromHost: Boolean = false)

    /** 清理旧会话（TEARDOWN + 资源释放），使后续 start() 能干净建立新连接。 */
    suspend fun recycle()

    /** 主动断开；失败不抛异常，状态通过 `BridgeStore` 广播。 */
    suspend fun disconnect()

    /** 释放所有资源（Service 销毁时调用）。 */
    fun close()

    /** 设置/调整视频码率（界面设置项）。 */
    fun setVideoBitrate(value: Int)

    /** 手动模拟一个宿主键码（按键调节区的“模拟”按钮）。 */
    fun triggerCarWithKey(code: Int)

    /** 开始宿主音频探测（界面诊断用）。 */
    fun startCarWithAudioProbe()

    /** 切换 CarPlay 音频测试（界面诊断用）。 */
    fun toggleCarPlayAudioTest()
}
