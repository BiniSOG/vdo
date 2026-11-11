package com.vdo.app.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.vdo.app.AppBus
import com.vdo.app.data.PlayItem
import com.vdo.app.data.Store
import com.vdo.app.data.WebEntry
import com.vdo.app.download.Probe
import com.vdo.app.player.PlayerHub
import com.vdo.app.ui.DownloadSheet
import com.vdo.app.ui.VdoColors
import com.vdo.app.ui.normalizeUrl
import java.io.ByteArrayInputStream
import java.net.URLEncoder

/** A media resource spotted while a page was loading. */
data class Detected(
    val url: String,
    val type: String,
    val headers: Map<String, String>,
    val pageTitle: String,
)

private val MEDIA = Regex("""\.(m3u8|mpd|mp4|m4v|webm|mkv|mov|mp3|m4a|aac|ogg|opus|wav|flac)(?:[?#]|$)""", RegexOption.IGNORE_CASE)

/** Services whose terms forbid downloading; VDO plays them in the page but offers no download. */
private val UNSUPPORTED = listOf("youtube.com", "youtu.be", "googlevideo.com", "youtube-nocookie.com", "netflix.com", "spotify.com")

private val AD_HOSTS = listOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.", "adnxs.com",
    "taboola.com", "outbrain.com", "popads.net", "popcash.net", "propellerads.com", "adsterra.com",
    "exoclick.com", "juicyads.com", "moatads.com", "scorecardresearch.com", "zedo.com", "amazon-adsystem.com",
    "criteo.com", "criteo.net", "pubmatic.com", "rubiconproject.com", "openx.net", "smartadserver.com",
    "hotjar.com", "mgid.com", "revcontent.com", "adform.net", "onclickads.net", "trafficjunky.net",
)

private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

// Reports <video>/<audio> sources that never show up as a recognisable network request.
private const val SNIFF_JS = """
(function(){
  if (window.__vdo) return; window.__vdo = true;
  var seen = {};
  function scan(){
    try {
      var els = document.querySelectorAll('video, audio, video source, audio source');
      for (var i = 0; i < els.length; i++) {
        var u = els[i].currentSrc || els[i].src;
        if (u && u.indexOf('http') === 0 && !seen[u]) { seen[u] = 1; VDO.found(u, els[i].tagName); }
      }
    } catch (e) {}
  }
  scan(); setInterval(scan, 2000);
})();
"""

/**
 * Runs before the page's own scripts. Many sites hide their stream behind a script-driven
 * player with no visible link and an address that doesn't end in .m3u8/.mpd. This watches what
 * the page's player itself loads and recognises stream manifests by their content, so VDO can
 * fetch the same segments the player would have cached. It also notices when a page asks for a
 * DRM licence, so the user is told up front that the stream is encrypted.
 */
