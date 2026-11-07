package io.github.iamr0s.carvex.carplay.core
/** Full-display scaling with per-axis normalization: no guessed rotation, crop or view-area.
 *
 *  `receiver*` 是接收端自报的显示尺寸，`width/height` 是我们实际编码的画布。触摸从接收端来，
 *  必须落回画布坐标，所以两个空间的宽高比**必须基本一致** —— 不一致就说明这中间隔着旋转或裁剪，
 *  那不是能靠缩放猜出来的。
 *
 *  但"基本一致"不能要求整数完全相等。实车（session a77d8359）接收端报 `1024x600`，车机面板是
 *  `1280x720`，宽高比差 4.2% —— 这是同一个 16:9 面板的两种标称值，接收端会把我们的画布按整屏
 *  拉伸显示，触摸自然也按同一个拉伸反算回来。要求整数相等会把这种正常的标称差异判成"需要显式
 *  映射"，然后整个更大的画布都用不了，画面只能停在 1024x600 被放大到 720p。
 *
 *  所以按相对误差给一个上限：超过它才算真的布局不同（横竖屏互换、裁剪），照旧拒掉。 */
data class ProjectionGeometry(val receiverWidth:Int,val receiverHeight:Int,val width:Int,val height:Int) {
    init {
        require(receiverWidth>1&&receiverHeight>1&&width>1&&height>1){"Invalid projection dimensions"}
        require(aspectErrorPercent(receiverWidth,receiverHeight,width,height)<=ASPECT_TOLERANCE_PERCENT){
            "Host ${width}x$height and receiver ${receiverWidth}x$receiverHeight differ by "+
                aspectErrorPercent(receiverWidth,receiverHeight,width,height)+
                "% in aspect; explicit rotation/view-area mapping needed"
        }
    }
    /** 接收端坐标按各自的轴线性归一化到画布坐标 —— 和接收端把画布铺满自己的屏是同一个映射。 */
    fun map(sample:TouchSample):TouchSample=TouchSample(sample.pressed,
        sample.x.coerceIn(0f,(receiverWidth-1).toFloat())/(receiverWidth-1)*(width-1),
        sample.y.coerceIn(0f,(receiverHeight-1).toFloat())/(receiverHeight-1)*(height-1))
    companion object {
        /** 允许的宽高比相对误差（百分比）。6% 覆盖 1024x600↔1280x720（4.2%）、
         *  1024x600↔1920x1080（4.2%）这类同一块屏的标称差异，又容不下横竖屏或 4:3↔16:9。 */
        const val ASPECT_TOLERANCE_PERCENT=6L
        /** 两个尺寸的宽高比相差多少百分比。任何一个边长非法时返回 [Long.MAX_VALUE]。 */
        fun aspectErrorPercent(receiverWidth:Int,receiverHeight:Int,width:Int,height:Int):Long =
            if(receiverWidth>0&&receiverHeight>0&&width>0&&height>0)
                kotlin.math.abs(width.toLong()*receiverHeight-receiverWidth.toLong()*height)*100L/
                    (receiverWidth.toLong()*height).coerceAtLeast(1L)
            else Long.MAX_VALUE
    }
}
