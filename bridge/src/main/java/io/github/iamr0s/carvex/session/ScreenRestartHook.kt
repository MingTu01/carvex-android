package io.github.iamr0s.carvex.session

/** 让 ADB 调试广播能在**会话不重建**的前提下触发一次屏幕流重建。
 *
 *  为什么需要它：真车验证「导航态车机把显示与输入一起关掉」这个假设时，正常路径只能靠
 *  CarWith 报一次 `mix_notify_start_navi` 来触发，而进入该状态一次就要：装导航、点开始导航、
 *  等失效。失效之后车机状态是粘性的（跨会话保持，只有重启车机能清），所以一轮只能试一次。
 *  有了这个钩子，可以在同一个已失效的会话里连续触发多次重建，把"重建能救/不能救"一次性问清。
 *
 *  生命周期：`SessionController` 在 AirSession 建立后挂上实现、在会话释放时置空。
 *  钩子为空只说明当前没有可用会话，不是错误。
 */
object ScreenRestartHook {
    /** 参数是给日志用的触发原因；返回是否成功重建。 */
    @Volatile var trigger:((String)->Boolean)?=null
}
