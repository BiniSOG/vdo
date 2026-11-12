package com.vdo.app

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.vdo.app.ui.Ads
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic: loads the same simple page in a plain WebView, then with each of the browser's
 * extras added, to tell an environment problem from a configuration problem. Always passes;
 * read the VDO-Isolation log lines.
 */
@RunWith(AndroidJUnit4::class)
class WebViewIsolationTest {
    private val page = "http://10.0.2.2:8765/page-meta.html"

    class Bridge { @JavascriptInterface fun found(u: String, k: String) = Unit }

    @SuppressLint("SetJavaScriptEnabled")
    private fun attempt(sc: ActivityScenario<MainActivity>, name: String, setup: (WebView) -> Unit) {
        var result = "timeout"
        var view: WebView? = null
        sc.onActivity { act ->
            val w = WebView(act)
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, url: String) { if (result == "timeout") result = "loaded" }
                override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean {
                    result = "renderer crashed"; return true
                }
            }
            setup(w)
            act.addContentView(w, FrameLayout.LayoutParams(400, 400))
            w.loadUrl(page)
            view = w
        }
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (result == "timeout" && SystemClock.elapsedRealtime() < deadline) Thread.sleep(500)
        Log.i("VDO-Isolation", "$name: $result")
        sc.onActivity { view?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); if (result != "renderer crashed") it.destroy() } }
        Thread.sleep(3000)
    }

    @Test
    @SuppressLint("SetJavaScriptEnabled")
    fun isolate() {
        Ads.disabled = true
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            attempt(sc, "1 plain WebView") { }
            attempt(sc, "2 + JavaScript") { it.settings.javaScriptEnabled = true }
            attempt(sc, "3 + JS bridge") {
                it.settings.javaScriptEnabled = true
                it.addJavascriptInterface(Bridge(), "VDO")
            }
            attempt(sc, "4 + document-start script") {
                it.settings.javaScriptEnabled = true
                it.addJavascriptInterface(Bridge(), "VDO")
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(it, "window.__x = 1;", setOf("*"))
                }
            }
            attempt(sc, "5 + DOM storage, zoom, wide viewport") {
                it.settings.javaScriptEnabled = true
                it.settings.domStorageEnabled = true
                it.settings.useWideViewPort = true
                it.settings.loadWithOverviewMode = true
                it.settings.builtInZoomControls = true
                it.settings.displayZoomControls = false
            }
        }
    }
}