private const val HOOK_JS = """
(function(){
  if (window.__vdoHook) return; window.__vdoHook = true;
  var sent = {};
  function abs(u){ try { return new URL(u, location.href).href; } catch (e) { return null; } }
  function report(u, kind){
    u = abs(u);
    if (!u || u.indexOf('http') !== 0 || sent[u]) return;
    sent[u] = 1;
    try { VDO.found(u, kind); } catch (e) {}
  }
  function kindOf(type, head){
    type = (type || '').toLowerCase();
    if (type.indexOf('mpegurl') >= 0) return 'M3U8';
    if (type.indexOf('dash+xml') >= 0) return 'MPD';
    if (head) {
      var h = head.replace(/^\uFEFF/, '').trimStart();
      if (h.indexOf('#EXTM3U') === 0) return 'M3U8';
      if (h.indexOf('<MPD') >= 0 && h.indexOf('<MPD') < 600) return 'MPD';
    }
    return null;
  }
  function texty(type){
    type = (type || '').toLowerCase();
    return !type || type.indexOf('text/') === 0 || type.indexOf('xml') >= 0 || type.indexOf('json') >= 0 ||
      type.indexOf('octet-stream') >= 0 || type.indexOf('mpegurl') >= 0;
  }
  var realFetch = window.fetch;
  if (realFetch) {
    window.fetch = function(input, init){
      var p = realFetch.apply(this, arguments);
      try {
        p.then(function(res){
          try {
            var type = res.headers.get('content-type');
            var k = kindOf(type, null);
            if (k) { report(res.url, k); return; }
            var len = parseInt(res.headers.get('content-length') || '0', 10);
            if (!texty(type) || len > 1500000) return;
            res.clone().text().then(function(t){
              var k2 = kindOf(type, t.slice(0, 800));
              if (k2) report(res.url, k2);
            }).catch(function(){});
          } catch (e) {}
        }).catch(function(){});
      } catch (e) {}
      return p;
    };
  }
  var open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url){
    var xhr = this;
    try {
      xhr.addEventListener('load', function(){
        try {
          var type = xhr.getResponseHeader('content-type');
          var head = null;
          if (xhr.responseType === '' || xhr.responseType === 'text') head = (xhr.responseText || '').slice(0, 800);
          var k = kindOf(type, head);
          if (k) report(xhr.responseURL || url, k);
        } catch (e) {}
      });
    } catch (e) {}
    return open.apply(this, arguments);
  };
  try {
    var rmksa = navigator.requestMediaKeySystemAccess;
    if (rmksa) {
      navigator.requestMediaKeySystemAccess = function(){
        try { VDO.protectedContent(); } catch (e) {}
        return rmksa.apply(this, arguments);
      };
    }
  } catch (e) {}
})();
"""

/** Owns the WebView so the page survives switching between tabs. */
@SuppressLint("SetJavaScriptEnabled")
class BrowserHolder(private val activity: Activity) {
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var fullscreen by mutableStateOf<View?>(null)
    var blockedNotice by mutableStateOf(false)
    var drmNotice by mutableStateOf(false)
    val detected = mutableStateListOf<Detected>()
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var created = false
    private var earlyHook = false
    private val mobileUa: String by lazy { WebSettings.getDefaultUserAgent(activity) }

