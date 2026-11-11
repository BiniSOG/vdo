package com.vdo.app.download

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil

data class DashRep(
    val id: String,
    val bandwidth: Long,
    val width: Int,
    val height: Int,
    val mime: String,
    val codecs: String,
    val isAudio: Boolean,
    val language: String?,
    val track: Track,
)

data class DashManifest(
    val reps: List<DashRep>,
    val drm: Boolean,
    val live: Boolean,
    val durationSec: Double,
) {
    private fun mp4First(r: DashRep) = if (r.mime.contains("mp4")) 1 else 0

    fun pickVideo(maxHeight: Int): DashRep? {
        val v = reps.filter { !it.isAudio }
            .sortedWith(compareBy({ it.height }, { mp4First(it) }, { it.bandwidth }))
        if (maxHeight <= 0) return v.lastOrNull()
        return v.lastOrNull { it.height in 1..maxHeight } ?: v.firstOrNull()
    }

    fun pickAudio(video: DashRep?): DashRep? {
        val wantMp4 = video?.mime?.contains("mp4") ?: true
        return reps.filter { it.isAudio }
            .sortedWith(compareBy({ if (it.mime.contains("mp4") == wantMp4) 1 else 0 }, { it.bandwidth }))
            .lastOrNull()
    }
}

/** A deliberately small MPEG-DASH (static/VOD) manifest parser. */
object DashParser {
    fun parse(xml: String, manifestUrl: String): DashManifest {
        val f = DocumentBuilderFactory.newInstance()
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.isExpandEntityReferences = false }
        val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
        val mpd = doc.documentElement
        val live = mpd.getAttribute("type") == "dynamic"
        val total = parseDuration(mpd.getAttribute("mediaPresentationDuration"))
        var drm = false
        val reps = ArrayList<DashRep>()
        val mpdBase = baseUrl(mpd, manifestUrl)

        val periods = mpd.children("Period")
        // Multi-period manifests are rare for VOD; we take the first period.
        val period = periods.firstOrNull() ?: return DashManifest(emptyList(), false, live, total)
        val periodDur = parseDuration(period.getAttribute("duration")).takeIf { it > 0 } ?: total
        val periodBase = baseUrl(period, mpdBase)

