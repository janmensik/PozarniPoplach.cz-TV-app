package com.example.tvkiosk

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var fullscreenContainer: FrameLayout

    /** Holds the custom view shown during HTML5 fullscreen (e.g. video). */
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    /** The URL loaded by this kiosk — defined in res/values/strings.xml. */
    private val kioskUrl: String by lazy { getString(R.string.kiosk_url) }

    /** Delay (ms) before retrying after a network/load error. */
    private val retryDelayMs: Long = 5_000L

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Keep the screen on permanently — no sleep / screensaver
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 2. True fullscreen: let the app draw edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemUi()

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        fullscreenContainer = findViewById(R.id.fullscreen_container)

        setupWebView()

        // Restore URL after rotation/config change; otherwise load fresh
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(kioskUrl)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        // Re-hide system UI in case the user triggered it transiently
        hideSystemUi()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // System UI / Fullscreen helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun hideSystemUi() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        // Allow transient show on swipe (user can still access TV home)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /** Re-apply immersive mode when the window regains focus. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // D-pad / remote key handling
    // ──────────────────────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Back button on TV remote: navigate within WebView history first
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) {
                // Exit HTML5 fullscreen video if active
                webChromeClientInstance.onHideCustomView()
                return true
            }
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
            // Let the system handle it → returns user to TV launcher
        }
        return super.onKeyDown(keyCode, event)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // WebView configuration
    // ──────────────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            // JavaScript & storage (all required by the kiosk page)
            javaScriptEnabled = true
            domStorageEnabled = true

            // Media autoplay without user gesture
            mediaPlaybackRequiresUserGesture = false

            // Allow mixed HTTP/HTTPS content (some pages load HTTP sub-resources)
            @Suppress("DEPRECATION")
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // Viewport / zoom — disable zoom controls (TV has no pinch)
            loadWithOverviewMode = true
            useWideViewPort      = true
            setSupportZoom(false)
            builtInZoomControls  = false
            displayZoomControls  = false

            // Remove "Mobile" from the UA so the site serves its desktop/TV layout
            userAgentString = userAgentString.replace("Mobile", "")

            // Cache: use cache when available, fall back to network
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        // Accept cookies (including third-party, for tracking/session continuity)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = kioskWebViewClient
        webView.webChromeClient = webChromeClientInstance
    }

    // ──────────────────────────────────────────────────────────────────────────
    // WebViewClient — navigation & error recovery
    // ──────────────────────────────────────────────────────────────────────────

    private val kioskWebViewClient = object : WebViewClient() {

        /** Keep all navigation inside the WebView (no browser launches). */
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean = false

        /**
         * On a main-frame load error (network down, server unreachable, etc.)
         * show a blank page and retry after [retryDelayMs].
         */
        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame) {
                view.loadUrl("about:blank")
                view.postDelayed({
                    view.loadUrl(kioskUrl)
                }, retryDelayMs)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // WebChromeClient — HTML5 fullscreen video support
    // ──────────────────────────────────────────────────────────────────────────

    private val webChromeClientInstance = object : WebChromeClient() {

        /**
         * Called when a page element (usually a <video>) requests fullscreen.
         * We move it into [fullscreenContainer] which covers the entire window.
         */
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                // Dismiss any previous custom view before showing a new one
                onHideCustomView()
            }
            customView = view
            customViewCallback = callback

            fullscreenContainer.addView(view)
            fullscreenContainer.visibility = View.VISIBLE
            webView.visibility = View.INVISIBLE
        }

        /** Called when the page exits fullscreen. Restore the normal WebView. */
        override fun onHideCustomView() {
            fullscreenContainer.visibility = View.GONE
            fullscreenContainer.removeAllViews()
            webView.visibility = View.VISIBLE

            customViewCallback?.onCustomViewHidden()
            customView = null
            customViewCallback = null
        }
    }
}
