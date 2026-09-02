package com.dadealbit.zhihuextractor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/**
 * 动图第一帧提取(Android 原生解码, 不依赖 WebView canvas, 可靠):
 * GIF/动图字节已原样保存为 xxx.gif, 再用 BitmapFactory 解码其第一帧
 * 转成同名 xxx.png。MD/HTML/PDF 统一引用 png —— 任何查看器都必然显示,
 * 不再出现动图位置一大片空白的问题; 原始 gif 仍保留在 zip 里。
 */
object GifFirstFrame {

    /**
     * 把 dir 下的 gifName(xxx.gif) 转成第一帧 xxx.png。
     * 已存在有效 png 则直接返回; 解码失败返回 false(保留原 gif)。
     */
    fun convert(dir: File, gifName: String): Boolean {
        val gif = File(dir, gifName)
        if (!gif.isFile || gif.length() == 0L) return false
        val png = File(dir, gifName.removeSuffix(".gif") + ".png")
        if (png.isFile && png.length() > 0L) return true
        return try {
            // 先读尺寸, 超大图按需降采样, 防止 OOM
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(gif.absolutePath, bounds)
            var sample = 1
            while ((bounds.outWidth / sample) > 1600 || (bounds.outHeight / sample) > 1600) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeFile(gif.absolutePath, opts) ?: return false
            val ok = try {
                FileOutputStream(png).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } finally {
                bmp.recycle()
            }
            ok && png.length() > 0L
        } catch (e: Exception) {
            false
        }
    }
}
