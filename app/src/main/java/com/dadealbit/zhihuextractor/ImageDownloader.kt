package com.dadealbit.zhihuextractor

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 图片下载结果: 成功数 + 失败清单(供上层把失败图替换回原图 URL) */
data class DownloadResult(val successCount: Int, val failed: List<ImageRef>)

/**
 * 模块四: 图片并发下载。
 * 带上知乎 Referer(绕过防盗链)与 Cookie, 4 路并发、每张最多重试 2 次,
 * 文件名与 JS 提取时写入 md/html 的相对路径完全一致。
 */
class ImageDownloader(private val assetsDir: File) {

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0"

    suspend fun download(images: List<ImageRef>, onProgress: (Int, Int) -> Unit): DownloadResult =
        withContext(Dispatchers.IO) {
            if (images.isEmpty()) return@withContext DownloadResult(0, emptyList())
            assetsDir.mkdirs()
            val semaphore = Semaphore(3) // 并发限 3 路, 降低被知乎风控限流的概率
            var done = 0
            val failed = mutableListOf<ImageRef>()
            images.map { image ->
                async {
                    semaphore.withPermit {
                        var ok = false
                        for (attempt in 1..2) {
                            if (downloadOne(image)) {
                                ok = true
                                break
                            }
                        }
                        synchronized(this@ImageDownloader) {
                            done += 1
                            if (!ok) failed.add(image)
                            onProgress(done, images.size)
                        }
                    }
                }
            }.awaitAll()
            DownloadResult(images.size - failed.size, failed.toList())
        }

    private fun downloadOne(image: ImageRef): Boolean {
        // 先带 Referer, 失败再不带 Referer 各试一次(绕过防盗链的多种情况)
        return downloadWith(image, referer = true) || downloadWith(image, referer = false)
    }

    private fun downloadWith(image: ImageRef, referer: Boolean): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(image.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 45_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", ua)
            if (referer) {
                conn.setRequestProperty("Referer", "https://www.zhihu.com/")
            }
            // 关键: 不声明 avif —— 知乎会返回 AVIF 格式, 手机浏览器/编辑器解码不了会全部裂图。
            // 只声明通用格式(jpeg/png/gif/webp), 保证存成 .jpg 文件名后处处可解
            conn.setRequestProperty("Accept", "image/jpeg,image/png,image/gif,image/webp;q=0.9,*/*;q=0.8")
            CookieManager.getInstance().getCookie(image.url)
                ?.let { conn.setRequestProperty("Cookie", it) }
            if (conn.responseCode in 200..299) {
                conn.inputStream.use { input ->
                    File(assetsDir, image.name).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                // 动图: 原生解码第一帧转同名 png, 保证查看器/PDF 必然显示
                if (image.name.lowercase().endsWith(".gif")) {
                    GifFirstFrame.convert(assetsDir, image.name)
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }
}
