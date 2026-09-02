package com.dadealbit.zhihuextractor

import android.content.Context
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把一篇提取结果打包成 zip(与浏览器插件压缩包同结构, 平铺):
 * <标题>.md / <标题>.html / <标题>.pdf(可选) / assets/<标题>/img_xxx.ext
 * 另带 _mathjax/tex-chtml-full.js 供导出的 HTML 离线渲染公式。
 */
object Zipper {

    fun zipArticle(
        context: Context,
        outZip: File,
        safeTitle: String,
        markdown: String,
        html: String,
        assetsDir: File,
        pdfFile: File? = null
    ) {
        outZip.parentFile?.mkdirs()
        ZipOutputStream(outZip.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("$safeTitle.md"))
            zip.write(markdown.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("$safeTitle.html"))
            zip.write(html.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            if (pdfFile != null && pdfFile.isFile && pdfFile.length() > 0) {
                zip.putNextEntry(ZipEntry("$safeTitle.pdf"))
                pdfFile.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }

            // 本地 MathJax(CHTML): HTML 里公式离线渲染的兜底
            try {
                context.assets.open("js/tex-chtml-full.js").use { input ->
                    zip.putNextEntry(ZipEntry("_mathjax/tex-chtml-full.js"))
                    input.copyTo(zip)
                    zip.closeEntry()
                }
            } catch (e: Exception) { /* 页面内有 CDN 兜底 */ }

            if (assetsDir.isDirectory) {
                assetsDir.listFiles()?.sortedBy { it.name }?.forEach { file ->
                    if (!file.isFile) return@forEach
                    zip.putNextEntry(ZipEntry("assets/$safeTitle/${file.name}"))
                    file.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }
}
