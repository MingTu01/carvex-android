package io.github.iamr0s.carvex.session

/** 让 ADB 调试广播能在导航态下探测 `hidInputMode` 的合法取值。
 *
 *  背景：我们一直给 `hidSetInputMode` 硬编码 `hidInputMode=0`。健康会话里它可用，
 *  但这个枚举的真正语义从未验证过。导航一开始车机就停发 `hidSendReport`，而重发 0
 *  已经被 20037 的真车实验证伪（4 台全 200 接受，之后仍 0 条触摸）。
 *
 *  所以这一步不是"再试一次同样的动作"，而是**枚举探测**：把 1、2、3 逐个发下去，
 *  每次只看两个可观测事实 —— 车机回 200 还是 4xx、之后有没有触摸报告回来。
 *  这比继续猜"关掉再打开"有依据：0 之外的取值我们一个都没试过。
 *
 *  生命周期与 [ScreenRestartHook] 相同：会话建立后挂上，会话释放时置空。
 */
object HidProbeHook {
    /** 参数是要探测的 mode 值；返回被接受（2xx）的设备数，-1 表示当前没有可用会话。 */
    @Volatile var probe:((Int)->Int)?=null
}