        for (set in period.children("AdaptationSet")) {
            if (set.children("ContentProtection").isNotEmpty()) drm = true
            val setBase = baseUrl(set, periodBase)
            for (rep in set.children("Representation")) {
                if (rep.children("ContentProtection").isNotEmpty()) drm = true
                fun attr(name: String) = rep.getAttribute(name).ifEmpty { set.getAttribute(name) }
                val mime = attr("mimeType")
                val contentType = set.getAttribute("contentType")
                val isAudio = mime.startsWith("audio") || contentType == "audio"
                val isVideo = mime.startsWith("video") || contentType == "video"
                if (!isAudio && !isVideo) continue // subtitles, thumbnails...
                val id = rep.getAttribute("id")
                val bandwidth = rep.getAttribute("bandwidth").toLongOrNull() ?: 0
                val repBase = baseUrl(rep, setBase)
                val chain = listOf(rep, set, period)
                val track = buildTrack(chain, repBase, id, bandwidth, periodDur, mime) ?: continue
                reps += DashRep(
                    id = id,
                    bandwidth = bandwidth,
                    width = attr("width").toIntOrNull() ?: 0,
                    height = attr("height").toIntOrNull() ?: 0,
                    mime = mime,
                    codecs = attr("codecs"),
                    isAudio = isAudio,
                    language = set.getAttribute("lang").ifEmpty { null },
                    track = track,
                )
            }
        }
        return DashManifest(reps, drm, live, total)
    }

    private fun buildTrack(
        chain: List<Element>, base: String, id: String, bandwidth: Long, durationSec: Double, mime: String,
    ): Track? {
        val container = if (mime.contains("webm")) "webm" else "mp4"
        val templates = chain.mapNotNull { it.children("SegmentTemplate").firstOrNull() }
        if (templates.isNotEmpty()) {
            fun attr(name: String) = templates.firstNotNullOfOrNull { t -> t.getAttribute(name).ifEmpty { null } }
            val media = attr("media") ?: return null
            val initTpl = attr("initialization")
            val timescale = attr("timescale")?.toLongOrNull() ?: 1
            val startNumber = attr("startNumber")?.toLongOrNull() ?: 1
            val init = initTpl?.let { Part(HlsParser.resolve(base, fill(it, id, bandwidth, 0, 0))) }
            val parts = ArrayList<Part>()
            val timeline = templates.firstNotNullOfOrNull { it.children("SegmentTimeline").firstOrNull() }
            if (timeline != null) {
                var time = 0L
                var number = startNumber
                for (s in timeline.children("S")) {
                    s.getAttribute("t").toLongOrNull()?.let { time = it }
                    val d = s.getAttribute("d").toLongOrNull() ?: continue
                    val repeat = (s.getAttribute("r").toIntOrNull() ?: 0).coerceAtLeast(0)
                    repeat(repeat + 1) {
                        parts += Part(HlsParser.resolve(base, fill(media, id, bandwidth, number, time)))
                        time += d
                        number++
                    }
                }
            } else {
                val d = attr("duration")?.toLongOrNull() ?: return null
                if (durationSec <= 0) return null
                val count = ceil(durationSec * timescale / d - 1e-6).toLong().coerceAtLeast(1)
                for (n in 0 until count) {
                    parts += Part(HlsParser.resolve(base, fill(media, id, bandwidth, startNumber + n, n * d)))
                }
            }
            return Track(init, parts, container)
        }
        val list = chain.firstNotNullOfOrNull { it.children("SegmentList").firstOrNull() }
        if (list != null) {
            val init = list.children("Initialization").firstOrNull()?.let { partOf(it, "sourceURL", "range", base) }
            val parts = list.children("SegmentURL").map { partOf(it, "media", "mediaRange", base) }
            return Track(init, parts, container)
        }
        // SegmentBase or bare BaseURL: the representation is a single file.
        return Track(null, listOf(Part(base)), container)
    }

    private fun partOf(e: Element, urlAttr: String, rangeAttr: String, base: String): Part {
        val url = e.getAttribute(urlAttr).let { if (it.isEmpty()) base else HlsParser.resolve(base, it) }
        val range = e.getAttribute(rangeAttr)
        if (range.contains('-')) {
            val a = range.substringBefore('-').toLongOrNull() ?: 0
            val b = range.substringAfter('-').toLongOrNull() ?: a
            return Part(url, a, b - a + 1)
        }
        return Part(url)
    }

    private val token = Regex("""\$(\w*)(%0(\d+)d)?\$""")

    fun fill(tpl: String, id: String, bandwidth: Long, number: Long, time: Long): String =
        token.replace(tpl) { m ->
            val width = m.groupValues[3].toIntOrNull() ?: 0
            fun num(v: Long) = v.toString().padStart(width, '0')
            when (m.groupValues[1]) {
                "" -> "$"
                "RepresentationID" -> id
                "Bandwidth" -> num(bandwidth)
                "Number" -> num(number)
                "Time" -> num(time)
                else -> m.value
            }
        }

    private fun baseUrl(e: Element, parent: String): String {
        val b = e.children("BaseURL").firstOrNull()?.textContent?.trim()
        return if (b.isNullOrEmpty()) parent else HlsParser.resolve(parent, b)
    }

    private fun Element.children(name: String): List<Element> {
        val out = ArrayList<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && (n.localName ?: n.tagName).substringAfter(':') == name) out += n
        }
        return out
    }

    private val iso = Regex("""P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?)?""")

    /** ISO-8601 duration such as PT1H2M3.5S, in seconds. */
    fun parseDuration(s: String): Double {
        val m = iso.matchEntire(s.trim()) ?: return 0.0
        fun g(i: Int) = m.groupValues[i].toDoubleOrNull() ?: 0.0
        return g(1) * 86400 + g(2) * 3600 + g(3) * 60 + g(4)
    }
}
