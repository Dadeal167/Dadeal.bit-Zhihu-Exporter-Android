package com.dadealbit.zhihuextractor

import android.content.Context
import android.webkit.CookieManager

/**
 * 模块一配套: 全局 Cookie 的持久化与恢复。
 * 所有 WebView(可见登录页 + 隐藏抓取页)自动共享同一 CookieManager, 无需单独传递。
 */
object CookieStore {

    private const val PREFS = "cookies"
    private const val KEY_ZHIHU = "zhihu_cookies"
    private const val ZHIHU_URL = "https://www.zhihu.com"

    fun cookieString(): String? =
        CookieManager.getInstance().getCookie(ZHIHU_URL)?.takeIf { it.isNotBlank() }

    /** 登录判定: 出现 z_c0 即为已登录 */
    fun hasLogin(): Boolean = cookieString()?.contains("z_c0=") == true

    /** 登录成功后持久化(进程被杀后仍可恢复会话) */
    fun persist(context: Context) {
        cookieString()?.let { cookies ->
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ZHIHU, cookies).apply()
            CookieManager.getInstance().flush()
        }
    }

    /** 启动时恢复会话 Cookie */
    fun restore(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ZHIHU, null) ?: return
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        saved.split(";").forEach { part ->
            val kv = part.trim()
            if (kv.isNotEmpty()) {
                manager.setCookie(ZHIHU_URL, kv)
            }
        }
        manager.flush()
    }

    fun clear(context: Context) {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ZHIHU).apply()
    }
}
