package com.vdo.app

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vdo.app.browser.BrowserHolder
import com.vdo.app.browser.Detected
import com.vdo.app.data.DlKind
import com.vdo.app.data.DlStatus
import com.vdo.app.download.Downloads
import com.vdo.app.download.Exporter
import com.vdo.app.download.Probe
import com.vdo.app.ui.Ads
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real in-app browser against local test pages that hide their video the way
 * real sites do, then downloads what it found through the same steps as the download sheet.
 * Needs the test media server on the host (see README).
 */
@RunWith(AndroidJUnit4::class)
class BrowserTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val host = "http://10.0.2.2:8765"

    private fun detected(sc: ActivityScenario<MainActivity>, holder: BrowserHolder): List<Detected> {
        var list = emptyList<Detected>()
        sc.onActivity { list = holder.detected.toList() }
        return list
    }

    @Test
    fun browserFindsVideosAndDownloadsThem() {
        Ads.disabled = true
        val cases = listOf(
            "typed link to a video file" to ("$host/sample-clip.mp4" to "sample-clip.mp4"),
            "page with a plain video element" to ("$host/page-video.html" to "/clip"),
            "script player (blob video, HLS stream)" to ("$host/page-hls.html" to "index.m3u8"),
            "share page with only og:video" to ("$host/page-meta.html" to "sample-clip.mp4"),
            "video fetched by script, no file extension" to ("$host/page-fetch.html" to "/clip"),
        )
        val report = StringBuilder()
        val found = LinkedHashMap<String, Detected>()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            var holder: BrowserHolder? = null
            sc.onActivity { holder = it.browser; AppBus.tab.value = Tab.BROWSER }
            inst.waitForIdleSync()
            for ((name, case) in cases) {
                val (page, expect) = case
                sc.onActivity { holder!!.load(page) }
                val deadline = SystemClock.elapsedRealtime() + 90_000
                var hit: Detected? = null
                while (SystemClock.elapsedRealtime() < deadline) {
                    hit = detected(sc, holder!!).firstOrNull { it.url.contains(expect) }
                    if (hit != null) break
                    Thread.sleep(1000)
                }
                if (hit != null) found[name] = hit
                report.append("\n").append(if (hit != null) "FOUND   " else "MISSING ").append(name)
                    .append(" -> ").append(detected(sc, holder!!).map { "${it.type} ${it.url}" })
            }
        }

        // Download what was found, exactly as the download sheet does: inspect, then queue.
        for ((name, d) in found) {
            if (name.startsWith("share page") || name.startsWith("page with")) continue // same files as others
            val p = runCatching { runBlocking { Probe.probe(d.url, d.headers) } }.getOrNull()
            if (p == null || p.isHtml) { report.append("\nPROBE FAILED ").append(name); continue }
            val task = Downloads.enqueue(p.url, "browser test $name", p.kind ?: DlKind.DIRECT, d.headers, isAudio = p.isAudio)
            val deadline = SystemClock.elapsedRealtime() + 180_000
            var t = task
            while (SystemClock.elapsedRealtime() < deadline) {
                t = Downloads.tasks.value.first { it.id == task.id }
                if (t.status == DlStatus.COMPLETED || t.status == DlStatus.FAILED) break
                Thread.sleep(1000)
            }
            report.append("\n").append(if (t.status == DlStatus.COMPLETED) "SAVED   " else "NOT SAVED ")
                .append(name).append(" (").append(t.status).append(t.error?.let { ": $it" } ?: "").append(")")
            Downloads.remove(t.id, deleteFile = true)
            Exporter.delete(InstrumentationRegistry.getInstrumentation().targetContext, t.output)
        }
        android.util.Log.i("VDO-BrowserTest", report.toString())
        assertTrue(report.toString(), !report.contains("MISSING") && !report.contains("NOT SAVED") && !report.contains("PROBE FAILED"))
    }
}
