package com.vdo.app

import com.vdo.app.data.DlKind
import com.vdo.app.download.DashParser
import com.vdo.app.download.HlsParser
import com.vdo.app.download.Http
import com.vdo.app.download.Probe
import com.vdo.app.download.Progress
import com.vdo.app.download.SegmentedDownloader
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class DownloadCoreTest {
    private lateinit var server: MockWebServer
    private lateinit var base: String
    private lateinit var dir: File
    private val files = ConcurrentHashMap<String, Pair<String, ByteArray>>() // path -> (type, bytes)
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val failOnce = ConcurrentHashMap.newKeySet<String>()
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("vdo").toFile()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                val (type, bytes) = files[path] ?: return MockResponse().setResponseCode(404)
                if (failOnce.remove(path)) return MockResponse().setResponseCode(503)
                val r = MockResponse().setHeader("Content-Type", type)
                val range = request.getHeader("Range") ?: return r.setBody(Buffer().write(bytes))
                val spec = range.removePrefix("bytes=")
                val start = spec.substringBefore('-').toInt()
                val end = spec.substringAfter('-').toIntOrNull() ?: (bytes.size - 1)
                return r.setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                    .setBody(Buffer().write(bytes, start, end - start + 1))
            }
        }
        server.start()
        base = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun put(path: String, bytes: ByteArray, type: String = "application/octet-stream") { files[path] = type to bytes }
    private fun put(path: String, text: String, type: String) = put(path, text.toByteArray(), type)

    private fun encrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)); doFinal(data)
        }

    @Test
    fun attributeListsHandleQuotedCommas() {
        val a = HlsParser.attrs("""#EXT-X-STREAM-INF:BANDWIDTH=1280000,CODECS="avc1.4d401f,mp4a.40.2",RESOLUTION=1280x720,AUDIO="aud"""")
        assertEquals("1280000", a["BANDWIDTH"])
        assertEquals("avc1.4d401f,mp4a.40.2", a["CODECS"])
        assertEquals("1280x720", a["RESOLUTION"])
        assertEquals("aud", a["AUDIO"])
    }

    @Test
    fun masterPlaylistPicksQualityAndAudio() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",DEFAULT=YES,URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="aud"
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720,AUDIO="aud"
            mid/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="aud"
            /abs/high.m3u8
        """.trimIndent()
        val m = HlsParser.parseMaster(text, "https://cdn.example.com/v/master.m3u8?token=1")
        assertEquals(3, m.variants.size)
        assertEquals("https://cdn.example.com/abs/high.m3u8", m.pick(0)!!.url)
        assertEquals(720, m.pick(720)!!.height)
        assertEquals(360, m.pick(480)!!.height)
        assertEquals(360, m.pick(144)!!.height) // nothing small enough: fall back to the smallest
        assertEquals("https://cdn.example.com/v/audio/en.m3u8", m.audioFor(m.pick(0)!!)!!.uri)
    }

    @Test
    fun mediaPlaylistDetectsDrmLiveAndByteRanges() {
        val drm = HlsParser.parseMedia(
            "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST",
            "https://e.com/p.m3u8",
        )
        assertTrue(drm.drm)

        val live = HlsParser.parseMedia("#EXTM3U\n#EXTINF:4,\na.ts\n", "https://e.com/p.m3u8")
        assertFalse(live.endList)

        val ranged = HlsParser.parseMedia(
            """
            #EXTM3U
            #EXT-X-MAP:URI="main.mp4",BYTERANGE="720@0"
            #EXTINF:6,
            #EXT-X-BYTERANGE:1000@720
            main.mp4
            #EXTINF:6,
            #EXT-X-BYTERANGE:500
            main.mp4
            #EXT-X-ENDLIST
            """.trimIndent(),
            "https://e.com/v/p.m3u8",
        )
        assertEquals("mp4", ranged.track.container)
        assertEquals(720L, ranged.track.init!!.rangeLength)
        assertEquals(720L, ranged.track.parts[0].rangeStart)
        assertEquals(1720L, ranged.track.parts[1].rangeStart) // continues where the last range ended
        assertEquals(12.0, ranged.durationSec, 0.001)
        assertTrue(ranged.endList && !ranged.drm)
    }

    @Test
    fun encryptedHlsIsCachedDecryptedAndMerged() = runBlocking {
        val key = Random.nextBytes(16)
        val explicitIv = ByteArray(16) { (it + 1).toByte() }
        val plain = (0 until 12).map { Random.nextBytes(3000 + it * 517) }
        put("/s/key.bin", key)
        val sb = StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:7\n")
        plain.forEachIndexed { i, seg ->
            when {
                i < 4 -> { // clear
                    put("/s/seg$i.ts", seg)
                }
                i < 8 -> { // AES-128, IV derived from the media sequence number
                    if (i == 4) sb.append("#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n")
                    val iv = ByteArray(16).also { it[15] = (7 + i).toByte() }
                    put("/s/seg$i.ts", encrypt(seg, key, iv))
                }
                else -> { // AES-128 with an explicit IV
                    if (i == 8) sb.append("#EXT-X-KEY:METHOD=AES-128,URI=\"$base/s/key.bin\",IV=0x0102030405060708090A0B0C0D0E0F10\n")
                    put("/s/seg$i.ts", encrypt(seg, key, explicitIv))
                }
            }
            sb.append("#EXTINF:4.0,\nseg$i.ts\n")
        }
        sb.append("#EXT-X-ENDLIST\n")
        put("/s/index.m3u8", sb.toString(), "application/vnd.apple.mpegurl")
        failOnce += "/s/seg2.ts" // one transient failure must be retried

        val (url, text) = Http.text(client, "$base/s/index.m3u8", emptyMap())
        val media = HlsParser.parseMedia(text, url)
        assertEquals(12, media.track.parts.size)
        assertEquals("ts", media.track.container)

        val progress = Progress()
        val out = File(dir, "out.ts")
        val cache = File(dir, "cache")
        SegmentedDownloader(client, emptyMap(), 4, progress).download(media.track, cache, out)

        val expected = plain.reduce { a, b -> a + b }
        assertArrayEquals(expected, out.readBytes())
        assertEquals(12, progress.partsDone.get())
        assertEquals(expected.size.toLong(), progress.bytes.get())
        assertEquals(2, hits["/s/seg2.ts"]!!.get())
        assertEquals("cache is emptied by the merge", 0, cache.listFiles()!!.size)
        assertFalse(File(out.path + ".pos").exists())
    }

    @Test
    fun interruptedDownloadResumesFromCache() = runBlocking {
        val segs = (0 until 6).map { Random.nextBytes(4096) }
        segs.forEachIndexed { i, b -> if (i != 4) put("/r/seg$i.ts", b) } // seg4 is missing at first
        val playlist = "#EXTM3U\n" + segs.indices.joinToString("") { "#EXTINF:2,\nseg$it.ts\n" } + "#EXT-X-ENDLIST\n"
        val media = HlsParser.parseMedia(playlist, "$base/r/index.m3u8")
        val out = File(dir, "r.ts")
        val cache = File(dir, "rc")

        val first = runCatching { SegmentedDownloader(client, emptyMap(), 1, Progress()).download(media.track, cache, out) }
        assertTrue(first.isFailure)
        assertFalse(out.exists())
        assertTrue(File(cache, "000000.seg").exists())

        put("/r/seg4.ts", segs[4])
        val progress = Progress()
        SegmentedDownloader(client, emptyMap(), 3, progress).download(media.track, cache, out)
        assertArrayEquals(segs.reduce { a, b -> a + b }, out.readBytes())
        assertEquals("already cached segments are not fetched again", 1, hits["/r/seg0.ts"]!!.get())
        assertEquals(segs.sumOf { it.size }.toLong(), progress.bytes.get())

        // A third run finds the merged file and does nothing.
        SegmentedDownloader(client, emptyMap(), 3, Progress()).download(media.track, cache, out)
        assertEquals(1, hits["/r/seg0.ts"]!!.get())
    }

    @Test
    fun interruptedMergeContinuesWithoutDuplicatingData() = runBlocking {
        val segs = (0 until 5).map { Random.nextBytes(1000) }
        val cache = File(dir, "mc").apply { mkdirs() }
        val out = File(dir, "m.ts")
        // Simulate a crash after parts 0-1 were merged and part 2 was half written.
        out.writeBytes(segs[0] + segs[1] + segs[2].copyOf(400))
        File(out.path + ".pos").writeText("2 2000")
        for (i in 2 until 5) File(cache, "%06d.seg".format(i)).writeBytes(segs[i])
        val playlist = "#EXTM3U\n" + segs.indices.joinToString("") { "#EXTINF:2,\nseg$it.ts\n" } + "#EXT-X-ENDLIST\n"
        val media = HlsParser.parseMedia(playlist, "$base/none/index.m3u8")

        SegmentedDownloader(client, emptyMap(), 2, Progress()).download(media.track, cache, out)
        assertArrayEquals(segs.reduce { a, b -> a + b }, out.readBytes())
        assertNull("nothing is re-downloaded during a merge resume", hits["/none/seg0.ts"])
    }

    @Test
    fun plainFileIsSplitIntoRangesAndReassembled() = runBlocking {
        val data = Random.nextBytes(1_000_003)
        put("/f/movie.mp4", data, "video/mp4")
        val probe = Probe.probe("$base/f/movie.mp4", emptyMap(), client)
        assertEquals(DlKind.DIRECT, probe.kind)
        assertEquals(data.size.toLong(), probe.size)
        assertTrue(probe.acceptsRanges)
        assertFalse(probe.isAudio)

        val track = SegmentedDownloader.rangedTrack(probe.url, probe.size, chunk = 128 * 1024)
        assertEquals(8, track.parts.size)
        val out = File(dir, "movie.mp4")
        SegmentedDownloader(client, emptyMap(), 6, Progress()).download(track, File(dir, "fc"), out)
        assertArrayEquals(data, out.readBytes())
    }

    @Test
    fun probeRecognisesStreamsAndPages() = runBlocking {
        put("/p/master", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=640x360\na.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2,RESOLUTION=1280x720\nb.m3u8\n", "application/x-mpegURL")
        put("/p/page", "<html><body>hi</body></html>", "text/html")
        put("/p/song.mp3", Random.nextBytes(100), "audio/mpeg")
        val hls = Probe.probe("$base/p/master", emptyMap(), client)
        assertEquals(DlKind.HLS, hls.kind)
        assertEquals(listOf("720p", "360p"), hls.variants.map { it.label })
        assertTrue(Probe.probe("$base/p/page", emptyMap(), client).isHtml)
        assertTrue(Probe.probe("$base/p/song.mp3", emptyMap(), client).isAudio)
        assertEquals("My Clip.mp4", Probe.fileName("attachment; filename=\"My Clip.mp4\""))
        assertEquals("naïve.mp4", Probe.fileName("attachment; filename*=UTF-8''na%C3%AFve.mp4"))
        assertEquals("big buck bunny", Probe.titleFromUrl("https://x.com/v/big%20buck%20bunny.mp4?t=1"))
        assertEquals("x.com video", Probe.titleFromUrl("https://www.x.com/hls/master.m3u8"))
    }

    @Test
    fun dashTemplatesTimelinesAndProtection() {
        val mpd = """
            <?xml version="1.0"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT1M1S">
              <Period>
                <AdaptationSet mimeType="video/mp4">
                  <SegmentTemplate timescale="1000" duration="4000" startNumber="1"
                      initialization="${'$'}RepresentationID${'$'}/init.mp4" media="${'$'}RepresentationID${'$'}/seg-${'$'}Number%05d${'$'}.m4s"/>
                  <Representation id="v720" bandwidth="2000000" width="1280" height="720" codecs="avc1.64001f"/>
                  <Representation id="v1080" bandwidth="4000000" width="1920" height="1080" codecs="avc1.640028"/>
                </AdaptationSet>
                <AdaptationSet mimeType="audio/mp4" lang="en">
                  <Representation id="a1" bandwidth="128000" codecs="mp4a.40.2">
                    <BaseURL>audio/</BaseURL>
                    <SegmentTemplate timescale="48000" initialization="init.mp4" media="t-${'$'}Time${'$'}.m4s">
                      <SegmentTimeline><S t="0" d="96000" r="2"/><S d="48000"/></SegmentTimeline>
                    </SegmentTemplate>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val m = DashParser.parse(mpd, "https://cdn.example.com/dash/manifest.mpd")
        assertFalse(m.drm || m.live)
        assertEquals(61.0, m.durationSec, 0.001)
        val v = m.pickVideo(0)!!
        assertEquals("v1080", v.id)
        assertEquals("v720", m.pickVideo(720)!!.id)
        assertEquals(16, v.track.parts.size) // ceil(61 / 4)
        assertEquals("https://cdn.example.com/dash/v1080/init.mp4", v.track.init!!.url)
        assertEquals("https://cdn.example.com/dash/v1080/seg-00001.m4s", v.track.parts.first().url)
        assertEquals("https://cdn.example.com/dash/v1080/seg-00016.m4s", v.track.parts.last().url)
        val a = m.pickAudio(v)!!
        assertEquals(
            listOf("t-0.m4s", "t-96000.m4s", "t-192000.m4s", "t-288000.m4s").map { "https://cdn.example.com/dash/audio/$it" },
            a.track.parts.map { it.url },
        )

        val protectedMpd = mpd.replace(
            "<AdaptationSet mimeType=\"video/mp4\">",
            "<AdaptationSet mimeType=\"video/mp4\"><ContentProtection schemeIdUri=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"/>",
        )
        assertTrue(DashParser.parse(protectedMpd, "https://e.com/m.mpd").drm)
        assertNotNull(DashParser.parse(mpd.replace("static", "dynamic"), "https://e.com/m.mpd").takeIf { it.live })
        assertEquals(3723.5, DashParser.parseDuration("PT1H2M3.5S"), 0.001)
    }
}
