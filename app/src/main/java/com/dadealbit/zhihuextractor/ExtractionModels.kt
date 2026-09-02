package com.dadealbit.zhihuextractor

/** 图片下载清单项 */
data class ImageRef(val url: String, val name: String)

/** 隐藏 WebView 提取结果(与 assets/js/extract.js 回传的 JSON 对应) */
data class ExtractionResult(
    val ok: Boolean,
    val error: String? = null,
    val title: String = "",
    val safeTitle: String = "",
    val markdown: String = "",
    val html: String = "",
    val images: List<ImageRef> = emptyList(),
    /** 已内嵌为 data URI 的图片数(base64 直接放进文件, 无需下载) */
    val embedded: Int = 0,
    /** 文章图片总数 */
    val totalImages: Int = 0
)
