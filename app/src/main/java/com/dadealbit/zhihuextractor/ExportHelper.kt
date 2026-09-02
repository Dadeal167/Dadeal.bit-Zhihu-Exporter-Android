package com.dadealbit.zhihuextractor

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 模块五(文件 I/O): 把 zip 保存到用户可见的「下载/知乎导出」目录。
 * Android 10+ 走 MediaStore(Scoped Storage 规范); Android 9 及以下直接写公共目录。
 */
object ExportHelper {

    fun saveZipToDownloads(context: Context, zipFile: File, safeTitle: String): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, zipFile, safeTitle)
        } else {
            saveLegacy(zipFile, safeTitle)
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(zipFile: File, safeTitle: String): String {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "知乎导出"
        )
        dir.mkdirs()
        val dest = File(dir, "$safeTitle.zip")
        zipFile.copyTo(dest, overwrite = true)
        return dest.absolutePath
    }

    private fun saveViaMediaStore(context: Context, zipFile: File, safeTitle: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$safeTitle.zip")
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/知乎导出")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法在下载目录创建文件")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                zipFile.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("无法写入下载目录")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "下载/知乎导出/$safeTitle.zip"
    }
}
