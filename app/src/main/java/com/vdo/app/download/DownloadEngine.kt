package com.vdo.app.download

import android.content.Context
import com.vdo.app.data.DlKind
import com.vdo.app.data.DlStatus
import com.vdo.app.data.DownloadTask
import com.vdo.app.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException

/**
 * Runs one download from start to finish:
 *  1. work out what the URL is (file / HLS / DASH),
 *  2. fetch every piece into a per-download cache folder (parallel + resumable),
 *  3. merge the cached pieces, remux to a normal MP4/M4A/WebM where needed,
 *  4. export the result to the gallery or the private folder.
 */
class DownloadEngine(
    private val ctx: Context,
    private val initial: DownloadTask,
    private val update: (String, (DownloadTask) -> DownloadTask) -> Unit,
) {
    private val id = initial.id
    private val headers = initial.headers
    private val progress = Progress()
    private val work = workDir(ctx, id)

    suspend fun run() = coroutineScope {
        work.mkdirs()
        val ticker = launch {
            var last = 0L
            var smooth = 0.0
            while (isActive) {
                delay(700)
                val now = progress.bytes.get()
                val inst = ((now - last).coerceAtLeast(0)) / 0.7
                smooth = if (smooth == 0.0) inst else smooth * 0.6 + inst * 0.4
                last = now
                val done = progress.partsDone.get()
                val all = progress.partsTotal.get()
                val known = progress.totalBytes.get()
                update(id) {
                    if (it.status != DlStatus.RUNNING) it else it.copy(
                        downloaded = now,
                        total = when {
                            known > 0 -> known
                            done >= 3 && all > 0 -> now / done * all // estimate from finished segments
                            else -> -1
                        },
                        partsDone = done, partsTotal = all, speed = smooth.toLong(),
                    )
                }
            }
        }
        try {
            val (file, mime, isAudio) = when (initial.kind) {
                DlKind.HLS -> hls()
                DlKind.DASH -> dash()
                DlKind.DIRECT -> direct()
            }
            ticker.cancel()
            val size = file.length()
            val output = Exporter.export(ctx, file, initial.title, mime, initial.isPrivate)
            work.deleteRecursively()
            update(id) {
                it.copy(
                    status = DlStatus.COMPLETED, output = output, mime = mime, isAudio = isAudio,
                    downloaded = size, total = size, speed = 0, error = null,
                )
            }
        } finally {
            ticker.cancel()
        }
    }

    private fun downloader() = SegmentedDownloader(Http.client, headers, Store.threads, progress)

    private fun merging() = update(id) { it.copy(status = DlStatus.MERGING, speed = 0) }

    private data class Result(val file: File, val mime: String, val isAudio: Boolean)

    // ---------------------------------------------------------------- direct
    private suspend fun direct(): Result {
        val probe = Probe.probe(initial.url, headers)
        if (probe.kind == DlKind.HLS || probe.kind == DlKind.DASH) {
            // The link turned out to be a stream after all.
            return if (probe.kind == DlKind.HLS) hls() else dash()
        }
        if (probe.isHtml) throw IOException("This link is a web page, not a media file. Open it in the browser tab.")
        val ext = extensionFor(probe)
        val out = File(work, "merged.$ext")
        val track = if (probe.acceptsRanges) {
            progress.totalBytes.set(probe.size)
            SegmentedDownloader.rangedTrack(probe.url, probe.size)
        } else {
            if (probe.size > 0) progress.totalBytes.set(probe.size)
            Track(null, listOf(Part(probe.url)), "file")
        }
        progress.partsTotal.set(track.parts.size)
        downloader().download(track, File(work, "v"), out)
        val mime = probe.mime?.takeIf { it.contains('/') && it != "application/octet-stream" }
            ?: Exporter.mimeFor(ext, probe.isAudio)
        return Result(out, mime, mime.startsWith("audio"))
    }

    private fun extensionFor(p: ProbeResult): String {
        val fromName = p.fileName?.substringAfterLast('.', "")
        val fromUrl = p.url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
        val known = Regex("^[a-zA-Z0-9]{2,5}$")
        return listOfNotNull(fromName, fromUrl).firstOrNull { known.matches(it) }?.lowercase()
            ?: when {
                p.mime == null -> "mp4"
                p.mime.contains("webm") -> "webm"
                p.mime.contains("mpeg") && p.mime.startsWith("audio") -> "mp3"
                p.mime.startsWith("audio") -> "m4a"
                p.mime.contains("matroska") -> "mkv"
                else -> "mp4"
            }
    }

    // ------------------------------------------------------------------- HLS
    private suspend fun hls(): Result {
        var (url, text) = Http.text(Http.client, initial.url, headers)
        var audioUrl: String? = null
        if (HlsParser.isMaster(text)) {
            val master = HlsParser.parseMaster(text, url)
            val variant = master.pick(initial.maxHeight) ?: throw IOException("No playable stream found")
            audioUrl = master.audioFor(variant)?.uri
            val fetched = Http.text(Http.client, variant.url, headers)
            url = fetched.first; text = fetched.second
        }
        val video = HlsParser.parseMedia(text, url)
        check(video)
        val audio = audioUrl?.let { a ->
            val (u, t) = Http.text(Http.client, a, headers)
            HlsParser.parseMedia(t, u).also(::check)
        }
        return fetchAndMux(video.track, audio?.track)
    }

    private fun check(m: HlsMedia) {
        if (m.drm) throw DrmProtectedException()
        if (!m.endList) throw LiveStreamException()
        if (m.track.parts.isEmpty()) throw IOException("The stream has no segments")
    }

    // ------------------------------------------------------------------ DASH
    private suspend fun dash(): Result {
        val (url, text) = Http.text(Http.client, initial.url, headers)
        val m = DashParser.parse(text, url)
        if (m.drm) throw DrmProtectedException()
        if (m.live) throw LiveStreamException()
        val video = m.pickVideo(initial.maxHeight)
        val audio = m.pickAudio(video)
        val main = video ?: audio ?: throw IOException("No playable stream found")
        return fetchAndMux(main.track, if (video != null) audio?.track else null)
    }

    // ------------------------------------------------------- shared pipeline
    private suspend fun fetchAndMux(main: Track, extraAudio: Track?): Result {
        progress.partsTotal.set(main.parts.size + (extraAudio?.parts?.size ?: 0) +
            listOfNotNull(main.init, extraAudio?.init).size)
        val dl = downloader()
        val merged = ArrayList<File>()
        val v = File(work, "video." + main.container)
        dl.download(main, File(work, "v"), v)
        merged += v
        if (extraAudio != null) {
            val a = File(work, "audio." + extraAudio.container)
            dl.download(extraAudio, File(work, "a"), a)
            merged += a
        }
        merging()
        val remuxed = runInterruptible(Dispatchers.IO) { Remuxer.remux(merged, File(work, "final")) }
        val file = remuxed ?: run {
            if (merged.size > 1) {
                throw IOException("This device couldn't combine the separate audio and video tracks.")
            }
            v // keep the merged stream as it is; VDO and most players can still play it
        }
        if (remuxed != null) merged.forEach { it.delete() }
        val ext = file.extension
        val isAudio = ext in setOf("m4a", "aac", "mp3")
        return Result(file, Exporter.mimeFor(ext, isAudio), isAudio)
    }

    companion object {
        fun workDir(ctx: Context, id: String) =
            File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, ".cache/$id")
    }
}