    val webView: WebView by lazy {
        created = true
        WebView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                allowFileAccess = false
                allowContentAccess = false
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(Bridge(), "VDO")
            earlyHook = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            if (earlyHook) WebViewCompat.addDocumentStartJavaScript(this, HOOK_JS, setOf("*"))
            webViewClient = Client()
            webChromeClient = Chrome()
            setDownloadListener { link, _, disposition, _, _ ->
                val name = Probe.fileName(disposition)?.substringBeforeLast('.') ?: this@BrowserHolder.title
                offer(link, "FILE", name)
            }
            applyDesktopMode(this)
        }
    }

    private fun applyDesktopMode(view: WebView) {
        view.settings.userAgentString = if (Store.desktopMode) DESKTOP_UA else mobileUa
    }

    fun setDesktop(on: Boolean) {
        Store.desktopMode = on
        applyDesktopMode(webView)
        webView.reload()
    }

    fun load(input: String) {
        val target = normalizeUrl(input) ?: (Store.searchEngine + URLEncoder.encode(input.trim(), "UTF-8"))
        webView.loadUrl(target)
    }

    fun goBack(): Boolean {
        if (fullscreen != null) { exitFullscreen(); return true }
        if (!created || !webView.canGoBack()) return false
        webView.goBack()
        return true
    }

    fun exitFullscreen() {
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        fullscreen = null
    }

    fun destroy() {
        if (created) runCatching { (webView.parent as? ViewGroup)?.removeView(webView); webView.destroy() }
    }

    private fun unsupported(link: String): Boolean {
        val host = runCatching { java.net.URI(link).host }.getOrNull() ?: link
        return UNSUPPORTED.any { host == it || host.endsWith(".$it") }
    }

    /**
     * Some players fetch one MP4 in slices by adding byte offsets to the address
     * (…&bytestart=0&byteend=65535). Dropping those gives the address of the whole file.
     */
    private fun wholeFile(link: String): String {
        if (!link.contains("bytestart=") && !link.contains("byteend=")) return link
        val base = link.substringBefore('?')
        val kept = link.substringAfter('?', "").substringBefore('#').split('&')
            .filter { it.isNotEmpty() && !it.startsWith("bytestart=") && !it.startsWith("byteend=") }
        return if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
    }

    /** Called from any thread when something that looks like media is seen. */
    fun offer(rawLink: String, type: String, name: String? = null, extraHeaders: Map<String, String> = emptyMap()) {
        val link = wholeFile(rawLink)
        activity.runOnUiThread {
            val page = url
            if (unsupported(link) || unsupported(page)) { blockedNotice = true; return@runOnUiThread }
            // A player usually asks for the same file many times (one request per chunk, each
            // with a fresh query string), so one entry per file is enough.
            val key = link.substringBefore('?')
            if (detected.any { it.url.substringBefore('?') == key }) return@runOnUiThread
            val headers = LinkedHashMap<String, String>()
            extraHeaders.forEach { (k, v) ->
                if (!k.equals("Range", true) && !k.equals("Accept-Encoding", true)) headers[k] = v
            }
            if (page.startsWith("http") && headers.keys.none { it.equals("Referer", true) }) headers["Referer"] = page
            if (headers.keys.none { it.equals("User-Agent", true) }) headers["User-Agent"] = webView.settings.userAgentString
            CookieManager.getInstance().getCookie(link)?.let { headers["Cookie"] = it }
            detected.add(0, Detected(link, type, headers, name ?: title.ifBlank { Probe.titleFromUrl(link) }))
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun found(link: String, tag: String) {
            val ext = MEDIA.find(link)?.groupValues?.get(1)?.uppercase()
            val type = when {
                tag == "M3U8" || tag == "MPD" -> tag // recognised by content, whatever the address looks like
                ext != null -> ext
                tag.equals("AUDIO", true) -> "AUDIO"
                else -> "VIDEO"
            }
            offer(link, type)
        }

        @JavascriptInterface
        fun protectedContent() {
            activity.runOnUiThread { drmNotice = true }
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme ?: return true
            // Never leave the app for intent://, market:// and similar redirects.
            return scheme != "http" && scheme != "https"
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val link = request.url.toString()
            val host = request.url.host.orEmpty()
            if (Store.adBlock && !request.isForMainFrame && AD_HOSTS.any { host.contains(it) }) {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            MEDIA.find(link.substringBefore('#'))?.let { m ->
                offer(link, m.groupValues[1].uppercase(), null, request.requestHeaders ?: emptyMap())
            }
            return null
        }

        override fun onPageStarted(view: WebView, link: String, favicon: Bitmap?) {
            url = link
            detected.clear()
            blockedNotice = false
            drmNotice = false
            // Older WebViews can't inject at document start; this is the earliest fallback.
            if (!earlyHook) view.evaluateJavascript(HOOK_JS, null)
        }

        override fun onPageFinished(view: WebView, link: String) {
            url = link
            title = view.title.orEmpty()
            canGoBack = view.canGoBack()
            canGoForward = view.canGoForward()
            view.evaluateJavascript(SNIFF_JS, null)
            Store.addHistory(link, title)
        }

        override fun doUpdateVisitedHistory(view: WebView, link: String, isReload: Boolean) {
            url = link
            canGoBack = view.canGoBack()
            canGoForward = view.canGoForward()
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress
            if (newProgress > 30) view.evaluateJavascript(SNIFF_JS, null)
        }

        override fun onReceivedTitle(view: WebView, t: String?) { title = t.orEmpty() }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            fullscreenCallback = callback
            fullscreen = view
        }

        override fun onHideCustomView() { fullscreen = null; fullscreenCallback = null }
    }
}

