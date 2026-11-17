package com.scropper.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

enum class Site(val key: String, val homeUrl: String, private val domains: List<String>, val originRules: Set<String>) {
    YOUTUBE(
        "youtube",
        "https://m.youtube.com",
        listOf("youtube.com", "youtu.be"),
        setOf("https://*.youtube.com"),
    ),
    INSTAGRAM(
        "instagram",
        "https://www.instagram.com",
        listOf("instagram.com", "instagr.am"),
        setOf("https://*.instagram.com"),
    );

    fun owns(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return domains.any { h == it || h.endsWith(".$it") }
    }

    companion object {
        fun forHost(host: String?): Site? = entries.firstOrNull { it.owns(host) }
        fun forUrl(url: String?): Site? = url?.let { forHost(Uri.parse(it).host) }
    }
}

/** What a [ScropperWebController] needs from its hosting activity. */
interface WebHost {
    /** A link belonging to the other site was tapped: load it in that site's WebView and switch tab. */
    fun routeToSite(site: Site, url: String)
    fun openExternal(uri: Uri)
    fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback)
    fun hideCustomView()
    fun openFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
}

/**
 * WebView that can pretend to stay visible. With [keepAlive] set, "hidden" signals never reach
 * Chromium, so the page keeps `document.visibilityState == "visible"` and media keeps playing
 * while the tab or the whole app is in the background.
 */
class KeepAliveWebView(context: Context) : WebView(context) {
    var keepAlive = false

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (keepAlive && visibility != View.VISIBLE) return
        super.onWindowVisibilityChanged(visibility)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        if (keepAlive && visibility != View.VISIBLE) return
        super.onVisibilityChanged(changedView, visibility)
    }
}

