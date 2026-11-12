package com.vdo.app

import android.content.ComponentName
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vdo.app.data.DlKind
import com.vdo.app.data.DlStatus
import com.vdo.app.data.DownloadTask
import com.vdo.app.data.MediaRepository
import com.vdo.app.data.PlayItem
import com.vdo.app.data.Store
import com.vdo.app.download.DownloadEngine
import com.vdo.app.download.Exporter
import com.vdo.app.player.PlaybackService
import com.vdo.app.player.PlayerActivity
import com.vdo.app.player.PlayerHub
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a real device or emulator. Expects the test media server on the host
 * (http://10.0.2.2:8765) and the sample files pushed to /sdcard — see README.
 */
@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val host = "http://10.0.2.2:8765"

    @Before
    fun setUp() {
        Store.init(ctx)
        MediaRepository.permissions().forEach {
            runCatching { inst.uiAutomation.grantRuntimePermission(ctx.packageName, it) }
        }
    }

    private fun download(url: String, kind: DlKind, title: String): DownloadTask {
        var task = DownloadTask(id = "test-" + System.nanoTime(), url = url, title = title, kind = kind)
        runBlocking { DownloadEngine(ctx, task) { _, f -> task = f(task) }.run() }
        return task
    }

    private fun tracks(output: String): Map<String, MediaFormat> {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, Uri.parse(Exporter.playUri(output)), null)
        val out = (0 until ex.trackCount).associate { i ->
            val f = ex.getTrackFormat(i)
            f.getString(MediaFormat.KEY_MIME)!! to f
        }
        ex.release()
        return out
    }

    @Test
    fun encryptedHlsStreamBecomesOnePlayableMp4() {
        val task = download("$host/hls/index.m3u8", DlKind.HLS, "VDO device test stream")
        try {
            assertEquals(task.error, DlStatus.COMPLETED, task.status)
            assertEquals("video/mp4", task.mime)
            assertTrue("saved to the gallery: ${task.output}", task.output!!.startsWith("content://"))
            val t = tracks(task.output!!)
            val video = t.entries.firstOrNull { it.key.startsWith("video/") }?.value
            val audio = t.entries.firstOrNull { it.key.startsWith("audio/") }?.value
            assertNotNull("has a video track: ${t.keys}", video)
            assertNotNull("has an audio track: ${t.keys}", audio)
            val seconds = video!!.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0
            assertTrue("whole 20 s stream is there, got $seconds", seconds in 18.5..21.5)
            assertEquals(640, video.getInteger(MediaFormat.KEY_WIDTH))
        } finally {
            Exporter.delete(ctx, task.output)
        }
    }

    @Test
    fun plainFileDownloadsIntact() {
        val task = download("$host/sample-clip.mp4", DlKind.DIRECT, "VDO device test file")
        try {
            assertEquals(task.error, DlStatus.COMPLETED, task.status)
            val size = ctx.contentResolver.openInputStream(Uri.parse(task.output))!!.use { it.readBytes().size }
            assertEquals(task.total.toInt(), size)
            assertTrue(tracks(task.output!!).keys.any { it.startsWith("video/") })
        } finally {
            Exporter.delete(ctx, task.output)
        }
    }

    @Test
    fun privateDownloadStaysOutOfTheGallery() {
        var task = DownloadTask(id = "priv-" + System.nanoTime(), url = "$host/tone1.mp3", title = "Private tone", kind = DlKind.DIRECT, isPrivate = true)
        runBlocking { DownloadEngine(ctx, task) { _, f -> task = f(task) }.run() }
        try {
            assertEquals(task.error, DlStatus.COMPLETED, task.status)
            assertTrue(task.output!!.startsWith(ctx.filesDir.path))
            assertTrue(task.isAudio)
        } finally {
            Exporter.delete(ctx, task.output)
        }
    }

    @Test
    fun libraryListsDeviceMediaWithTags() = runBlocking {
        val videos = MediaRepository.load(ctx, audio = false)
        assertTrue("videos: ${videos.map { it.title }}", videos.any { it.title == "sample-clip" && it.duration in 19_000..21_000 })
        val songs = MediaRepository.load(ctx, audio = true)
        val evening = songs.firstOrNull { it.title == "Evening Tone" }
        assertNotNull("songs: ${songs.map { it.title }}", evening)
        assertEquals("Test Artist", evening!!.artist)
        assertEquals("Ambient", evening.genre)
        assertEquals("Relax", evening.folder)
        assertTrue(songs.any { it.title == "Morning Tone" && it.genre == "Jazz" && it.folder == "Music" })
    }

    @Test
    fun serviceActuallyPlaysALocalVideo() = runBlocking {
        val video = MediaRepository.load(ctx, audio = false).first { it.title == "sample-clip" }
        var controller: MediaController? = null
        inst.runOnMainSync {
            val f = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))).buildAsync()
            f.addListener({ controller = f.get() }, { it.run() })
        }
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (controller == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(200)
        val c = controller ?: error("could not connect to the playback service")
        inst.runOnMainSync {
            c.setMediaItems(listOf(PlayerHub.toMediaItem(video.toPlayItem())))
            c.prepare(); c.play()
        }
        var pos = 0L; var state = 0; var err: String? = null; var hasVideo = false; var sawLevels = false
        while (SystemClock.elapsedRealtime() < deadline) {
            inst.runOnMainSync {
                pos = c.currentPosition; state = c.playbackState; err = c.playerError?.message
                hasVideo = c.currentTracks.groups.any { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
            }
            if (com.vdo.app.player.AudioLevels.current()?.any { it > 0.05f } == true) sawLevels = true
            if ((pos > 2500 && sawLevels) || pos > 6000 || err != null) break
            Thread.sleep(100)
        }
        // The sound panel must be safe to use whatever effects this device offers.
        inst.runOnMainSync {
            com.vdo.app.player.AudioFx.changeBass(600)
            com.vdo.app.player.AudioFx.changeBoost(500)
            if (com.vdo.app.player.AudioFx.bands.isNotEmpty()) com.vdo.app.player.AudioFx.setBand(0, 300)
            com.vdo.app.player.AudioFx.reset()
            com.vdo.app.player.AudioFx.setOn(false)
        }
        inst.runOnMainSync { c.stop(); c.release() }
        assertEquals("player error", null, err)
        assertTrue("playback advanced (state=$state pos=$pos)", pos > 1500)
        assertTrue("video track detected", hasVideo)
        assertTrue("visualizer received audio levels", sawLevels)
    }

    @Test
    fun reopeningAVideoResumesWhereItStopped() = runBlocking {
        val video = MediaRepository.load(ctx, audio = false).first { it.title == "sample-clip" }
        val id = video.uri.toString()
        Store.setProgress(id, 0, 0)
        Store.resumePlayback = true

        // Open it in the real player screen and let it play for a while, never pausing.
        PlayerHub.pending.value = PlayerHub.Request(listOf(video.toPlayItem()), 0)
        val first = ActivityScenario.launch(PlayerActivity::class.java)
        val deadline = SystemClock.elapsedRealtime() + 150_000
        while (Store.position(id) < 5000 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(500)
        val saved = Store.position(id)
        assertTrue("position is saved while still playing, got $saved", saved >= 5000)
        // Leaving the player pauses the video, which saves the exact spot.
        first.close()
        Thread.sleep(1500)
        val left = Store.position(id)
        assertTrue("position kept after leaving, got $left", left >= saved)

        // Open the same file again: it must start from there, not from zero.
        var controller: MediaController? = null
        inst.runOnMainSync {
            val f = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))).buildAsync()
            f.addListener({ controller = f.get() }, { it.run() })
        }
        PlayerHub.pending.value = PlayerHub.Request(listOf(video.toPlayItem()), 0)
        ActivityScenario.launch(PlayerActivity::class.java).use {
            var pos = -1L; var ready = false
            while (SystemClock.elapsedRealtime() < deadline + 60_000) {
                val c = controller
                if (c != null) inst.runOnMainSync {
                    ready = c.playbackState == Player.STATE_READY && c.currentMediaItem?.mediaId == id
                    pos = c.currentPosition
                }
                if (ready) break
                Thread.sleep(300)
            }
            assertTrue("player became ready", ready)
            assertTrue("resumed near $left, was at $pos", pos >= left - 1500)
        }
        inst.runOnMainSync { controller?.stop(); controller?.release() }
        Store.setProgress(id, 0, 0)
    }

    @Test
    fun everyScreenOpensWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (tab in Tab.entries) {
                AppBus.tab.value = tab
                inst.waitForIdleSync()
                Thread.sleep(2500)
            }
            AppBus.tab.value = Tab.HOME
            AppBus.settingsOpen.value = true
            inst.waitForIdleSync(); Thread.sleep(2000)
            AppBus.settingsOpen.value = false
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
        }
        PlayerHub.pending.value = PlayerHub.Request(listOf(PlayItem("$host/tone1.mp3", "Tone", "Test Artist", isAudio = true)), 0)
        ActivityScenario.launch(PlayerActivity::class.java).use { scenario ->
            Thread.sleep(6000)
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
