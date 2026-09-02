package com.dadealbit.zhihuextractor

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

/**
 * 主界面:
 * - 模块一: 未登录时显示可见登录 WebView(手机 UA, 扫码), 检测到 z_c0 自动进入主界面
 * - 模块五: 剪贴板自动填入知乎链接 + 四步进度提示(解析→等待渲染→下载图片 x/n→打包导出)
 */
class MainActivity : Activity() {

    // 登录 WebView 用手机 UA(标准真实手机浏览器 UA), 隐藏抓取 WebView 用桌面 UA(见 HiddenScraper)
    private val mobileUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var authWebView: WebView
    private lateinit var scraper: HiddenScraper
    private lateinit var pdfRenderer: PdfRenderer

    private lateinit var tvStatus: TextView
    private lateinit var authContainer: FrameLayout
    private lateinit var mainPanel: LinearLayout
    private lateinit var etUrl: EditText
    private lateinit var cbPdf: CheckBox
    private lateinit var btnExtract: Button
    private lateinit var tvStep: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvDetail: TextView
    private lateinit var tvResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus) as TextView
        authContainer = findViewById(R.id.authContainer) as FrameLayout
        mainPanel = findViewById(R.id.mainPanel) as LinearLayout
        etUrl = findViewById(R.id.etUrl) as EditText
        cbPdf = findViewById(R.id.cbPdf) as CheckBox
        btnExtract = findViewById(R.id.btnExtract) as Button
        tvStep = findViewById(R.id.tvStep) as TextView
        progressBar = findViewById(R.id.progressBar) as ProgressBar
        tvDetail = findViewById(R.id.tvDetail) as TextView
        tvResult = findViewById(R.id.tvResult) as TextView

        CookieManager.getInstance().setAcceptCookie(true)
        CookieStore.restore(this)

        setupAuthWebView()
        scraper = HiddenScraper(this, findViewById(R.id.rootLayout) as ViewGroup)
        pdfRenderer = PdfRenderer(this, findViewById(R.id.rootLayout) as ViewGroup)

        btnExtract.setOnClickListener { extractUrls() }
        (findViewById(R.id.btnRelogin) as Button).setOnClickListener {
            CookieStore.clear(this)
            showLogin()
        }

        if (CookieStore.hasLogin()) showMain() else showLogin()
    }

    override fun onResume() {
        super.onResume()
        fillFromClipboardIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        scraper.destroy()
        try {
            authWebView.destroy()
        } catch (e: Exception) { /* ignore */ }
    }

    // ============ 模块一: 登录 WebView ============

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupAuthWebView() {
        authWebView = WebView(this)
        authWebView.settings.javaScriptEnabled = true
        authWebView.settings.domStorageEnabled = true
        authWebView.settings.userAgentString = mobileUa
        authWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // 每次页面加载完成读全局 Cookie; 出现 z_c0 即登录成功
                if (CookieStore.hasLogin()) {
                    CookieStore.persist(this@MainActivity)
                    showMain()
                }
            }
        }
        authContainer.addView(
            authWebView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun showLogin() {
        authContainer.visibility = View.VISIBLE
        mainPanel.visibility = View.GONE
        tvStatus.text = getString(R.string.status_logging_in)
        authWebView.loadUrl("https://www.zhihu.com/signin?next=%2F")
    }

    private fun showMain() {
        authContainer.visibility = View.GONE
        mainPanel.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.status_logged_in)
    }

    // ============ 模块五: 剪贴板自动填入 ============

    private fun fillFromClipboardIfNeeded() {
        if (!etUrl.text.isNullOrBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = try {
            cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        } catch (e: Exception) {
            ""
        }
        if (text.contains("zhihu.com") && (text.contains("/p/") || text.contains("/answer/"))) {
            etUrl.setText(text.trim())
            appendResult("📋 已从剪贴板填入链接\n")
        }
    }

    // ============ 提取主流程 ============

    private fun extractUrls() {
        val lines = etUrl.text.toString().lines()
            .map { it.trim() }
            .filter { it.startsWith("http") && it.contains("zhihu.com") }
        if (lines.isEmpty()) {
            appendResult("❌ 请先粘贴知乎文章/回答链接\n")
            return
        }
        if (!CookieStore.hasLogin()) {
            appendResult("⚠️ 尚未登录知乎, 正在打开登录页...\n")
            showLogin()
            return
        }
        setBusy(true)
        scope.launch {
            try {
                for ((i, url) in lines.withIndex()) {
                    val tag = if (lines.size > 1) "(${i + 1}/${lines.size})" else ""

                    // 批量防风控: 每篇之间随机休眠 6~15 秒(模拟正常阅读节奏, 新号更稳)
                    if (i > 0) {
                        val waitMs = Random.nextLong(6_000, 15_001)
                        var leftSec = waitMs / 1000
                        while (leftSec > 0) {
                            tvStep.text = "$tag 🛡️ 防风控休眠: ${leftSec} 秒后开始下一篇..."
                            delay(1000)
                            leftSec -= 1
                        }
                    }

                    tvStep.text = "$tag 正在打开文章, 提取正文并抓取图片..."
                    val result = scraper.extract(url)
                    if (!result.ok) {
                        val msg = result.error ?: "提取失败"
                        appendResult("❌ $tag $msg\n")
                        if (msg.contains("风控") || msg.contains("安全验证") || msg.contains("验证码")) {
                            appendResult("⚠️ 已打开登录页, 请在知乎里手动完成安全验证, 然后重新提取。\n")
                            showLogin()
                            break
                        }
                        continue
                    }
                    appendResult("📄 $tag 《${result.title}》解析完成 " +
                        "(图片已抓取写入本地 ${result.embedded}/${result.totalImages})\n")

                    // 只有页面内抓取失败(极少)的图片才需要原生下载兜底
                    tvStep.text = "$tag 正在下载兜底图片..."
                    val assetsDir = File(cacheDir, "export/${result.safeTitle}/assets").apply { mkdirs() }
                    val dlResult = ImageDownloader(assetsDir).download(result.images) { done, total ->
                        // 回调来自 IO 线程, 切回主线程更新进度
                        runOnUiThread {
                            tvDetail.text = "图片 $done/$total"
                            progressBar.isIndeterminate = false
                            progressBar.progress = if (total == 0) 100 else done * 100 / total
                        }
                    }
                    tvDetail.text = "兜底下载 ${dlResult.successCount}/${result.images.size}"
                    if (dlResult.successCount == 0 && result.images.isNotEmpty()) {
                        appendResult("⚠️ 部分图片未能抓取(已保留原图链接, 需联网查看): " +
                            "可能是知乎风控或网络问题, 建议重新登录或稍后再试\n")
                    }

                    // 下载失败的图片: 本地相对路径已失效, 替换回原图 URL
                    // (zip 里的 HTML 在线可看; PDF 页面由 WebView 带 Referer 补图)
                    val failedMap = dlResult.failed.associate { it.name to it.url }
                    val fixedHtml = if (failedMap.isEmpty()) result.html
                    else replaceFailedImages(result.html, result.safeTitle, failedMap)

                    // PDF(可选): 隐藏 WebView 静默渲染, 不弹任何界面
                    var pdfFile: File? = null
                    if (cbPdf.isChecked) {
                        tvStep.text = "$tag 正在生成 PDF..."
                        val workDir = File(cacheDir, "export/${result.safeTitle}")
                        val printFile = File(workDir, "_print.html")
                        // 打印页与 assets 目录同在 workDir 下: 把 "assets/<标题>/" 相对路径
                        // 修正为 "assets/"(打印页的相对路径), 让 PDF 直接读本地图片, 离线可用
                        val printHtml = buildPrintHtml(fixedHtml)
                            .replace("assets/${result.safeTitle}/", "assets/")
                        printFile.writeText(printHtml)
                        val pdf = File(workDir, "${result.safeTitle}.pdf")
                        if (pdfRenderer.render(printFile, pdf) && pdf.length() > 0) {
                            pdfFile = pdf
                        } else {
                            appendResult("⚠️ $tag PDF 生成失败, 已跳过(其余文件正常)\n")
                        }
                    }

                    tvStep.text = "$tag 正在打包 zip..."
                    val path = withContext(Dispatchers.IO) {
                        val zipFile = File(cacheDir, "export/${result.safeTitle}.zip")
                        Zipper.zipArticle(
                            this@MainActivity, zipFile, result.safeTitle,
                            result.markdown, fixedHtml, assetsDir, pdfFile)
                        val saved = ExportHelper.saveZipToDownloads(
                            this@MainActivity, zipFile, result.safeTitle)
                        // 清理缓存工作目录
                        File(cacheDir, "export/${result.safeTitle}").deleteRecursively()
                        saved
                    }
                    appendResult("✅ $tag ${result.title} → $path\n")
                }
                tvStep.text = "导出完成"
            } catch (e: Exception) {
                appendResult("❌ 发生异常: ${e.message}\n")
                tvStep.text = "导出中断"
            } finally {
                setBusy(false)
                tvDetail.text = ""
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        btnExtract.isEnabled = !busy
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) {
            progressBar.isIndeterminate = true
            progressBar.progress = 0
        }
    }

    private fun appendResult(text: String) {
        tvResult.append(text)
    }

    /**
     * 打印用 HTML: 在导出 HTML 基础上加入:
     * 1. 本地 MathJax(CHTML, 随扩展打包, 离线渲染公式) + CDN 双层兜底
     * 2. "公式渲染完成标记"脚本(渲染完设置 data-mathjax-ready, 15 秒兜底必设)
     * 3. html2canvas + jsPDF 生成脚本(等图片/公式就绪后截图分页, base64 回传原生层)
     * 文件写在 assets 同级目录, 图片相对路径与三个 JS 库相对引用直接可用。
     */
    private fun buildPrintHtml(html: String): String {
        // 打印页用 MathJax SVG 输出(LaTeX 引擎渲染的矢量路径), 本地 tex-svg-full.js 优先 + CDN 兜底
        val mathjaxTag = "<script>MathJax = { tex: { inlineMath: [['$','$']], displayMath: [['$$','$$']] }, " +
            "svg: { fontCache: 'none' } };</script>" +
            "<script type=\"text/javascript\" async src=\"tex-svg-full.js\" " +
            "onerror=\"var s=document.createElement('script');" +
            "s.src='https://registry.npmmirror.com/mathjax/3.2.2/files/es5/tex-svg-full.js';" +
            "s.onerror=function(){var s2=document.createElement('script');" +
            "s2.src='https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-svg-full.js';" +
            "s2.async=true;document.head.appendChild(s2);};" +
            "s.async=true;document.head.appendChild(s);\"></script>"

        // 把导出 HTML 里原有的 MathJax 配置与加载脚本移除, 换成打印页专用(本地优先)
        var out = html
        out = out.replace(Regex("<script>[^<]*MathJax\\s*=[\\s\\S]*?</script>"), "")
        out = out.replace(Regex("<script[^>]*tex-chtml-full[^>]*></script>"), "")
        out = out.replace("</body>", mathjaxTag + "</body>")

        // 用固定宽度的包裹层承载截图内容, 保证分块截取的坐标稳定
        out = out.replace(
            "<body>",
            "<body><div id=\"pdf-capture\" style=\"width:800px;margin:0 auto;padding:24px;background:#fff;\">")
        out = out.replace("</body>", "</div></body>")

        val marker = "<script>" +
            "(function(){var done=false;function mark(){if(done)return;done=true;" +
            "document.body.setAttribute('data-mathjax-ready','1');}" +
            "window.addEventListener('load',function(){" +
            "if(window.MathJax&&window.MathJax.startup&&window.MathJax.typesetPromise){" +
            "MathJax.startup.promise.then(function(){return MathJax.typesetPromise();})" +
            ".then(mark).catch(mark);}else{mark();}});setTimeout(mark,15000);})();</script>"

        // 分块截图 + 高质 JPEG(内存安全) + 公式矢量增强:
        // 截图时【不隐藏公式】(截图里必然有公式, 保底可见);
        // 截图后用 pdf.svg(svg2pdf) 把 MathJax SVG 矢量层覆盖上去 ——
        // 成功则公式为 LaTeX 矢量路径(放大不失真), 失败则保留截图里的公式, 不会消失
        val pdfGen = "<script src=\"html2canvas.min.js\"></script>\n" +
            "<script src=\"jspdf.umd.min.js\"></script>\n" +
            "<script src=\"svg2pdf.umd.min.js\"></script>\n" +
            "<script>" +
            "(function(){" +
            "function send(ok,data){window.AndroidBridge.onPdf(JSON.stringify({ok:ok,base64:data||''}));}" +
            "var deadline=Date.now()+40000;" +
            "function start(){" +
            "try{" +
            "var cap=document.getElementById('pdf-capture');" +
            "var pdf=new jspdf.jsPDF({unit:'mm',format:'a4'});" +
            "var pageW=210,pageH=297,margin=8;" +
            "var imgW=pageW-margin*2;" +
            "var scale=2;" +
            "var chunkH=1100;" +
            "var totalH=cap.scrollHeight||1000;" +
            "var mmPerCss=imgW/800;" +
            "var canSvg=(typeof pdf.svg==='function');" +
            "var y=0;" +
            "function next(){" +
            "if(y>=totalH){var dataUrl=pdf.output('datauristring');send(true,dataUrl.split(',')[1]);return;}" +
            "var h=Math.min(chunkH,totalH-y);" +
            "html2canvas(cap,{scale:scale,useCORS:false,backgroundColor:'#ffffff',logging:false,windowWidth:800,x:0,y:y,width:800,height:h})" +
            ".then(function(canvas){" +
            "var imgH=canvas.height*imgW/canvas.width;" +
            "var imgData=canvas.toDataURL('image/jpeg',0.97);" +
            "canvas.width=1;canvas.height=1;canvas=null;" +
            "if(y>0){pdf.addPage();}" + // 首页用 jsPDF 初始空白页, 之后每块才翻页(修第一页空白)
            "pdf.addImage(imgData,'JPEG',margin,margin,imgW,imgH);" +
            "imgData=null;" +
            "if(canSvg){" +
            "var capRect=cap.getBoundingClientRect();" +
            "document.querySelectorAll('mjx-container[jax=\"SVG\"]').forEach(function(m){" +
            "var svg=m.querySelector('svg');" +
            "if(!svg)return;" +
            "var r=svg.getBoundingClientRect();" +
            "var top=r.top-capRect.top,left=r.left-capRect.left;" +
            "if(top>=y-1&&(top+r.height)<=y+h+1){" +
            "try{" +
            "pdf.svg(svg,{x:margin+left*mmPerCss,y:margin+(top-y)*mmPerCss,width:r.width*mmPerCss,height:r.height*mmPerCss});" +
            "}catch(e){}" +
            "}" +
            "});" +
            "}" +
            "y+=chunkH;" +
            "setTimeout(next,60);" +
            "}).catch(function(e){send(false);});" +
            "}" +
            "next();" +
            "}catch(e){send(false);}" +
            "}" +
            "function whenReady(){" +
            "var imgs=Array.prototype.slice.call(document.images);" +
            "var pending=imgs.filter(function(i){return !i.complete;});" +
            "var mathReady=document.body.getAttribute('data-mathjax-ready')==='1';" +
            "if(pending.length===0&&mathReady){setTimeout(start,800);return;}" +
            "if(Date.now()>deadline){start();return;}" +
            "setTimeout(whenReady,400);" +
            "}" +
            "whenReady();" +
            "})();</script>"

        return if (out.contains("</body>")) {
            out.replace("</body>", marker + pdfGen + "</body>")
        } else {
            out + marker + pdfGen
        }
    }

    /**
     * 把下载失败的图片相对路径替换回原图 URL(在线可看; 打印页由 WebView 带 Referer 补图)
     */
    private fun replaceFailedImages(html: String, safeTitle: String, failed: Map<String, String>): String {
        var out = html
        failed.forEach { (name, url) ->
            val rel = "assets/$safeTitle/$name"
            val relEscaped = rel.replace("&", "&amp;")
            val urlEscaped = url.replace("&", "&amp;")
            out = out.replace(relEscaped, urlEscaped).replace(rel, urlEscaped)
        }
        return out
    }
}