/** Owns one persistent WebView (plus its pull-to-refresh wrapper) for one [Site]. */
@SuppressLint("SetJavaScriptEnabled")
class ScropperWebController(
    activity: Activity,
    val site: Site,
    private val settings: ShieldSettings,
    private val host: WebHost,
) {
    val webView = KeepAliveWebView(activity)
    val root = SwipeRefreshLayout(activity)

    @Volatile
    private var innerScrolled = false
    private var docStartHandler: ScriptHandler? = null
    private var adsHandler: ScriptHandler? = null
    private var paused = false
    private var started = false

    /** URL of the page currently showing, for ad-filter context. Read from WebView IO threads. */
    @Volatile
    private var pageUrl: String = site.homeUrl

    init {
        configure(webView)
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
        webView.addJavascriptInterface(Bridge(), "ScropperNative")

        root.addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.setColorSchemeColors(Color.WHITE)
        root.setProgressBackgroundColorSchemeColor(0xFF1C1C1F.toInt())
        // Pull-to-refresh only when the page (and any inner scroller under the finger) is at the top.
        root.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 || innerScrolled }
        root.setOnRefreshListener { webView.reload() }

        applyConfig(settings.config)
    }

    /** Loads the home page unless something (e.g. a shared link) was loaded already. */
    fun start() {
        if (started) return
        started = true
        webView.loadUrl(site.homeUrl)
    }

    private fun configure(view: WebView) {
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        view.setBackgroundColor(Color.BLACK)
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false

        val s = view.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        @Suppress("DEPRECATION")
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(false)
        s.setSupportMultipleWindows(false)
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.userAgentString = chromeUserAgent(view.context)

        // Google sign-in rejects embedded WebViews: besides the UA, drop the X-Requested-With header.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        }

        // Dark mode: API 33+ follows the (dark) app theme via prefers-color-scheme. Older: opt in explicitly.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(s, WebSettingsCompat.FORCE_DARK_ON)
            }
            @Suppress("DEPRECATION")
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                WebSettingsCompat.setForceDarkStrategy(
                    s,
                    WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING,
                )
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
    }

    // --- shield -----------------------------------------------------------------------------

    private fun script(): String {
        val c = settings.config
        return RustCore.getInjectionScript(site.key, c.hideYtShorts, c.hideIgReels, c.hideIgExplore, c.blockAds)
    }

    private fun adsActive() = settings.config.blockAds && AdBlock.ready

    private fun inject(view: WebView, url: String?) {
        if (Site.forUrl(url) != site) return
        view.evaluateJavascript(script(), null)
    }

    /** Re-arm everything that depends on the toggles. Call on the main thread whenever they change. */
    fun applyConfig(config: ShieldConfig) {
        webView.keepAlive = site == Site.YOUTUBE && config.backgroundAudio
        if (webView.keepAlive && paused) {
            webView.onResume()
            paused = false
        }
        // Earliest possible hook: runs before any page script on every matching document.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            docStartHandler?.remove()
            docStartHandler = WebViewCompat.addDocumentStartJavaScript(webView, script(), site.originRules)
            // uBlock Origin scriptlets must run before the page's own scripts to strip video ads.
            adsHandler?.remove()
            adsHandler = null
            if (adsActive()) {
                val ads = AdBlock.cosmeticScript(site.homeUrl)
                if (ads.isNotEmpty()) {
                    adsHandler = WebViewCompat.addDocumentStartJavaScript(webView, ads, site.originRules)
                }
            }
        }
        // Live page: same script reconfigures (or tears down) the already-installed observer.
        inject(webView, webView.url)
    }

    // --- lifecycle --------------------------------------------------------------------------

    /** `false` when its tab is not showing or the activity is paused. No-op while kept alive. */
    fun setForeground(foreground: Boolean) {
        if (foreground) {
            if (paused) {
                webView.onResume()
                paused = false
            }
        } else if (!webView.keepAlive && !paused) {
            webView.onPause()
            paused = true
        }
    }

    fun load(url: String) {
        started = true
        val target = if (settings.config.hideYtShorts) RustCore.rewriteNavigationUrl(url) ?: url else url
        webView.loadUrl(target)
    }

    fun goHome() = webView.loadUrl(site.homeUrl)

    fun destroy() {
        docStartHandler?.remove()
        adsHandler?.remove()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    // --- clients ----------------------------------------------------------------------------

    private inner class Bridge {
        @JavascriptInterface
        fun setInnerScrolled(value: Boolean) {
            innerScrolled = value
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                // intent://, vnd.youtube:, instagram://, market:// ... never leave for a native app.
                return scheme !in INTERNAL_SCHEMES
            }
            if (!request.isForMainFrame) return false

            val url = uri.toString()
            if (settings.config.hideYtShorts) {
                RustCore.rewriteNavigationUrl(url)?.let { rewritten ->
                    if (site == Site.YOUTUBE) view.loadUrl(rewritten) else host.routeToSite(Site.YOUTUBE, rewritten)
                    return true
                }
            }
            val target = Site.forHost(uri.host)
            return when {
                target == site -> false
                target != null -> {
                    host.routeToSite(target, url)
                    true
                }
                isAuthHost(uri.host) -> false
                request.hasGesture() -> {
                    host.openExternal(uri)
                    true
                }
                else -> false
            }
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.isForMainFrame) return null
            val c = settings.config
            val uri = request.url
            val url = uri.toString()
            if (c.hideYtShorts || c.hideIgReels || c.hideIgExplore) {
                val block = RustCore.shouldBlockUrl(url, uri.host.orEmpty(), c.hideYtShorts, c.hideIgReels, c.hideIgExplore)
                if (block) return emptyResponse()
            }
            // Ad filters only on the site's own pages: sign-in pages of other hosts stay untouched.
            val page = pageUrl
            if (c.blockAds && AdBlock.ready && Site.forUrl(page) == site) {
                if (RustCore.adblockShouldBlock(url, page, requestType(request), request.method ?: "GET")) {
                    return emptyResponse()
                }
            }
            return null
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (url != null) pageUrl = url
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            innerScrolled = false
            if (url != null) pageUrl = url
            // Without document-start support this is the earliest point to add the ad scriptlets.
            if (adsHandler == null && adsActive() && Site.forUrl(url) == site) {
                AdBlock.cosmeticScript(url!!).takeIf { it.isNotEmpty() }?.let { view.evaluateJavascript(it, null) }
            }
            inject(view, url)
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            inject(view, url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            inject(view, url)
            root.isRefreshing = false
            CookieManager.getInstance().flush()
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) = host.showCustomView(view, callback)

        override fun onHideCustomView() = host.hideCustomView()

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean = host.openFileChooser(filePathCallback, fileChooserParams)

        // Replaces the grey "play" placeholder Chromium shows before a video's first frame.
        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        val INTERNAL_SCHEMES = setOf("about", "blob", "data", "javascript")

        /** Sign-in providers that must stay inside the WebView for login to complete. */
        val AUTH_DOMAINS = listOf("google.com", "gstatic.com", "googleusercontent.com", "facebook.com", "fbcdn.net")

        fun isAuthHost(host: String?): Boolean {
            val h = host?.lowercase() ?: return false
            return h.startsWith("accounts.google.") || AUTH_DOMAINS.any { h == it || h.endsWith(".$it") }
        }

        /** Stock Chrome-on-Android UA: the WebView default minus the `; wv` and `Version/4.0` markers. */
        fun chromeUserAgent(context: Context): String =
            WebSettings.getDefaultUserAgent(context)
                .replace("; wv", "")
                .replace(Regex("""\s*Version/\d+(\.\d+)*"""), "")

        private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif")
        private val MEDIA_EXT = setOf("mp4", "webm", "m4a", "m4s", "mp3", "ts", "m3u8", "mpd")
        private val FONT_EXT = setOf("woff", "woff2", "ttf", "otf")

        /** WebView doesn't expose the resource type, so infer it for `$script`/`$image`/`$xhr` filters. */
        fun requestType(request: WebResourceRequest): String {
            val headers = request.requestHeaders
            when (headers?.get("Sec-Fetch-Dest") ?: headers?.get("sec-fetch-dest")) {
                "script" -> return "script"
                "image" -> return "image"
                "style" -> return "stylesheet"
                "iframe", "frame" -> return "sub_frame"
                "video", "audio" -> return "media"
                "font" -> return "font"
                "empty" -> return "xmlhttprequest"
            }
            val ext = request.url.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
            val accept = (headers?.get("Accept") ?: headers?.get("accept")).orEmpty()
            return when {
                ext == "js" || ext == "mjs" -> "script"
                ext == "css" || accept.startsWith("text/css") -> "stylesheet"
                ext in IMAGE_EXT || accept.startsWith("image/") -> "image"
                ext in MEDIA_EXT -> "media"
                ext in FONT_EXT -> "font"
                accept.startsWith("text/html") -> "sub_frame"
                else -> "xmlhttprequest"
            }
        }

        fun emptyResponse() = WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "No Content",
            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0)),
        )
    }
}

/**
 * IndexedStack-style container: every controller's WebView stays attached and alive; only the
 * [selected] one is visible. Switching is a visibility flip, so no reload and no lost state.
 */
@Composable
fun ScropperWebStack(
    controllers: List<ScropperWebController>,
    selected: Site?,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        controllers.forEach { controller ->
            key(controller.site) {
                AndroidView(
                    factory = { controller.root },
                    modifier = Modifier.fillMaxSize(),
                    update = { it.visibility = if (controller.site == selected) View.VISIBLE else View.GONE },
                )
            }
        }
    }
}
