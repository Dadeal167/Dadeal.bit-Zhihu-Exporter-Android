// Dadealbit Android 提取脚本: 在隐藏 WebView 的知乎页面里运行
// 与电脑端/浏览器插件同一套提取逻辑(选择器/公式/图片清单), 完成后通过
// window.AndroidBridge.onResult(json) 回传原生层。
(function () {
  "use strict";

  // 当前正在提取的文章安全文件名(图片桥接写盘时用)
  var safeTitleOfExtract = "";

  var CONTENT_SELECTORS = [
    ".Post-RichText",
    ".RichContent-inner",
    ".AnswerItem .RichText",
    ".RichText.ztext.Post-RichText",
    ".RichText",
    ".Post-Content",
    "article"
  ];

  function pick() {
    for (var i = 0; i < CONTENT_SELECTORS.length; i++) {
      var el = document.querySelector(CONTENT_SELECTORS[i]);
      if (el) return el;
    }
    return null;
  }

  function sanitize(name) {
    var c = String(name || "").replace(/[\\/*?:"<>|]/g, "").trim().replace(/[. ]+$/g, "");
    return c || "未命名文章";
  }

  function getAuthor() {
    var el = document.querySelector(
      ".AuthorInfo-name .UserLink, .AuthorInfo .UserLink, .Post-Author .UserLink");
    return el ? el.textContent.trim() : "";
  }

  function getDate() {
    var el = document.querySelector(
      "time, .ContentItem-time, [itemprop='dateModified'], [itemprop='datePublished'], .Post-Meta time");
    if (!el) return "";
    var t = (el.textContent || el.getAttribute("datetime") || "").trim();
    return t.replace(/^编辑于\s*/, "").replace(/^发布于\s*/, "");
  }

  function getTitle() {
    var h1 = document.querySelector("h1.Post-Title, .Post-Title");
    if (h1 && h1.textContent.trim()) return h1.textContent.trim();
    var q = document.querySelector(".QuestionHeader-title, h1.QuestionTitle");
    if (q && q.textContent.trim()) {
      var qt = q.textContent.trim();
      var author = getAuthor();
      return author ? qt + " - " + author + "的回答" : qt;
    }
    var t = document.title.replace(/\s*[-–—]\s*知乎.*$/, "").trim();
    return t || "知乎文章";
  }

  function imgSrc(img) {
    return img.getAttribute("data-actualsrc")
      || img.getAttribute("data-original")
      || img.getAttribute("data-src")
      || img.getAttribute("src") || "";
  }

  function imageExtension(url) {
    try {
      var m = new URL(url).pathname.match(/\.(gif|png|jpe?g|webp|svg)(?=$|\?)/i);
      if (m) {
        var ext = m[1].toLowerCase();
        return ext === "jpeg" ? "jpg" : ext;
      }
    } catch (e) { /* ignore */ }
    return "jpg";
  }

  // ============ 图片管线: 页面内 fetch(真实浏览器网络栈 + 知乎 Referer,
  // 与正常看文章完全一致, 不受防盗链/风控影响) → 二进制分块桥接到原生层
  // 流式写盘 → MD/HTML 只放相对路径。全程不做 base64 大字符串, 内存峰值低 ============

  // 按服务器返回的真实 Content-Type 决定扩展名(比 URL 后缀可靠):
  // 动图(GIF)原样保存 —— 查看器/浏览器里正常显示播放, PDF 由 html2canvas 取第一帧
  function extFromType(type, url) {
    var t = (type || "").toLowerCase();
    if (t === "image/gif") return "gif";
    if (t === "image/png") return "png";
    if (t === "image/webp") return "webp";
    if (t === "image/jpeg" || t === "image/jpg") return "jpg";
    if (t === "image/svg+xml") return "svg";
    return imageExtension(url);
  }

  // 图片 token: zhihu 图片 URL 里的 v2-xxx 标识, 动图缩略图与 GIF 原图共用同一 token
  function tokenOf(url) {
    var m = /(v2-[0-9a-f]{20,})/i.exec(String(url || ""));
    return m ? m[1] : "";
  }

  // 知乎运行时 GifPlayer 组件会把动图的 src 换成静态缩略图(_b.jpg),
  // 真 GIF 地址只存在于 SSR 原始 HTML 里 —— 同源抓一次当前页面,
  // 建立 token → 原始 GIF 地址 的映射, 供动图恢复真身
  async function buildSsrGifMap() {
    var map = {};
    try {
      var resp = await fetch(location.href, { credentials: "include" });
      if (!resp.ok) return map;
      var text = await resp.text();
      var re = /<img\b[^>]*\bsrc="([^"]+)"/gi;
      var m;
      while ((m = re.exec(text))) {
        var src = m[1];
        if (/\.gif($|\?)/i.test(src)) {
          var tok = tokenOf(src);
          if (tok && !map[tok]) map[tok] = src;
        }
      }
    } catch (e) { /* 忽略, 拿不到映射就按普通图处理 */ }
    return map;
  }

  function toBase64(bytes) {
    // 分段拼 UTF-16, 避免 apply 参数超限
    var CHUNK = 0x8000;
    var out = "";
    for (var i = 0; i < bytes.length; i += CHUNK) {
      out += String.fromCharCode.apply(null, bytes.subarray(i, Math.min(i + CHUNK, bytes.length)));
    }
    return btoa(out);
  }

  // 每块 200KB 二进制(约 267KB base64 字符串), 单条桥接消息远小于 Binder 1MB 上限
  var UPLOAD_CHUNK = 200 * 1024;

  function uploadImageBytes(name, buf) {
    return new Promise(function (resolve, reject) {
      try {
        var total = Math.max(1, Math.ceil(buf.byteLength / UPLOAD_CHUNK));
        window.AndroidBridge.onImageStart(safeTitleOfExtract, name, total);
        var i = 0;
        (function step() {
          if (i >= total) {
            window.AndroidBridge.onImageEnd(name);
            resolve();
            return;
          }
          var start = i * UPLOAD_CHUNK;
          var len = Math.min(UPLOAD_CHUNK, buf.byteLength - start);
          var sub = new Uint8Array(buf, start, len);
          window.AndroidBridge.onImageChunk(name, i, toBase64(sub));
          i += 1;
          setTimeout(step, 0); // 让出事件循环
        })();
      } catch (e) {
        reject(e);
      }
    });
  }

  // 知乎 GifPlayer 会给动图 img 加 style="display:none"(悬停才显示动画),
  // 导出时必须恢复可见, 否则第一帧/动图在 HTML/PDF 里隐身留空白
  function revealGif(el) {
    try {
      el.style.removeProperty("display");
      if (!el.getAttribute("style")) el.removeAttribute("style");
    } catch (e) { /* ignore */ }
  }

  // 逐张串行处理(低并发, 对知乎更友好, 内存峰值只有一张图):
  // 抓取 → 原样二进制写本地 assets/<safeTitle>/img_xxx.ext。
  // 动图(GIF): 字节原样保存 xxx.gif(留在 zip 里), 同时原生层用 Android 解码器
  // 提取第一帧为 xxx.png; 页面统一引用 png —— 任何查看器/PDF 都必然显示,
  // 不会在动图位置留一大片空白。png 缺失时逐级回退 gif → 原图 URL
  function processImage(el, abs, idxLocal, images) {
    var ctrl = typeof AbortController !== "undefined" ? new AbortController() : null;
    var timer = setTimeout(function () { if (ctrl) ctrl.abort(); }, 15000);
    // 关键: credentials 必须 omit —— zhimg 图床返回 ACAO:*,
    // 按规范 "通配符 + credentials:include" 会被浏览器直接拒绝(所有图片都抓不到)
    return fetch(abs, {
      credentials: "omit",
      signal: ctrl ? ctrl.signal : undefined
    }).then(function (resp) {
      if (!resp.ok) throw new Error("http " + resp.status);
      return resp.blob();
    }).then(function (blob) {
      var ext = extFromType(blob.type, abs);
      var name = "img_" + String(idxLocal).padStart(3, "0") + "." + ext;
      return blob.arrayBuffer().then(function (buf) {
        return uploadImageBytes(name, buf).then(function () {
          return { name: name, ext: ext };
        });
      });
    }).then(function (pack) {
      var shown = pack.name;
      if (pack.ext === "gif") {
        // 动图: 引用原生层转出的第一帧 png; 缺 png 回退动画 gif; 再缺回退原图 URL
        shown = pack.name.replace(/\.gif$/i, ".png");
        revealGif(el);
        el.setAttribute("src", "assets/" + safeTitleOfExtract + "/" + shown);
        el.setAttribute("onerror",
          "if(!this.getAttribute('data-fb1')){this.setAttribute('data-fb1','1');" +
          "this.src='assets/" + safeTitleOfExtract + "/" + pack.name + "';}" +
          "else if(!this.getAttribute('data-fb2')){this.setAttribute('data-fb2','1');" +
          "this.src='" + abs.replace(/'/g, "%27") + "';}else{this.onerror=null;}");
      } else {
        el.setAttribute("src", "assets/" + safeTitleOfExtract + "/" + shown);
        el.setAttribute("onerror",
          "this.onerror=null;this.src='" + abs.replace(/'/g, "%27") + "';");
      }
      return shown;
    }).catch(function () {
      // 页面内抓取失败(极少): 记入清单, 原生层再兜底下载
      var name = "img_" + String(idxLocal).padStart(3, "0") + "." + imageExtension(abs);
      images.push({ url: abs, name: name });
      revealGif(el);
      el.setAttribute("src", "assets/" + safeTitleOfExtract + "/" + name);
      el.setAttribute("onerror",
        "this.onerror=null;this.src='" + abs.replace(/'/g, "%27") + "';");
      return null;
    }).finally(function () { clearTimeout(timer); });
  }

  function fixLatex(code) {
    if (!code) return "";
    code = code.replace(/\\\{/g, "\\{").replace(/\\\}/g, "\\}");
    code = code.replace(/[\u200b\u200c\u200d\ufeff]/g, "");
    code = code.replace(/\^(\s*)$/g, "^{}");
    code = code.replace(/_(\s*)$/g, "_{}");
    code = code.replace(/(\^|_)\s*(\\color\{[^}]+\})\s*(\{[^}]+\}|\\[a-zA-Z]+|[a-zA-Z0-9+\-])/g, "$1{$2$3}");
    code = code.replace(/\\uwave/g, "\\underline").replace(/\\xout/g, "\\cancel");
    return code.trim();
  }

  function escapeHtml(s) {
    return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  function mathToLatex(root) {
    root.querySelectorAll("span.ztext-math").forEach(function (span) {
      var tex = span.getAttribute("data-tex");
      if (!tex) return;
      var latex = fixLatex(tex);
      var out = span.getAttribute("data-block")
        ? "\n$$\n" + latex + "\n$$\n" : " $" + latex + "$ ";
      span.replaceWith(document.createTextNode(out));
    });
    root.querySelectorAll("img.eeimg").forEach(function (img) {
      var tex = img.getAttribute("alt");
      if (!tex) return;
      img.replaceWith(document.createTextNode(" $" + fixLatex(tex) + "$ "));
    });
  }

  var EXPORT_STYLE = [
    "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', sans-serif;",
    "line-height: 1.8; max-width: 900px; margin: 0 auto; padding: 2em; color: #333; }",
    "img { max-width: 100%; height: auto; border-radius: 8px; margin: 16px 0; }",
    "h1, h2, h3 { color: #111; border-bottom: 1px solid #eaecef; padding-bottom: .3em; }",
    "pre { background: #f6f8fa; padding: 16px; border-radius: 8px; overflow-x: auto; }",
    "code { background: rgba(27,31,35,.05); padding: .2em .4em; border-radius: 3px; }",
    "blockquote { border-left: 4px solid #dfe2e5; padding-left: 1em; color: #6a737d; margin-left: 0; }",
    "table { border-collapse: collapse; margin: 12px 0; }",
    "td, th { border: 1px solid #ddd; padding: 6px 10px; }"
  ].join("\n");

  async function extract() {
    var content = pick();
    if (!content) return { ok: false, error: "未找到正文容器" };

    var title = getTitle();
    var author = getAuthor();
    var date = getDate();
    var safeTitle = sanitize(title);
    safeTitleOfExtract = safeTitle;

    var clone = content.cloneNode(true);

    // 清理噪音节点(含视频动图的播放按钮覆盖层与动图空白 SVG 封面, 否则 PDF 里出现黑圈/大片空白)
    var noise = "script, style, noscript, button, .RichContent-actions, .ContentItem-actions, " +
      ".AnswerItem-extraInfo, .VoteButton, [data-draft-type], .CopyrightRichText-tooltip, " +
      ".Post-Author, .ContentItem-rightButton, .RichContent-cover, " +
      ".VideoPlayButton, .PlayButton, .GifPlayer-icon, .GifPlayer-cover, .video-play-button, " +
      "[class*='PlayButton'], [class*='play-button']";
    clone.querySelectorAll(noise).forEach(function (n) { n.remove(); });

    // 知乎部分"动图"实际是 <video>: 取 poster(或 data-poster)封面转成静态图; 无封面的移除。
    // 先处理 video(整个父容器换成封面 img, 连播放按钮等装饰元素一起清掉, 不留黑圈),
    // 让封面图也进入下面统一的图片内嵌流程
    clone.querySelectorAll("video").forEach(function (video) {
      var poster = video.getAttribute("poster") || video.getAttribute("data-poster") || "";
      if (poster && poster.indexOf("data:") !== 0) {
        try { poster = new URL(poster, location.href).href; } catch (e) { poster = ""; }
      }
      if (/^https?:/i.test(poster)) {
        var img = document.createElement("img");
        img.setAttribute("src", poster);
        var parent = video.parentElement;
        if (parent && parent.tagName !== "BODY" &&
            parent.querySelectorAll("video, img").length === 1) {
          parent.replaceWith(img);
        } else {
          video.replaceWith(img);
        }
      } else {
        // 无封面: 连同父容器一起移除, 避免残留空容器在 PDF/HTML 里留一大片空白
        var vp = video.parentElement;
        if (vp && vp.tagName !== "BODY" && vp.querySelectorAll("video, img").length === 1) {
          vp.remove();
        } else {
          video.remove();
        }
      }
    });

    // 图片: 逐张串行 —— 页面内 fetch 抓字节 → 二进制桥写本地 assets/<safeTitle>/img_xxx.ext,
    // MD/HTML 里放相对路径(低并发, 内存峰值只有一张图; 抓取失败才记入 images 清单)
    var ssrGifMap = await buildSsrGifMap();
    var images = [];
    var imageItems = [];
    clone.querySelectorAll("img").forEach(function (img) {
      if (img.classList.contains("eeimg") && img.getAttribute("alt")) return;
      if (img.classList.contains("Avatar")) return;
      var src = imgSrc(img);
      if (!src || src.indexOf("data:") === 0) return;
      var abs;
      try { abs = new URL(src, location.href).href; } catch (e) { return; }
      if (!/^https?:/i.test(abs)) return;
      // 动图: 活体 DOM 里 src 被 GifPlayer 换成静态缩略图 —— 用 SSR 映射恢复真 GIF 地址
      if (!/\.gif($|\?)/i.test(abs)) {
        var tok = tokenOf(img.getAttribute("data-thumbnail")) ||
          tokenOf(img.getAttribute("data-original-token")) || tokenOf(abs);
        if (tok && ssrGifMap[tok]) {
          try { abs = new URL(ssrGifMap[tok], location.href).href; } catch (e) { /* 保留原值 */ }
        }
      }
      imageItems.push({ el: img, abs: abs });
    });
    var savedCount = 0;
    for (var k = 0; k < imageItems.length; k++) {
      var item = imageItems[k];
      var okName = await processImage(item.el, item.abs, k + 1, images);
      if (okName) savedCount += 1;
      // 统一清掉知乎懒加载属性, 防止残留 data-src 干扰
      ["data-actualsrc", "data-original", "data-src", "srcset", "data-caption",
        "data-default-watermark-src", "data-rawheight", "data-rawwidth"]
        .forEach(function (a) { item.el.removeAttribute(a); });
    }

    // ---- Markdown: 公式占位符保护 → Turndown → 还原 ----
    var mdClone = clone.cloneNode(true);
    var mathMap = {};
    var ph = 0;
    mdClone.querySelectorAll("span.ztext-math").forEach(function (span) {
      var tex = span.getAttribute("data-tex");
      if (!tex) return;
      var latex = fixLatex(tex);
      var out = span.getAttribute("data-block")
        ? "\n$$\n" + latex + "\n$$\n" : " $" + latex + "$ ";
      var placeholder = "PLACEHOLDERMATH" + (ph++);
      mathMap[placeholder] = out;
      span.replaceWith(document.createTextNode(placeholder));
    });
    mdClone.querySelectorAll("img.eeimg[alt]").forEach(function (img) {
      var tex = img.getAttribute("alt");
      if (!tex) return;
      var placeholder = "PLACEHOLDERMATH" + (ph++);
      mathMap[placeholder] = " $" + fixLatex(tex) + "$ ";
      img.replaceWith(document.createTextNode(placeholder));
    });

    if (typeof TurndownService === "undefined") {
      return { ok: false, error: "Turndown 未加载" };
    }
    var td = new TurndownService({ headingStyle: "atx", bulletListMarker: "-", codeBlockStyle: "fenced" });
    var mdBody = td.turndown(mdClone);
    Object.keys(mathMap).forEach(function (k) {
      mdBody = mdBody.split(k).join(mathMap[k]);
    });

    var yaml = "---\n";
    yaml += 'title: "' + title.replace(/"/g, "'") + '"\n';
    yaml += 'author: "' + (author || "未知").replace(/"/g, "'") + '"\n';
    yaml += "date: " + (date || "1970-01-01") + "\n";
    yaml += "tags: [知乎备份]\n";
    yaml += 'url: "' + location.href + '"\n';
    yaml += "---\n\n";
    var markdown = yaml + "# " + title + "\n\n" + mdBody;

    // ---- HTML: 公式换 LaTeX + 本地 MathJax(CHTML 输出, 对 html2canvas 更友好) ----
    // 本地 _mathjax/tex-chtml-full.js 优先, CDN 双层兜底
    mathToLatex(clone);
    var mathjaxHead =
      "<script>MathJax = { tex: { inlineMath: [['$','$']], displayMath: [['$$','$$']] } };<\/script>" +
      '<script type="text/javascript" async src="_mathjax/tex-chtml-full.js" ' +
      'onerror="var s=document.createElement(\'script\');' +
      's.src=\'https://registry.npmmirror.com/mathjax/3.2.2/files/es5/tex-chtml-full.js\';' +
      's.onerror=function(){var s2=document.createElement(\'script\');' +
      's2.src=\'https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-chtml-full.js\';' +
      's2.async=true;document.head.appendChild(s2);};' +
      's.async=true;document.head.appendChild(s);"><\/script>';

    var html = "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\">\n<title>"
      + escapeHtml(title) + "</title>\n" + mathjaxHead + "\n<style>" + EXPORT_STYLE
      + "</style>\n</head>\n<body>\n<h1>" + escapeHtml(title) + "</h1>\n"
      + clone.innerHTML + "\n</body>\n</html>";

    return {
      ok: true,
      title: title,
      safeTitle: safeTitle,
      markdown: markdown,
      html: html,
      images: images,
      embedded: savedCount,
      totalImages: imageItems.length
    };
  }

  // 风控验证页检测(新号/批量过猛时会跳验证码页)
  function detectCaptcha() {
    try {
      if (/captcha/i.test(location.href)) return true;
      var t = (document.title || "").toLowerCase();
      if (t.indexOf("安全验证") >= 0 || t.indexOf("验证码") >= 0 || t.indexOf("captcha") >= 0) return true;
      return !!document.querySelector(".Captcha, .captcha, [class*='Captcha'], [class*='captcha']");
    } catch (e) {
      return false;
    }
  }

  // 结果回传: 正常结果较小(图片走二进制桥, 不在 JSON 里),
  // 但为防超长文章仍做分块回传, 避开 Binder 单条 ~1MB 上限
  function sendResult(obj) {
    var json = JSON.stringify(obj);
    var CHUNK = 300000;
    if (json.length <= CHUNK) {
      window.AndroidBridge.onResult(json);
      return;
    }
    var total = Math.ceil(json.length / CHUNK);
    window.AndroidBridge.onResultStart(total);
    for (var i = 0; i < total; i++) {
      window.AndroidBridge.onResultChunk(i, json.substr(i * CHUNK, CHUNK));
    }
    window.AndroidBridge.onResultEnd();
  }

  // 轮询驱动: 知乎是前端渲染, onPageFinished 后正文可能还没出来
  (function driver() {
    var deadline = Date.now() + 25000;
    function attempt() {
      var el = pick();
      if (!el) {
        if (detectCaptcha()) {
          sendResult({
            ok: false,
            error: "风控: 知乎触发了安全验证(新号或批量过猛易触发)。请点「重新登录」打开知乎完成验证后重试, 批量建议减小每批数量。"
          });
          return;
        }
        if (Date.now() < deadline) {
          setTimeout(attempt, 400);
          return;
        }
        sendResult({
          ok: false,
          error: "等待正文渲染超时(25秒): 链接可能无效, 或账号需要重新登录"
        });
        return;
      }
      extract().then(function (result) {
        sendResult(result);
      }).catch(function (e) {
        sendResult({ ok: false, error: String(e) });
      });
    }
    attempt();
  })();
})();
