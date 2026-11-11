package com.vdo.app.download

import com.vdo.app.data.DlKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.URLDecoder

data class Variant(val label: String, val height: Int, val url: String)

data class ProbeResult(
    val url: String,
    val kind: DlKind?,
    val isHtml: Boolean,
    val mime: String?,
    val size: Long,
    val acceptsRanges: Boolean,
    val fileName: String?,
    val variants: List<Variant> = emptyList(),
    val drm: Boolean = false,
    val live: Boolean = false,
    val body: String? = null,
) {
    val isAudio: Boolean
        get() = mime?.startsWith("audio") == true ||
            Regex("""\.(mp3|m4a|aac|ogg|opus|wav|flac)$""").containsMatchIn(url.substringBefore('?').lowercase())
}

/** Looks at a URL to work out whether it is a file, an HLS/DASH stream or a web page. */
object Probe {
    suspend fun probe(
        url: String,
        headers: Map<String, String>,
        client: OkHttpClient = Http.client,
    ): ProbeResult = withContext(Dispatchers.IO) {
        val req = Http.request(url, headers).header("Range", "bytes=0-").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpStatusException(r.code, url)
            val finalUrl = r.request.url.toString()
            val mime = r.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            val path = finalUrl.substringBefore('?').lowercase()
            val ranged = r.code == 206
            val size = r.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
                ?: r.body?.contentLength()?.takeIf { it > 0 && !ranged } ?: -1L
            val name = fileName(r.header("Content-Disposition"))

            val looksText = mime == null || mime.startsWith("text/") || mime.contains("mpegurl") ||
                mime.contains("xml") || mime == "application/octet-stream" && (path.endsWith(".m3u8") || path.endsWith(".mpd"))
            val hintedStream = path.endsWith(".m3u8") || path.endsWith(".mpd") ||
                mime?.contains("mpegurl") == true || mime == "application/dash+xml"
            val body = if (looksText || hintedStream) {
                r.peekBody(4L * 1024 * 1024).string()
            } else null

            when {
                body != null && HlsParser.isPlaylist(body) -> {
                    if (HlsParser.isMaster(body)) {
                        val master = HlsParser.parseMaster(body, finalUrl)
                        val variants = master.variants
                            .sortedByDescending { it.height * 100000L + it.bandwidth / 1000 }
                            .map {
                                val label = if (it.height > 0) "${it.height}p" else "${it.bandwidth / 1000} kbps"
                                Variant(label, it.height, it.url)
                            }
                            .distinctBy { it.label }
                        ProbeResult(finalUrl, DlKind.HLS, false, mime, -1, false, name, variants, body = body)
                    } else {
                        val media = HlsParser.parseMedia(body, finalUrl)
                        ProbeResult(
                            finalUrl, DlKind.HLS, false, mime, -1, false, name,
                            drm = media.drm, live = !media.endList, body = body,
                        )
                    }
                }
                body != null && body.contains("<MPD") -> {
                    val m = DashParser.parse(body, finalUrl)
                    val variants = m.reps.filter { !it.isAudio && it.height > 0 }
                        .map { it.height }.distinct().sortedDescending()
                        .map { Variant("${it}p", it, finalUrl) }
                    ProbeResult(
                        finalUrl, DlKind.DASH, false, mime, -1, false, name, variants,
                        drm = m.drm, live = m.live, body = body,
                    )
                }
                mime == "text/html" || mime == "application/xhtml+xml" ->
                    ProbeResult(finalUrl, null, true, mime, -1, false, name)
                else -> ProbeResult(finalUrl, DlKind.DIRECT, false, mime, size, ranged && size > 0, name)
            }
        }
    }

    fun fileName(disposition: String?): String? {
        if (disposition == null) return null
        Regex("""filename\*\s*=\s*[\w-]*''([^;]+)""", RegexOption.IGNORE_CASE).find(disposition)?.let {
            return runCatching { URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") }.getOrNull()
        }
        return Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.get(1)?.trim()
    }

    /** A human-friendly title guessed from a URL. */
    fun titleFromUrl(url: String): String {
        val seg = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(seg, "UTF-8") }.getOrDefault(seg)
        val base = decoded.substringBeforeLast('.', decoded)
        val generic = setOf("index", "master", "playlist", "manifest", "video", "chunklist", "")
        return if (base.lowercase() in generic) {
            url.substringAfter("://").substringBefore('/').removePrefix("www.") + " video"
        } else base
    }
}
