package io.github.iamr0s.carvex

import android.graphics.PixelFormat
import android.media.ImageReader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 证明 `assets/splash.mp4` 真的被合成进了「喂给编码器的那个 surface」。
 *
 * 这块画面走 VirtualDisplay + Presentation，肉眼只有在车上才看得到。而 TextureView 在这条路径上
 * 不出图的表现是**车机上一直黑屏**：没有异常、没有日志、会话照常建立——属于最难在车上定位的那类。
 *
 * 所以这里用 ImageReader 顶替编码器 surface，直接读帧：读到非黑像素才算通过。
 * 它不验证「好不好看」，只验证「视频有没有走到编码器前面」。
 */
@RunWith(AndroidJUnit4::class)
class SplashVideoSurfaceTest {
    @Test(timeout = 45_000) fun splashVideoReachesTheEncoderSurface() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val width = 960
        val height = 540
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val content = runBlocking { LocalVideoDisplay.open(context, reader.surface, width, height, 30) }
        assertNotNull("local display refused to open on a fresh virtual display", content)
        var brightest = 0
        try {
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && brightest == 0) {
                val image = reader.acquireLatestImage()
                if (image == null) {
                    Thread.sleep(100)
                    continue
                }
                try {
                    val buffer = image.planes[0].buffer
                    var lit = 0
                    // 抽样而不是逐像素：一帧 2MB，全扫会把测试拖成秒级，而这里只关心「有没有内容」。
                    val step = 4 * 97
                    var index = 0
                    while (index + 3 < buffer.limit()) {
                        val r = buffer.get(index).toInt() and 0xFF
                        val g = buffer.get(index + 1).toInt() and 0xFF
                        val b = buffer.get(index + 2).toInt() and 0xFF
                        if (r + g + b > 24) lit++
                        index += step
                    }
                    brightest = maxOf(brightest, lit)
                } finally {
                    image.close()
                }
            }
            assertTrue(
                "no frame with visible content reached the surface; the splash page is being encoded " +
                    "as black, which is what a TextureView that fails to composite looks like",
                brightest > 0
            )
        } finally {
            runCatching { content?.close() }
            reader.close()
        }
    }
}
