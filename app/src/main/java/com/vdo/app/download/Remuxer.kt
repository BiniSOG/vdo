package com.vdo.app.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Rewraps merged stream data (MPEG-TS, fragmented MP4, WebM...) into one regular,
 * seekable file, muxing a separate audio track into the video when there is one.
 * No re-encoding takes place, so this is fast and lossless.
 */
object Remuxer {
    private class Source(val extractor: MediaExtractor, val map: HashMap<Int, Int>)

    /** Returns the produced file (extension chosen from the codecs) or null if the device can't remux it. */
    fun remux(inputs: List<File>, outBase: File): File? {
        val extractors = ArrayList<MediaExtractor>()
        var muxer: MediaMuxer? = null
        var out: File? = null
        try {
            var haveVideo = false
            var haveAudio = false
            val chosen = ArrayList<Triple<MediaExtractor, Int, MediaFormat>>()
            for (f in inputs) {
                val ex = MediaExtractor().apply { setDataSource(f.path) }
                extractors += ex
                for (i in 0 until ex.trackCount) {
                    val fmt = ex.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") && !haveVideo) {
                        haveVideo = true; chosen += Triple(ex, i, fmt)
                    } else if (mime.startsWith("audio/") && !haveAudio) {
                        haveAudio = true; chosen += Triple(ex, i, fmt)
                    }
                }
            }
            if (chosen.isEmpty()) return null
            val mimes = chosen.map { it.third.getString(MediaFormat.KEY_MIME) ?: "" }
            val webm = mimes.any { it.contains("vp8") || it.contains("vp9") || it.contains("vorbis") || it.contains("opus") }
            val ext = when {
                webm -> "webm"
                !haveVideo -> "m4a"
                else -> "mp4"
            }
            out = File(outBase.path + "." + ext)
            out.delete()
            muxer = MediaMuxer(
                out.path,
                if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            val sources = LinkedHashMap<MediaExtractor, Source>()
            var bufSize = 2 * 1024 * 1024
            for ((ex, idx, fmt) in chosen) {
                ex.selectTrack(idx)
                if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    bufSize = maxOf(bufSize, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                if (fmt.containsKey(MediaFormat.KEY_ROTATION)) {
                    muxer.setOrientationHint(fmt.getInteger(MediaFormat.KEY_ROTATION))
                }
                sources.getOrPut(ex) { Source(ex, HashMap()) }.map[idx] = muxer.addTrack(fmt)
            }
            muxer.start()

            // Streams rarely start at zero; shift everything so the file starts at 0.
            val active = sources.values.toMutableList()
            val offset = active.map { it.extractor.sampleTime }.filter { it >= 0 }.minOrNull() ?: 0L
            val buffer = ByteBuffer.allocate(bufSize)
            val info = MediaCodec.BufferInfo()
            var wrote = 0L
            while (active.isNotEmpty()) {
                if (Thread.interrupted()) throw InterruptedException()
                val src = active.minByOrNull { it.extractor.sampleTime }!!
                val ex = src.extractor
                val size = ex.readSampleData(buffer, 0)
                if (size < 0) { active.remove(src); continue }
                val track = src.map[ex.sampleTrackIndex]
                if (track != null) {
                    info.set(
                        0, size, (ex.sampleTime - offset).coerceAtLeast(0),
                        if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                    )
                    muxer.writeSampleData(track, buffer, info)
                    wrote++
                }
                if (!ex.advance()) active.remove(src)
            }
            if (wrote == 0L) throw IllegalStateException("no samples")
            muxer.stop()
            muxer.release()
            muxer = null
            return out
        } catch (e: InterruptedException) {
            out?.delete()
            throw e
        } catch (e: Exception) {
            Log.w("VDO", "Remux failed, keeping original container", e)
            runCatching { muxer?.release() }
            out?.delete()
            return null
        } finally {
            extractors.forEach { runCatching { it.release() } }
        }
    }
}
