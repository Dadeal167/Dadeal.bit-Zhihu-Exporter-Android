# Dadeal.bit知乎内容提取器

与电脑端和浏览器插件同源的手机 App：
**只保留提取功能**——把知乎文章/回答提取成 Markdown + HTML（含图片），打包成 zip 存到「下载/知乎导出」。

## 功能

- ✅ **扫码登录**：内置登录页（手机 UA），登录一次即记住会话，之后打开直接用
- ✅ **免风控**：抓取在隐藏 WebView 中进行，用的是你真实登录的浏览器会话（与浏览器插件同理，不受桌面版 requests 风控影响）
- ✅ **PDF 静默生成**：隐藏 WebView 渲染（等公式渲染完成）→ 打印适配器直接写成 PDF 文件，不弹任何系统界面
- ✅ 图片自动下载（带知乎 Referer 绕过防盗链，4 路并发）
- ✅ 数学公式：Markdown 还原 `$...$`；HTML/PDF 由 MathJax 渲染
- ✅ 剪贴板自动填入：复制知乎链接 → 打开 App 自动填入
- ✅ 支持多行链接批量（顺序逐篇提取）
- ✅ 导出到 `下载/知乎导出/<标题>.zip`（Android 10+ 走 MediaStore 分区存储规范）

zip 结构与插件一致（平铺）：
```
<标题>.zip
├── <标题>.md
├── <标题>.html
├── <标题>.pdf          ← 勾选「同时生成 PDF」时
└── assets/<标题>/img_001.png
```

## 五个模块的对应实现

| 模块 | 实现 |
|---|---|
| 一 登录接管 | `MainActivity` 可见 WebView 加载 `zhihu.com/signin`（手机 UA），`onPageFinished` 读 CookieManager，出现 `z_c0` 即登录成功；`CookieStore` 持久化会话，隐藏 WebView 自动共享 |
| 二 隐形抓取 | `HiddenScraper`：1x1 透明隐藏 WebView（桌面 UA），`onPageFinished` 注入 JS，JS 轮询 `.RichText` 等正文容器（最多 25 秒），经 `@JavascriptInterface` 回传完整 HTML |
| 三 清洗转换 | 注入的 `assets/js/extract.js`：公式（新版 `data-tex` / 旧版 `img.eeimg`）转 `$...$`，Turndown 转 Markdown，生成带 MathJax CDN 的干净 HTML |
| 四 图片引擎 | `ImageDownloader`：协程 4 路并发下载，带 Referer/Cookie，保存为 `assets/<标题>/img_xxx.ext`，与 md/html 相对路径一致 |
| 五 UI 与 I/O | 剪贴板监听（onResume）+ 进度提示（解析中 → 等待正文渲染 → 下载图片 x/n → 打包 zip → 导出成功）；`ExportHelper` 按 Android 10+ Scoped Storage 规范写入下载目录 |

## 构建（需要 Android Studio）

1. 安装 [Android Studio](https://developer.android.com/studio)（自带 Android SDK + JDK 17）
2. File → Open → 选择本文件夹 `android_app/`
3. 等 Gradle 同步完成（首次会自动下载 AGP/Kotlin 依赖）
4. 手机开启「开发者选项 → USB 调试」，连接电脑
5. 点 Run ▶ 安装到手机；或 Build → Build APK(s) 得到安装包

命令行备选（已配置 Gradle Wrapper）：
```
cd android_app
gradlew.bat assembleDebug
```
输出：`app/build/outputs/apk/debug/app-debug.apk`

## 使用

1. 首次打开 → 内置页面扫码登录知乎（出现 `z_c0` 自动进入主界面）
2. 复制知乎文章/回答链接（或直接粘贴多行）
3. 点「开始提取」→ 等待进度走完
4. 到手机「文件管理 → 下载/知乎导出」里解压 zip 查看

## 已知限制（如实说明）

- HTML/PDF 里的公式走本地 MathJax（随 App 打包，**离线可渲染**）；Markdown 的公式交给 Obsidian/Typora 渲染
- PDF：正文分块截图（高质 JPEG，内存安全不崩溃），**数学公式由 MathJax SVG 矢量路径直接写入 PDF（LaTeX 引擎排版，放大不失真）**
- 知乎"动图"分两类：GIF 保留原图（PDF 显示第一帧）；`<video>` 视频动图提取为**静态封面图**（播放按钮覆盖层已清理，不再有黑圈）
- 批量防风控：每篇之间自动随机休眠 5~12 秒，图片并发限 3 路；触发安全验证时会提示并打开登录页让你手动完成
- 首次登录需在 App 内扫码；登录过期后点「重新登录」
- 极老文章的公式是图片形式（`img.eeimg`），Markdown 中还原为 LaTeX，HTML/PDF 保持图片原样
- **免责声明**：本工具仅供个人备份使用，请勿用于商业抓取或大规模采集；新注册/空白账号批量操作更容易触发知乎风控，建议使用常用账号、控制每批数量（≤ 20）

## 目录结构

```
android_app/
├── gradlew / gradlew.bat / gradle/wrapper/   # Gradle Wrapper(可直接构建)
├── settings.gradle.kts / build.gradle.kts / gradle.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/js/turndown.js   # HTML→Markdown(与插件同库)
        ├── assets/js/extract.js    # 提取/公式/图片清单/轮询回传
        ├── java/com/dadealbit/zhihuextractor/
        │   ├── MainActivity.kt     # 登录 WebView + 剪贴板 + 进度 UI + 主流程
        │   ├── HiddenScraper.kt    # 隐形抓取引擎
        │   ├── PdfRenderer.kt      # PDF 静默渲染(WebView 打印适配器)
        │   ├── ImageDownloader.kt  # 图片并发下载(Referer/Cookie)
        │   ├── Zipper.kt           # zip 打包
        │   ├── ExportHelper.kt     # Scoped Storage 导出
        │   ├── CookieStore.kt      # 会话持久化
        │   └── ExtractionModels.kt
        └── res/                    # 布局/字符串/主题
```
