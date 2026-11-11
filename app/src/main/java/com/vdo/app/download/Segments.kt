package com.vdo.app.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-128 (clear key) segment encryption as defined by the HLS spec. */
data class AesKey(val uri: String, val iv: ByteArray)

/** One cacheable piece of a stream: a whole URL or a byte range of it. */
data class Part(
    val url: String,
    val rangeStart: Long = -1,
    val rangeLength: Long = -1,
    val key: AesKey? = null,
)

/** A single elementary stream (video, audio or muxed) split into parts. */
data class Track(val init: Part?, val parts: List<Part>, val container: String)

/** Thrown when a stream is protected by a DRM system. VDO never attempts to bypass DRM. */
class DrmProtectedException :
    IOException("This content is DRM-protected and can't be downloaded.")

class LiveStreamException :
    IOException("Live streams can't be downloaded until the broadcast has ended.")

class HttpStatusException(val code: Int, url: String) : IOException("Server returned HTTP $code")

class Progress {
    val bytes = AtomicLong()
    val partsDone = AtomicInteger()
    val partsTotal = AtomicInteger()
    val totalBytes = AtomicLong(-1)
}

object Http {
    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun request(url: String, headers: Map<String, String>): Request.Builder {
        val b = Request.Builder().url(url)
        if (headers.keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", UA)
        headers.forEach { (k, v) -> runCatching { b.header(k, v) } }
        return b
    }

    /** Fetches a small text resource such as a playlist or manifest. */
    suspend fun text(client: OkHttpClient, url: String, headers: Map<String, String>): Pair<String, String> =
        withContext(Dispatchers.IO) {
            client.newCall(request(url, headers).build()).execute().use { r ->
                if (!r.isSuccessful) throw HttpStatusException(r.code, url)
                r.request.url.toString() to (r.body?.string() ?: "")
            }
        }
}

/**
 * Downloads every part of a track into a cache directory (in parallel, resumable:
 * parts already on disk are skipped) and then merges them into a single file.
 */
class SegmentedDownloader(
    private val client: OkHttpClient,
    private val headers: Map<String, String>,
    private val threads: Int,
    private val progress: Progress,
) {
    private val keys = ConcurrentHashMap<String, ByteArray>()

    suspend fun download(track: Track, cacheDir: File, out: File) {
        val all = listOfNotNull(track.init) + track.parts
        val files = all.indices.map { File(cacheDir, "%06d.seg".format(it)) }
        val marker = File(out.path + ".pos")
        if (out.exists() && !marker.exists()) {
            // Already fetched and merged in an earlier run.
            progress.bytes.addAndGet(out.length())
            progress.partsDone.addAndGet(all.size)
            return
        }
        if (!marker.exists()) {
            cacheDir.mkdirs()
            val sem = Semaphore(threads.coerceIn(1, 16))
            coroutineScope {
                all.mapIndexed { i, part ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            fetchPart(part, files[i])
                            progress.partsDone.incrementAndGet()
                        }
                    }
                }.awaitAll()
            }
        } else {
            progress.partsDone.addAndGet(all.size)
        }
        merge(files, out, marker)
    }

    /**
     * Appends the cached parts to [out] one by one, deleting each part once it has been
     * copied so the download never needs twice its size on disk. [marker] records how far
     * the merge got ("nextIndex length") so an interrupted merge can pick up where it left off.
     */
    private suspend fun merge(files: List<File>, out: File, marker: File) = withContext(Dispatchers.IO) {
        var next = 0
        var length = 0L
        if (marker.exists()) {
            val saved = runCatching { marker.readText().trim().split(' ') }.getOrNull()
            next = saved?.getOrNull(0)?.toIntOrNull() ?: 0
            length = saved?.getOrNull(1)?.toLongOrNull() ?: 0
        }
        if (next == 0) {
            out.delete()
            length = 0
        }
        marker.writeText("$next $length")
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(length)
            raf.seek(length)
            val buf = ByteArray(1 shl 16)
            for (i in next until files.size) {
                currentCoroutineContext().ensureActive()
                val f = files[i]
                if (!f.exists()) throw IOException("Cached segment ${i + 1} is missing")
                f.inputStream().use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                    }
                }
                length += f.length()
                marker.writeText("${i + 1} $length")
                f.delete()
            }
        }
        marker.delete()
        if (next > 0) progress.bytes.addAndGet(out.length())
    }

    private suspend fun fetchPart(part: Part, dest: File) {
        if (dest.exists()) {
            progress.bytes.addAndGet(dest.length())
            return
        }
        val tmp = File(dest.path + ".part")
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            var written = 0L
            try {
                val cipher = part.key?.let { cipherFor(it) }
                val rb = Http.request(part.url, headers)
                if (part.rangeStart >= 0) {
                    val end = if (part.rangeLength > 0) (part.rangeStart + part.rangeLength - 1).toString() else ""
                    rb.header("Range", "bytes=${part.rangeStart}-$end")
                }
                val call = client.newCall(rb.build())
                val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
                try {
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) throw HttpStatusException(resp.code, part.url)
                        if (part.rangeStart > 0 && resp.code != 206) {
                            throw IOException("Server ignored the byte-range request")
                        }
                        val raw: InputStream = resp.body?.byteStream() ?: throw IOException("Empty response")
                        val ins = if (cipher != null) CipherInputStream(raw, cipher) else raw
                        FileOutputStream(tmp).use { os ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                os.write(buf, 0, n)
                                written += n
                                progress.bytes.addAndGet(n.toLong())
                            }
                        }
                    }
                } finally {
                    handle.dispose()
                }
                if (!tmp.renameTo(dest)) throw IOException("Could not store segment")
                return
            } catch (e: IOException) {
                progress.bytes.addAndGet(-written)
                tmp.delete()
                currentCoroutineContext().ensureActive()
                val fatal = e is HttpStatusException && e.code in 400..499 && e.code != 408 && e.code != 429
                if (fatal || ++attempt >= 5) throw e
                delay(700L * attempt * attempt)
            }
        }
    }

    private fun cipherFor(key: AesKey): Cipher {
        val bytes = keys.getOrPut(key.uri) {
            client.newCall(Http.request(key.uri, headers).build()).execute().use { r ->
                if (!r.isSuccessful) throw HttpStatusException(r.code, key.uri)
                val b = r.body?.bytes() ?: ByteArray(0)
                if (b.size != 16) throw IOException("Unsupported encryption key")
                b
            }
        }
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(bytes, "AES"), IvParameterSpec(key.iv))
        }
    }

    companion object {
        /** Splits a plain file into byte-range parts so it can be fetched in parallel and resumed. */
        fun rangedTrack(url: String, total: Long, chunk: Long = 4L * 1024 * 1024): Track {
            val parts = ArrayList<Part>()
            var pos = 0L
            while (pos < total) {
                val len = minOf(chunk, total - pos)
                parts += Part(url, pos, len)
                pos += len
            }
            return Track(null, parts, "file")
        }
    }
}
