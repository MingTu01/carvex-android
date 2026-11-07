package io.github.iamr0s.carvex

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import io.github.iamr0s.carvex.sessioncore.BridgeStore
import java.io.File

/** 车机启动画面的自定义素材：用户上传的一段视频或一张图片，放不下就退回内置视频。
 *
 *  为什么放在应用私有目录而不是原位置：投屏时读的是 `MediaPlayer` / `BitmapFactory`，
 *  而 SAF 给的 `content://` 授权只在本次进程、本次授权内有效 —— 车机上重新连一次就失效了，
 *  表现是"上传过的启动画面没了"，而且只在车上复现。复制一份到 filesDir 之后就与原文件无关了。
 *
 *  5 MB 上限不是随意的：这段画面要进 VirtualDisplay 再被编码，包在 APK 里那份就有 5 MB，
 *  再大只会拖长第一次连接前的等待，而 720p 的片源到这个体积已经够用。 */
object SplashAsset {
    /** 内置视频；上传被清掉之后回到它。 */
    const val DEFAULT_VIDEO = "splash.mp4"
    const val MAX_BYTES = 5L * 1024L * 1024L

    private const val DIR = "splash"
    private const val PREFIX = "custom."
    private val VIDEO_EXT = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp")

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** SAF 的文件名。扩展名决定类型，所以必须拿真名，不能用 URI 的最后一段（那通常是数字 id）。 */
    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (_: Throwable) {
        null
    }

    /** 当前生效的素材；null 表示用内置视频。 */
    fun current(context: Context): File? = dir(context).listFiles()
        ?.firstOrNull { it.isFile && it.name.startsWith(PREFIX) && it.length() > 0L }

    fun isVideo(file: File): Boolean = file.extension.lowercase() in VIDEO_EXT
    fun isImage(file: File): Boolean = file.extension.lowercase() in IMAGE_EXT

    /** 上传替换：复制到私有目录，超过 [MAX_BYTES] 或类型不认识就拒绝。
     *
     *  返回 null 表示成功，否则是一句可以直接显示给用户的原因 —— 这个操作只在这一处发生，
     *  没必要为它造一个异常类型。 */
    fun save(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver
        val name = (displayName(context, uri) ?: uri.lastPathSegment ?: "splash").lowercase()
        val ext = name.substringAfterLast('.', "")
        if (ext !in VIDEO_EXT && ext !in IMAGE_EXT) return "只支持视频或图片（mp4 / webm / mov / jpg / png / webp）"

        val target = File(dir(context), PREFIX + ext)
        val temp = File(dir(context), PREFIX + ext + ".tmp")
        val copied = try {
            resolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        // 先看再加：刚好卡在 5 MB 的文件不该因为多读一个缓冲就被判超限。
                        if (total > MAX_BYTES) return "文件超过 5 MB"
                        output.write(buffer, 0, read)
                    }
                    total
                }
            } ?: return "打不开这个文件"
        } catch (t: Throwable) {
            temp.delete()
            return "读取失败：" + (t.message ?: t.javaClass.simpleName)
        }
        if (copied <= 0L) {
            temp.delete()
            return "文件是空的"
        }
        // 旧的先删：换类型时扩展名会变，留着会出现两份素材，先读到哪份不确定。
        dir(context).listFiles()?.forEach { if (it != temp) it.delete() }
        if (!temp.renameTo(target)) {
            temp.delete()
            return "保存失败"
        }
        BridgeStore.log(BridgeStore.state.value.id, "splash asset replaced: " + target.name + " " + copied + "B")
        return null
    }

    fun clear(context: Context) {
        val removed = dir(context).listFiles()?.count { it.delete() } ?: 0
        if (removed > 0) BridgeStore.log(BridgeStore.state.value.id, "splash asset reset to bundled video; removed=$removed")
    }

    /** 设置页那一行要显示的内容。 */
    fun describe(context: Context): String {
        val file = current(context) ?: return "内置默认视频（" + assetName(context, DEFAULT_VIDEO) + "）"
        val kind = when {
            isVideo(file) -> "视频"
            isImage(file) -> "图片"
            else -> "未知"
        }
        return kind + "：" + file.name.removePrefix(PREFIX) + "（" + (file.length() / 1024) + " KB）"
    }

    /** 内置素材的体积，用来在设置里说清"默认用的是哪一个"。 */
    private fun assetName(context: Context, asset: String): String = try {
        context.assets.open(asset).use { stream ->
            val size = stream.available()
            if (size > 0) "约 " + (size / 1024 / 1024) + " MB" else "内置"
        }
    } catch (_: Throwable) {
        "内置"
    }

    /** 起屏用的位图；只在上传的是图片时用到。失败返回 null，调用方退回默认页。 */
    fun decodeImage(context: Context, file: File): Bitmap? = try {
        BitmapFactory.decodeFile(file.absolutePath)
    } catch (_: Throwable) {
        null
    }
}
