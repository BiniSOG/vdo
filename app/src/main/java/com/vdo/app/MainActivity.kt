package com.vdo.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.vdo.app.browser.BrowserHolder
import com.vdo.app.browser.BrowserScreen
import com.vdo.app.data.PlayItem
import com.vdo.app.download.Downloads
import com.vdo.app.player.PlayerHub
import com.vdo.app.player.rememberController
import com.vdo.app.ui.DownloadsScreen
import com.vdo.app.ui.HomeScreen
import com.vdo.app.ui.LibraryScreen
import com.vdo.app.ui.SettingsScreen
import com.vdo.app.ui.VdoColors
import com.vdo.app.ui.VdoTheme
import kotlinx.coroutines.flow.MutableStateFlow

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    LIBRARY("Library", Icons.Rounded.VideoLibrary),
    BROWSER("Browser", Icons.Rounded.Public),
    DOWNLOADS("Downloads", Icons.Rounded.Download),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

/** Tiny in-app event bus for navigation between tabs. */
object AppBus {
    val tab = MutableStateFlow(Tab.HOME)

    /** A link shared to the app, shown in the Home URL box. */
    val incomingUrl = MutableStateFlow<String?>(null)

    /** A page the browser tab should load. */
    val browserUrl = MutableStateFlow<String?>(null)

    fun openInBrowser(url: String) {
        browserUrl.value = url
        tab.value = Tab.BROWSER
    }
}

class MainActivity : ComponentActivity() {
    val browser by lazy { BrowserHolder(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent { VdoTheme { MainApp(browser) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        browser.destroy()
        super.onDestroy()
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        if (intent.getStringExtra("tab") == "downloads") AppBus.tab.value = Tab.DOWNLOADS
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                val url = Regex("""https?://\S+""").find(text)?.value ?: return
                AppBus.incomingUrl.value = url
                AppBus.tab.value = Tab.HOME
            }
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                val type = intent.type.orEmpty()
                val isMedia = data.scheme == "content" || data.scheme == "file" ||
                    type.startsWith("video") || type.startsWith("audio") || type.contains("mpegurl") || type.contains("dash")
                if (isMedia) {
                    val name = data.lastPathSegment?.substringAfterLast('/') ?: "Media"
                    PlayerHub.play(this, listOf(PlayItem(data.toString(), name, isAudio = type.startsWith("audio"))))
                } else {
                    AppBus.incomingUrl.value = data.toString()
                    AppBus.tab.value = Tab.HOME
                }
            }
        }
    }
}

@Composable
private fun MainApp(browser: BrowserHolder) {
    val tab by AppBus.tab.collectAsState()
    val tasks by Downloads.tasks.collectAsState()
    val activeCount = tasks.count { it.active }

    if (Build.VERSION.SDK_INT >= 33) {
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    BackHandler(enabled = tab != Tab.HOME) {
        if (tab == Tab.BROWSER && browser.goBack()) return@BackHandler
        AppBus.tab.value = Tab.HOME
    }

    Scaffold(
        containerColor = VdoColors.Navy,
        bottomBar = {
            Column {
                MiniPlayer()
                NavigationBar(containerColor = Color(0xFF0D1236)) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { AppBus.tab.value = t },
                            icon = {
                                if (t == Tab.DOWNLOADS && activeCount > 0) {
                                    BadgedBox(badge = { Badge { Text("$activeCount") } }) { Icon(t.icon, t.label) }
                                } else Icon(t.icon, t.label)
                            },
                            label = { Text(t.label, fontSize = 11.sp, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White,
                                selectedTextColor = VdoColors.RoseGold,
                                indicatorColor = Color(0xFF4A3FB8),
                                unselectedIconColor = VdoColors.Muted,
                                unselectedTextColor = VdoColors.Muted,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().background(VdoColors.Background).padding(padding)) {
            when (tab) {
                Tab.HOME -> HomeScreen()
                Tab.LIBRARY -> LibraryScreen()
                Tab.BROWSER -> BrowserScreen(browser)
                Tab.DOWNLOADS -> DownloadsScreen()
                Tab.SETTINGS -> SettingsScreen()
            }
        }
    }
}

/** Shows what is playing above the navigation bar. */
@Composable
private fun MiniPlayer() {
    val controller = rememberController()
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(controller) {
        val l = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { version++ }
        }
        controller?.addListener(l)
        onDispose { controller?.removeListener(l) }
    }
    @Suppress("UNUSED_EXPRESSION") version
    val c = controller ?: return
    if (c.mediaItemCount == 0 || c.playbackState == Player.STATE_IDLE) return
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF1A2058)).clickable { PlayerHub.open(ctx) }
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painterResource(R.drawable.vdo_icon), null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                c.mediaMetadata.title?.toString() ?: "Now playing", color = Color.White,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(if (c.isPlaying) "Playing" else "Paused", color = VdoColors.Muted, fontSize = 12.sp)
        }
        IconButton(onClick = { if (c.isPlaying) c.pause() else c.play() }) {
            Icon(
                if (c.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (c.isPlaying) "Pause" else "Play", tint = Color.White,
            )
        }
        IconButton(onClick = { c.stop(); c.clearMediaItems() }) {
            Icon(Icons.Rounded.Close, "Stop", tint = VdoColors.Muted)
        }
    }
}
