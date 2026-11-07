package io.github.iamr0s.carvex.carplay.core
import org.junit.Assert.*
import org.junit.Test
class ProjectionGeometryTest {
    @Test fun proportionalPortraitScalingMapsCornersAndCenter(){
        val g=ProjectionGeometry(1600,2560,800,1280)
        val corner=g.map(TouchSample(true,1599f,2559f))
        assertEquals(799f,corner.x,0.01f);assertEquals(1279f,corner.y,0.01f)
        val center=g.map(TouchSample(true,799.5f,1279.5f))
        assertEquals(399.5f,center.x,0.01f);assertEquals(639.5f,center.y,0.01f)
        assertFalse(g.map(TouchSample(false,0f,0f)).pressed)
    }
    @Test fun originalSizeAndInvalidLayouts(){
        val g=ProjectionGeometry(1920,720,1920,720)
        assertEquals(TouchSample(true,100f,200f),g.map(TouchSample(true,100f,200f)))
        assertThrows(IllegalArgumentException::class.java){ProjectionGeometry(1600,2560,1280,720)}
        assertThrows(IllegalArgumentException::class.java){ProjectionGeometry(1600,2560,2560,1600)}
        assertThrows(IllegalArgumentException::class.java){ProjectionGeometry(1600,2560,0,0)}
        // 4:3 也不行：容差只吸收同一块屏的标称差异，不吸收布局差异。
        assertThrows(IllegalArgumentException::class.java){ProjectionGeometry(1920,1080,1024,768)}
    }
    /** 实车（session a77d8359）：接收端报 1024x600，面板其实是 1280x720，宽高比差 4.2%。
     *  这属于同一块 16:9 屏的两种标称值，必须能建出几何关系，否则 720p 画布永远用不上，
     *  画面只能停在 600p 被放大 —— 那就是用户说的"糊"。 */
    @Test fun aNominalPanelSizeDifferenceStillMapsPerAxis(){
        assertEquals(4L,ProjectionGeometry.aspectErrorPercent(1024,600,1280,720))
        assertEquals(4L,ProjectionGeometry.aspectErrorPercent(1024,600,1920,1080))
        val g=ProjectionGeometry(1024,600,1280,720)
        val corner=g.map(TouchSample(true,1023f,599f))
        assertEquals(1279f,corner.x,0.01f);assertEquals(719f,corner.y,0.01f)
        val center=g.map(TouchSample(true,511.5f,299.5f))
        assertEquals(639.5f,center.x,0.01f);assertEquals(359.5f,center.y,0.01f)
    }
}
