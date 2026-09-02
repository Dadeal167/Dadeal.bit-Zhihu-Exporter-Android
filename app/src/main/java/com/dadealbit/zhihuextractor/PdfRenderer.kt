package com.dadealbit.zhihuextractor

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * 静默生成 PDF: 隐藏 WebView 加载打印 HTML, 页面内用 html2canvas + jsPDF
 * (与浏览器插件同一套方案) 生成 PDF, base64 经 @JavascriptInterface 回传。
 * 全程无任何系统界面弹出。
 *
 * - MathJax 用本地打包的 tex-chtml-full.js(CHTML 输出对 html2canvas 友好, 离线可用)
 * - 未下载成功的图片在打印页回退为原图 URL, 由 shouldInterceptRequest 带知乎
 *   Referer 抓取, 绕过防盗链
 */
class PdfRenderer(private val context: Context, private val host: ViewGroup) {

    private val desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0"

    /** 渲染 printFile 所在页面为 PDF; 成功返回 true */
    suspend fun render(printFile: File, outPdf: File): Boolean =
        withTimeoutOrNull(150_000) { renderInner(printFile, outPdf) } ?: false

    @SuppressLint("SetJavaScriptEnabled", "SetAllowFileAccessFromFileURLs")
    private suspend fun renderInner(printFile: File, outPdf: File): Boolean {
        // 把页面内需要的 JS 库从 assets 复制到打印页同目录(相对引用)
        val workDir = printFile.parentFile ?: return false
        copyAsset("js/html2canvas.min.js", File(workDir, "html2canvas.min.js"))
        copyAsset("js/jspdf.umd.min.js", File(workDir, "jspdf.umd.min.js"))
        copyAsset("js/svg2pdf.umd.min.js", File(workDir, "svg2pdf.umd.min.js"))
        copyAsset("js/tex-svg-full.js", File(workDir, "tex-svg-full.js"))

        val wv = WebView(context)
        wv.layoutParams = ViewGroup.LayoutParams(800, 600)
        wv.setBackgroundColor(0xFFFFFFFF.toInt())
        wv.alpha = 0f
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.allowFileAccess = true
        // file:// 打印页读同目录本地图片/JS(相对路径), 离线渲染 PDF 的关键
        wv.settings.allowFileAccessFromFileURLs = true
        wv.settings.userAgentString = desktopUa
        wv.webViewClient = object : WebViewClient() {
            // 拦截图片请求: 带知乎 Referer 抓取, 让回退原图的图片也能加载(绕过防盗链)
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url?.toString() ?: return null
                if (!url.startsWith("http")) return null
                if (request.method?.uppercase() != "GET") return null
                return try {
                    interceptImage(url)
                } catch (e: Exception) {
                    null
                }
            }
        }
        host.addView(wv)
        try {
            return suspendCancellableCoroutine { cont ->
                wv.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onPdf(json: String) {
                        wv.post {
                            val ok = writePdf(json, outPdf)
                            if (cont.isActive) cont.resume(ok)
                        }
                    }
                }, "AndroidBridge")
                wv.loadUrl("file://" + printFile.absolutePath)
            }
        } finally {
            try {
                host.removeView(wv)
                wv.destroy()
            } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun interceptImage(url: String): WebResourceResponse? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 45_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", desktopUa)
            conn.setRequestProperty("Referer", "https://www.zhihu.com/")
            // 不声明 avif(知乎会返回 AVIF, 部分环境解码不了); 只声明通用格式
            conn.setRequestProperty("Accept", "image/jpeg,image/png,image/gif,image/webp;q=0.9,*/*;q=0.8")
            if (conn.responseCode in 200..299) {
                val mime = conn.contentType?.substringBefore(";")?.trim() ?: "image/jpeg"
                val bytes = conn.inputStream.use { it.readBytes() }
                WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(bytes))
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun writePdf(json: String, outPdf: File): Boolean {
        return try {
            val obj = org.json.JSONObject(json)
            val ok = obj.optBoolean("ok", false)
            val base64 = obj.optString("base64", "")
            if (ok && base64.isNotEmpty()) {
                outPdf.parentFile?.mkdirs()
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                outPdf.writeBytes(bytes)
                outPdf.length() > 0
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun copyAsset(name: String, dest: File) {
        try {
            if (!dest.exists() || dest.length() == 0L) {
                context.assets.open(name).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
        } catch (e: Exception) { /* 忽略, 页面内有加载失败处理 */ }
    }
}
