package com.dadealbit.zhihuextractor

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * 模块二: 隐形抓取引擎。
 * 维护一个 1x1、全透明的隐藏 WebView(必须挂在视图树里才能执行 JS),
 * 用桌面 UA 加载知乎链接 → onPageFinished 后注入 JS → JS 轮询正文容器
 * (.RichText 等, 最多 25 秒) → 提取结果经 @JavascriptInterface 回传。
 * 与可见登录 WebView 共享 CookieManager, 自动带上登录态。
 */
class HiddenScraper(private val context: Context, private val host: ViewGroup) {

    // 与电脑端/插件一致的桌面 UA(让知乎返回桌面版 DOM, 选择器才匹配)
    private val desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0"

    private var webView: WebView? = null
    private var injected = false

    /** 图片二进制流写盘串行队列: JS 逐张串行回传, 这里按序 append, 不占主线程 */
    private val imageIo = Executors.newSingleThreadExecutor()
    private var imgOut: FileOutputStream? = null
    private var imgDir: File? = null

    /** 注入脚本 = Turndown 库 + 提取脚本(读一次缓存) */
    private val extractJs: String by lazy {
        val turndown = context.assets.open("js/turndown.js")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val extract = context.assets.open("js/extract.js")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        turndown + "\n;\n" + extract
    }

    /** 提取一篇文章(挂起直到 JS 回传或 90 秒超时; 含图片抓取写盘耗时) */
    suspend fun extract(url: String): ExtractionResult =
        withTimeoutOrNull(90_000) { extractInner(url) }
            ?: ExtractionResult(ok = false, error = "提取超时(90秒)")

    private suspend fun extractInner(url: String): ExtractionResult =
        suspendCancellableCoroutine { cont ->
            var chunkTotal = -1
            val chunkBuf = StringBuilder()

            // JS 回传结果(超长文章可能较大)分块重组后解析;
            // 解析放图片 IO 串行线程, 保证此前排队的图片写盘全部完成, 且不卡主线程
            fun finish(json: String) {
                val wv = webView
                try {
                    imageIo.execute {
                        closeImageStream()
                        val result = parse(json)
                        wv?.post {
                            cleanup()
                            if (cont.isActive) cont.resume(result)
                        }
                    }
                } catch (e: Exception) {
                    // destroy() 后队列已关闭: 直接兜底解析
                    closeImageStream()
                    val result = parse(json)
                    wv?.post {
                        cleanup()
                        if (cont.isActive) cont.resume(result)
                    }
                }
            }

            val bridge = object {
                @JavascriptInterface
                fun onResultStart(total: Int) {
                    val wv = webView ?: return
                    wv.post {
                        chunkTotal = total
                        chunkBuf.setLength(0)
                    }
                }

                @JavascriptInterface
                fun onResultChunk(index: Int, chunk: String) {
                    val wv = webView ?: return
                    wv.post {
                        if (chunkTotal > 0) chunkBuf.append(chunk)
                    }
                }

                @JavascriptInterface
                fun onResultEnd() {
                    val wv = webView ?: return
                    wv.post { finish(chunkBuf.toString()) }
                }

                @JavascriptInterface
                fun onResult(json: String) {
                    val wv = webView ?: return
                    wv.post { finish(json) }
                }

                // ---- 图片二进制流桥: JS 页面内 fetch 抓到的字节分块传回, 原生流式写盘 ----
                @JavascriptInterface
                fun onImageStart(safeTitle: String, name: String, total: Int) {
                    imageIo.execute {
                        closeImageStream()
                        try {
                            val dir = File(context.cacheDir, "export/$safeTitle/assets").apply { mkdirs() }
                            imgDir = dir
                            imgOut = FileOutputStream(File(dir, safeFileName(name)))
                        } catch (e: Exception) {
                            imgOut = null
                        }
                    }
                }

                @JavascriptInterface
                fun onImageChunk(name: String, index: Int, b64: String) {
                    imageIo.execute {
                        try {
                            imgOut?.write(Base64.decode(b64, Base64.DEFAULT))
                        } catch (e: Exception) { /* 忽略坏块 */ }
                    }
                }

                @JavascriptInterface
                fun onImageEnd(name: String) {
                    imageIo.execute {
                        closeImageStream()
                        // 动图: 原生解码第一帧转同名 png(MD/HTML/PDF 都引用 png, 保证显示)
                        if (name.lowercase().endsWith(".gif")) {
                            val dir = imgDir
                            if (dir != null) GifFirstFrame.convert(dir, safeFileName(name))
                        }
                    }
                }
            }

            injected = false
            val wv = createWebView(bridge)
            webView = wv
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (!injected) {
                        injected = true
                        view.evaluateJavascript(extractJs, null)
                    }
                }
            }
            wv.loadUrl(url)

            cont.invokeOnCancellation {
                val v = webView
                if (v != null) {
                    v.post { cleanup() }
                }
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(bridge: Any): WebView {
        val wv = WebView(context)
        wv.layoutParams = ViewGroup.LayoutParams(1, 1) // 1x1 隐形
        wv.setBackgroundColor(0x00000000)
        wv.alpha = 0f
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.blockNetworkImage = true // 只要 DOM 与属性, 不加载图片像素, 大幅提速
        wv.settings.userAgentString = desktopUa
        wv.addJavascriptInterface(bridge, "AndroidBridge")
        host.addView(wv)
        return wv
    }

    private fun parse(json: String): ExtractionResult {
        return try {
            val obj = JSONObject(json)
            if (!obj.optBoolean("ok", false)) {
                ExtractionResult(ok = false, error = obj.optString("error", "提取失败"))
            } else {
                val images = mutableListOf<ImageRef>()
                val arr = obj.optJSONArray("images")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        images.add(ImageRef(item.optString("url"), item.optString("name")))
                    }
                }
                ExtractionResult(
                    ok = true,
                    title = obj.optString("title", "知乎文章"),
                    safeTitle = obj.optString("safeTitle", "知乎文章"),
                    markdown = obj.optString("markdown"),
                    html = obj.optString("html"),
                    images = images,
                    embedded = obj.optInt("embedded", 0),
                    totalImages = obj.optInt("totalImages", 0)
                )
            }
        } catch (e: Exception) {
            ExtractionResult(ok = false, error = "解析提取结果失败: ${e.message}")
        }
    }

    /** 图片文件名白名单化, 防止页面注入路径穿越 */
    private fun safeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").replace("..", "__")

    private fun closeImageStream() {
        try { imgOut?.close() } catch (e: Exception) { /* ignore */ }
        imgOut = null
    }

    private fun cleanup() {
        try {
            imageIo.execute { closeImageStream() }
        } catch (e: Exception) {
            closeImageStream()
        }
        val wv = webView ?: return
        webView = null
        wv.stopLoading()
        wv.loadUrl("about:blank")
        host.removeView(wv)
        wv.destroy()
    }

    fun destroy() {
        cleanup()
        imageIo.shutdown()
    }
}
