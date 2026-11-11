package com.vdo.app.download

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class HlsVariant(
    val url: String,
    val bandwidth: Long,
    val width: Int,
    val height: Int,
    val audioGroup: String?,
    val codecs: String?,
)

data class HlsRendition(
    val type: String,
    val groupId: String,
    val name: String,
    val uri: String?,
    val isDefault: Boolean,
    val language: String?,
)

data class HlsMaster(val variants: List<HlsVariant>, val renditions: List<HlsRendition>) {
    /** Best variant not taller than [maxHeight] (0 = no limit). */
    fun pick(maxHeight: Int): HlsVariant? {
        val sorted = variants.sortedWith(compareBy({ it.height }, { it.bandwidth }))
        if (maxHeight <= 0) return sorted.lastOrNull()
        return sorted.lastOrNull { it.height in 1..maxHeight } ?: sorted.firstOrNull()
    }

    /** Separate audio playlist for a variant, if its audio is not muxed into the video segments. */
    fun audioFor(v: HlsVariant): HlsRendition? {
        val group = v.audioGroup ?: return null
        val list = renditions.filter { it.type == "AUDIO" && it.groupId == group && it.uri != null }
        return list.firstOrNull { it.isDefault } ?: list.firstOrNull()
    }
}

data class HlsMedia(
    val track: Track,
    val endList: Boolean,
    val drm: Boolean,
    val durationSec: Double,
)

object HlsParser {
    fun isPlaylist(text: String) = text.trimStart('﻿', ' ', '\n', '\r').startsWith("#EXTM3U")

    fun isMaster(text: String) = text.contains("#EXT-X-STREAM-INF")

    fun resolve(base: String, rel: String): String =
        base.toHttpUrlOrNull()?.resolve(rel.trim())?.toString() ?: rel.trim()

    /** Parses `KEY=value,KEY="quoted, value"` attribute lists. */
    fun attrs(line: String): Map<String, String> {
        val s = line.substringAfter(':', "")
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < s.length) {
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val key = s.substring(i, eq).trim().trimStart(',').trim()
            var j = eq + 1
            val value: String
            if (j < s.length && s[j] == '"') {
                val end = s.indexOf('"', j + 1).let { if (it < 0) s.length else it }
                value = s.substring(j + 1, end)
                j = end + 1
            } else {
                val end = s.indexOf(',', j).let { if (it < 0) s.length else it }
                value = s.substring(j, end).trim()
                j = end
            }
            out[key] = value
            i = j + 1
        }
        return out
    }

    fun parseMaster(text: String, base: String): HlsMaster {
        val variants = ArrayList<HlsVariant>()
        val renditions = ArrayList<HlsRendition>()
        var pending: Map<String, String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> pending = attrs(line)
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = attrs(line)
                    renditions += HlsRendition(
                        type = a["TYPE"] ?: "",
                        groupId = a["GROUP-ID"] ?: "",
                        name = a["NAME"] ?: "",
                        uri = a["URI"]?.let { resolve(base, it) },
                        isDefault = a["DEFAULT"] == "YES",
                        language = a["LANGUAGE"],
                    )
                }
                line.isNotEmpty() && !line.startsWith("#") && pending != null -> {
                    val a = pending
                    val res = a["RESOLUTION"]?.lowercase()?.split('x')
                    variants += HlsVariant(
                        url = resolve(base, line),
                        bandwidth = a["BANDWIDTH"]?.toLongOrNull() ?: 0,
                        width = res?.getOrNull(0)?.toIntOrNull() ?: 0,
                        height = res?.getOrNull(1)?.toIntOrNull() ?: 0,
                        audioGroup = a["AUDIO"],
                        codecs = a["CODECS"],
                    )
                    pending = null
                }
            }
        }
        return HlsMaster(variants.distinctBy { it.url }, renditions)
    }

    fun parseMedia(text: String, base: String): HlsMedia {
        val parts = ArrayList<Part>()
        var init: Part? = null
        var seq = 0L
        var key: Pair<String, ByteArray?>? = null // uri to explicit IV
        var drm = false
        var endList = false
        var duration = 0.0
        var range: Pair<Long, Long>? = null // start, length
        var lastRangeEnd = 0L
        var lastRangeUrl = ""

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    seq = line.substringAfter(':').trim().toLongOrNull() ?: 0
                line.startsWith("#EXTINF:") ->
                    duration += line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-ENDLIST") -> endList = true
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = attrs(line)
                    val method = a["METHOD"] ?: "NONE"
                    val format = a["KEYFORMAT"] ?: "identity"
                    when {
                        method == "NONE" -> key = null
                        method == "AES-128" && format == "identity" && a["URI"] != null ->
                            key = resolve(base, a["URI"]!!) to a["IV"]?.let(::hexIv)
                        else -> drm = true // SAMPLE-AES, Widevine, FairPlay, PlayReady...
                    }
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val a = attrs(line)
                    val uri = a["URI"]?.let { resolve(base, it) } ?: continue
                    val br = a["BYTERANGE"]?.let { parseRange(it, 0) }
                    init = Part(uri, br?.first ?: -1, br?.second ?: -1)
                }
                line.startsWith("#EXT-X-BYTERANGE:") ->
                    range = parseRange(line.substringAfter(':'), -1)
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val url = resolve(base, line)
                    var start = -1L
                    var len = -1L
                    range?.let { (s, l) ->
                        start = if (s >= 0) s else if (url == lastRangeUrl) lastRangeEnd else 0
                        len = l
                        lastRangeEnd = start + len
                        lastRangeUrl = url
                    }
                    val k = key?.let { (uri, iv) -> AesKey(uri, iv ?: seqIv(seq)) }
                    parts += Part(url, start, len, k)
                    range = null
                    seq++
                }
            }
        }
        val first = parts.firstOrNull()?.url?.substringBefore('?')?.lowercase() ?: ""
        val container = when {
            init != null || first.endsWith(".m4s") || first.endsWith(".mp4") || first.endsWith(".m4a") -> "mp4"
            first.endsWith(".aac") -> "aac"
            first.endsWith(".mp3") -> "mp3"
            else -> "ts"
        }
        return HlsMedia(Track(init, parts, container), endList, drm, duration)
    }

    private fun parseRange(s: String, defaultStart: Long): Pair<Long, Long> {
        val t = s.trim().trim('"')
        val len = t.substringBefore('@').toLongOrNull() ?: 0
        val start = if (t.contains('@')) t.substringAfter('@').toLongOrNull() ?: 0 else defaultStart
        return start to len
    }

    private fun hexIv(s: String): ByteArray {
        val hex = s.removePrefix("0x").removePrefix("0X").padStart(32, '0').takeLast(32)
        return ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun seqIv(seq: Long): ByteArray {
        val iv = ByteArray(16)
        for (i in 0 until 8) iv[15 - i] = (seq ushr (8 * i)).toByte()
        return iv
    }
}