private enum class Panel { NONE, MEDIA, BOOKMARKS, HISTORY }

@Composable
fun BrowserScreen(holder: BrowserHolder) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    var address by remember { mutableStateOf(holder.url) }
    var editing by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var menu by remember { mutableStateOf(false) }
    var download by remember { mutableStateOf<Detected?>(null) }
    val pendingUrl by AppBus.browserUrl.collectAsState()
    val bookmarks by Store.bookmarks.collectAsState()
    val history by Store.history.collectAsState()

    LaunchedEffect(pendingUrl) {
        val p = pendingUrl
        if (p != null) { holder.load(p); AppBus.browserUrl.value = null }
        else if (holder.url.isEmpty()) holder.load(Store.homePage)
    }
    LaunchedEffect(holder.url) { if (!editing) address = holder.url }

    holder.fullscreen?.let { view ->
        BackHandler { holder.exitFullscreen() }
        AndroidView(
            factory = { c ->
                FrameLayout(c).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    (view.parent as? ViewGroup)?.removeView(view)
                    addView(view, FrameLayout.LayoutParams(-1, -1))
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Address bar
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF0D1236)).padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(21.dp)).background(VdoColors.SurfaceHigh)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = address,
                        onValueChange = { address = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                        cursorBrush = SolidColor(VdoColors.RoseGold),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            if (address.isNotBlank()) holder.load(address)
                            focus.clearFocus()
                        }),
                        modifier = Modifier.weight(1f).onFocusChanged {
                            editing = it.isFocused
                            if (!it.isFocused) address = holder.url
                        },
                        decorationBox = { inner ->
                            if (address.isEmpty()) Text("Search or type a web address", color = VdoColors.Muted, fontSize = 14.sp)
                            inner()
                        },
                    )
                    if (editing && address.isNotEmpty()) {
                        Icon(Icons.Rounded.Close, "Clear", tint = VdoColors.Muted, modifier = Modifier.size(18.dp).clickable { address = "" })
                    } else if (!editing) {
                        Icon(Icons.Rounded.Refresh, "Reload", tint = VdoColors.Muted, modifier = Modifier.size(18.dp).clickable { holder.webView.reload() })
                    }
                }
                val marked = bookmarks.any { it.url == holder.url }
                IconButton(onClick = { if (holder.url.isNotBlank()) Store.toggleBookmark(holder.url, holder.title) }) {
                    Icon(
                        if (marked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "Bookmark",
                        tint = if (marked) VdoColors.RoseGold else Color.White,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Menu", tint = Color.White) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Bookmarks") }, onClick = { menu = false; panel = Panel.BOOKMARKS })
                        DropdownMenuItem(text = { Text("History") }, onClick = { menu = false; panel = Panel.HISTORY })
                        DropdownMenuItem(
                            text = { Text(if (Store.desktopMode) "Mobile site" else "Desktop site") },
                            onClick = { menu = false; holder.setDesktop(!Store.desktopMode) },
                        )
                        DropdownMenuItem(text = { Text("Share page") }, onClick = {
                            menu = false
                            ctx.startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, holder.url), "Share",
                            ))
                        })
                    }
                }
            }
            if (holder.progress < 100) {
                LinearProgressIndicator(
                    progress = { holder.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = VdoColors.RoseGold, trackColor = Color.Transparent,
                )
            }

            AndroidView(
                factory = {
                    holder.webView.also { (it.parent as? ViewGroup)?.removeView(it) }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            // Navigation row
            Row(Modifier.fillMaxWidth().background(Color(0xFF0D1236)), verticalAlignment = Alignment.CenterVertically) {
                NavButton(Modifier.weight(1f), holder.canGoBack, { holder.webView.goBack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                }
                NavButton(Modifier.weight(1f), holder.canGoForward, { holder.webView.goForward() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Forward")
                }
                NavButton(Modifier.weight(1f), true, { holder.load(Store.homePage) }) { Icon(Icons.Rounded.Home, "Home page") }
                NavButton(Modifier.weight(1f), true, { panel = Panel.HISTORY }) { Icon(Icons.Rounded.History, "History") }
                NavButton(Modifier.weight(1f), holder.detected.isNotEmpty(), { panel = Panel.MEDIA }) {
                    BadgedBox(badge = { if (holder.detected.isNotEmpty()) Badge { Text("${holder.detected.size}") } }) {
                        Icon(Icons.Rounded.Download, "Detected media")
                    }
                }
            }
        }

        if (holder.detected.isNotEmpty() && panel == Panel.NONE) {
            ExtendedFloatingActionButton(
                onClick = { panel = Panel.MEDIA },
                containerColor = VdoColors.Violet, contentColor = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 64.dp),
                icon = { Icon(Icons.Rounded.Download, null) },
                text = { Text("${holder.detected.size} found") },
            )
        } else if (holder.blockedNotice || holder.drmNotice) {
            Text(
                if (holder.blockedNotice) "Downloads aren't available on this site"
                else "This video is DRM-encrypted and can't be saved",
                color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp).clip(RoundedCornerShape(50))
                    .background(Color(0xE61F2760)).clickable { holder.blockedNotice = false; holder.drmNotice = false }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }

    if (panel != Panel.NONE) {
        ModalBottomSheet(onDismissRequest = { panel = Panel.NONE }, containerColor = VdoColors.Surface) {
            when (panel) {
                Panel.MEDIA -> {
                    SheetHeader("Media on this page")
                    LazyColumn(Modifier.navigationBarsPadding()) {
                        items(holder.detected.toList(), key = { it.url }) { d ->
                            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    d.type, color = VdoColors.RoseGold, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(VdoColors.SurfaceHigh)
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(d.pageTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(d.url, color = VdoColors.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = {
                                    panel = Panel.NONE
                                    val audio = d.type in setOf("MP3", "M4A", "AAC", "OGG", "OPUS", "WAV", "FLAC", "AUDIO")
                                    PlayerHub.play(ctx, listOf(PlayItem(d.url, d.pageTitle, "Web stream", audio)), 0, d.headers)
                                }) { Icon(Icons.Rounded.PlayArrow, "Play") }
                                IconButton(onClick = { panel = Panel.NONE; download = d }) {
                                    Icon(Icons.Rounded.Download, "Download", tint = VdoColors.RoseGold)
                                }
                            }
                        }
                    }
                }
                Panel.BOOKMARKS -> EntryList("Bookmarks", bookmarks, "Tap the bookmark icon to save a page.", null) {
                    holder.load(it); panel = Panel.NONE
                }
                Panel.HISTORY -> EntryList("History", history, "Pages you visit will appear here.", { Store.clearHistory() }) {
                    holder.load(it); panel = Panel.NONE
                }
                Panel.NONE -> {}
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    download?.let { d -> DownloadSheet(d.url, d.headers, d.pageTitle) { download = null } }
}

@Composable
private fun NavButton(modifier: Modifier, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
}

@Composable
private fun SheetHeader(title: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
private fun EntryList(title: String, entries: List<WebEntry>, empty: String, onClear: (() -> Unit)?, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    SheetHeader(title) {
        if (onClear != null && entries.isNotEmpty()) {
            TextButton(onClick = { onClear(); Toast.makeText(ctx, "Cleared", Toast.LENGTH_SHORT).show() }) { Text("Clear") }
        }
    }
    if (entries.isEmpty()) {
        Text(empty, color = VdoColors.Muted, modifier = Modifier.padding(24.dp))
        return
    }
    LazyColumn(Modifier.navigationBarsPadding()) {
        items(entries) { e ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(e.url) }.padding(horizontal = 24.dp, vertical = 10.dp)) {
                Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e.url, color = VdoColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
