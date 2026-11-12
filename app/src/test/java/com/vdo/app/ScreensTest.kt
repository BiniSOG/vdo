package com.vdo.app

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.vdo.app.browser.BrowserStart
import com.vdo.app.data.DlKind
import com.vdo.app.data.DlStatus
import com.vdo.app.data.DownloadTask
import com.vdo.app.data.LocalMedia
import com.vdo.app.data.PlayItem
import com.vdo.app.data.Store
import com.vdo.app.data.WebEntry
import com.vdo.app.download.Downloads
import com.vdo.app.player.AudioPlayerLayout
import com.vdo.app.player.PlayerActions
import com.vdo.app.player.PlayerUi
import com.vdo.app.player.VideoControls
import com.vdo.app.ui.DownloadsScreen
import com.vdo.app.ui.GroupBy
import com.vdo.app.ui.HomeContent
import com.vdo.app.ui.LibraryContent
import com.vdo.app.ui.SettingsScreen
import com.vdo.app.ui.VdoColors
import com.vdo.app.ui.VdoTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders every screen with sample data and saves a PNG, so layouts can be reviewed without a device. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun setUp() {
        Store.init(ApplicationProvider.getApplicationContext())
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            VdoTheme { Box(Modifier.fillMaxSize().background(VdoColors.Background)) { content() } }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun media(id: Long, title: String, audio: Boolean, folder: String, artist: String = "", genre: String = "", minutes: Int = 4) =
        LocalMedia(
            id, Uri.parse("content://media/external/${if (audio) "audio" else "video"}/media/$id"), title,
            minutes * 60_000L + 13_000, 48_000_000L * minutes, 1_794_000_000L - id * 900_000, folder, genre, artist, 0, audio,
            if (audio) 0 else 1080,
        )

    private val videos = listOf(
        media(1, "Harbour at sunrise", false, "Camera", minutes = 2),
        media(2, "Birthday dinner — long cut with everyone", false, "Camera", minutes = 14),
        media(3, "Lecture 04 Signals", false, "VDO", minutes = 52),
        media(4, "Trail ride", false, "VDO", minutes = 7),
        media(5, "Screen recording", false, "Recordings", minutes = 1),
        media(6, "Concert clip", false, "Camera", minutes = 3),
    )
    private val songs = listOf(
        media(11, "Evening Tone", true, "Relax", "Test Artist", "Ambient"),
        media(12, "Morning Tone", true, "Music", "Second Artist", "Jazz"),
        media(13, "Night Drive", true, "Music", "Second Artist", "Electronic", 5),
        media(14, "Paper Boats", true, "Downloads", "Test Artist", "Ambient", 3),
        media(15, "Low Tide", true, "Relax", "Coastline", "Ambient", 6),
    )

    @Test fun homeFirstRun() = shot("home_first_run") {
        HomeContent("", {}, false, emptyList(), emptyList(), {}, {}, {}, {}, {})
    }

    @Test fun homeInUse() = shot("home_in_use") {
        HomeContent(
            "https://example.com/talks/keynote.m3u8", {}, false,
            recents = listOf(
                PlayItem("content://a/1", "Harbour at sunrise", "Camera"),
                PlayItem("https://example.com/a.m3u8", "Keynote stream", "Web stream"),
                PlayItem("content://a/3", "Evening Tone", "Test Artist", isAudio = true),
            ),
            tasks = listOf(
                DownloadTask("a", "u", "Conference keynote 1080p", DlKind.HLS, status = DlStatus.RUNNING, downloaded = 310_000_000, total = 720_000_000),
                DownloadTask("b", "u", "Trail ride", DlKind.DIRECT, status = DlStatus.COMPLETED, total = 88_000_000, output = "content://x/1"),
                DownloadTask("c", "u", "Podcast episode 12", DlKind.DIRECT, status = DlStatus.COMPLETED, total = 41_000_000, output = "content://x/2", isAudio = true),
            ),
            {}, {}, {}, {}, {},
        )
    }

    @Test fun videosGrid() = shot("videos_grid") { LibraryContent(false, videos, true, {}, { _, _ -> }, {}, {}) }

    @Test fun videosPermission() = shot("videos_permission") { LibraryContent(false, null, false, {}, { _, _ -> }, {}, {}) }

    @Test fun musicList() = shot("music_list") { LibraryContent(true, songs, true, {}, { _, _ -> }, {}, {}) }

    @Test fun musicByArtist() = shot("music_by_artist") {
        LibraryContent(true, songs, true, {}, { _, _ -> }, {}, {}, initialGroupBy = GroupBy.ARTIST)
    }

    @Test fun musicByGenre() = shot("music_by_genre") {
        LibraryContent(true, songs, true, {}, { _, _ -> }, {}, {}, initialGroupBy = GroupBy.GENRE)
    }

    @Test fun downloadsActive() {
        Downloads.tasks.value = listOf(
            DownloadTask("a", "u", "Conference keynote 1080p", DlKind.HLS, status = DlStatus.RUNNING, downloaded = 310_000_000, total = 720_000_000, partsDone = 212, partsTotal = 480, speed = 3_400_000),
            DownloadTask("b", "u", "Lecture 05 Filters", DlKind.DASH, status = DlStatus.MERGING, downloaded = 512_000_000, total = 512_000_000),
            DownloadTask("c", "u", "Big sample file", DlKind.DIRECT, status = DlStatus.PAUSED, downloaded = 120_000_000, total = 900_000_000),
            DownloadTask("d", "u", "Expired link", DlKind.DIRECT, status = DlStatus.FAILED, error = "Server returned HTTP 403"),
            DownloadTask("e", "u", "Queued clip", DlKind.DIRECT, status = DlStatus.QUEUED),
        )
        shot("downloads_active") { DownloadsScreen() }
        Downloads.tasks.value = emptyList()
    }

    @Test fun downloadsEmpty() {
        Downloads.tasks.value = emptyList()
        shot("downloads_empty") { DownloadsScreen() }
    }

    @Test fun settings() = shot("settings") { SettingsScreen() }

    @Test fun browserStart() = shot("browser_start") {
        BrowserStart(
            listOf(WebEntry("https://example.org/videos", "Example videos"), WebEntry("https://news.example.com", "News")),
            listOf(WebEntry("https://example.com/talks", "Talks — Example")),
        ) {}
    }

    private val ui = PlayerUi(
        title = "Harbour at sunrise", artist = "Camera", playing = true, position = 83_000, duration = 255_000,
        hasPrev = true, hasNext = true, queueSize = 6,
        previewLevels = FloatArray(32) { i -> (0.25 + 0.7 * kotlin.math.abs(kotlin.math.sin(i * 0.55))).toFloat() * (1f - i / 60f) },
    )

    @Test fun playerVideoPortrait() = shot("player_video_portrait") {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF35506B), Color(0xFF8A5A44))))) {
            VideoControls(ui, PlayerActions())
        }
    }

    @Test @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun playerVideoLandscape() = shot("player_video_landscape") {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF35506B), Color(0xFF8A5A44))))) {
            VideoControls(ui.copy(queueSize = 1, speed = 1.5f), PlayerActions())
        }
    }

    @Test fun soundSheet() = shot("sound_sheet") { com.vdo.app.player.SoundSheet() }

    @Test fun playerAudio() = shot("player_audio") {
        AudioPlayerLayout(ui.copy(title = "Evening Tone", artist = "Test Artist", shuffle = true), PlayerActions())
    }

    @Test @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun playerAudioLandscape() = shot("player_audio_landscape") {
        AudioPlayerLayout(ui.copy(title = "Evening Tone", artist = "Test Artist", audioOnly = true), PlayerActions())
    }
}
