package com.vdo.app.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.LinearProgressIndicator
import com.vdo.app.data.DlStatus
import com.vdo.app.data.DownloadTask
import com.vdo.app.download.Exporter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vdo.app.AppBus
import com.vdo.app.R
import com.vdo.app.Tab
import com.vdo.app.data.DlKind
import com.vdo.app.data.PlayItem
import com.vdo.app.data.Store
import com.vdo.app.download.Downloads
import com.vdo.app.download.Probe
import com.vdo.app.download.ProbeResult
import com.vdo.app.player.PlayerHub
import kotlinx.coroutines.launch

fun normalizeUrl(input: String): String? {
    val t = input.trim()
    if (t.isEmpty() || t.contains(' ')) return null
    return when {
        t.startsWith("http://") || t.startsWith("https://") -> t
        t.contains('.') && !t.contains("://") -> "https://$t"
        else -> null
    }
}

fun mimeForKind(kind: DlKind?): String? = when (kind) {
    DlKind.HLS -> "application/x-mpegURL"
    DlKind.DASH -> "application/dash+xml"
    else -> null
}

@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var downloadFor by remember { mutableStateOf<String?>(null) }
    val incoming by AppBus.incomingUrl.collectAsState()
    val recents by Store.recents.collectAsState()
    val tasks by Downloads.tasks.collectAsState()

    LaunchedEffect(incoming) {
        incoming?.let { url = it; AppBus.incomingUrl.value = null }
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    fun play() {
        val u = normalizeUrl(url) ?: return toast("Enter a valid link, e.g. https://example.com/video.mp4")
        busy = true
        scope.launch {
            val r = runCatching { Probe.probe(u, emptyMap()) }
            busy = false
            val p = r.getOrNull()
            when {
                p == null -> // Let the player try anyway; some servers reject probing.
                    PlayerHub.play(ctx, listOf(PlayItem(u, Probe.titleFromUrl(u))))
                p.isHtml -> { toast("That's a web page — opening it in the browser to find the video."); AppBus.openInBrowser(u) }
                else -> PlayerHub.play(
                    ctx,
                    listOf(PlayItem(p.url, p.fileName ?: Probe.titleFromUrl(p.url), "Web stream", p.isAudio, mimeForKind(p.kind))),
                )
            }
        }
    }

    HomeContent(
        url = url, onUrl = { url = it }, busy = busy,
        recents = recents, tasks = tasks,
        onPlay = { play() },
        onDownload = {
            val u = normalizeUrl(url)
            if (u == null) toast("Enter a valid link first") else downloadFor = u
        },
        onPaste = {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
            if (text.isBlank()) toast("Clipboard is empty") else url = text.trim()
        },
        onOpenItem = { PlayerHub.play(ctx, listOf(it)) },
        onOpenDownload = { t ->
            t.output?.let { PlayerHub.play(ctx, listOf(PlayItem(Exporter.playUri(it), t.title, "Downloaded", t.isAudio))) }
        },
    )

    downloadFor?.let { u -> DownloadSheet(u, emptyMap(), null) { downloadFor = null } }
}

@Composable
fun HomeContent(
    url: String, onUrl: (String) -> Unit, busy: Boolean,
    recents: List<PlayItem>, tasks: List<DownloadTask>,
    onPlay: () -> Unit, onDownload: () -> Unit, onPaste: () -> Unit,
    onOpenItem: (PlayItem) -> Unit, onOpenDownload: (DownloadTask) -> Unit,
) {
    val active = tasks.filter { it.active || it.status == DlStatus.QUEUED }
    val finished = tasks.filter { it.finished && !it.isPrivate }.take(3)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        // Brand banner with settings in the corner
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            Image(
                painterResource(R.drawable.vdo_logo), "VDO — Play · Stream · Download", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(2.7f).clip(RoundedCornerShape(24.dp))
                    .border(1.dp, VdoColors.RoseGold.copy(alpha = 0.22f), RoundedCornerShape(24.dp)),
            )
            IconButton(
                onClick = { AppBus.settingsOpen.value = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(50)).background(Color(0x66000000)),
            ) { Icon(Icons.Rounded.Settings, "Settings", tint = Color.White) }
        }

        // The main job of this screen: a link in, play or download out.
        Column(
            Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(VdoColors.Surface).padding(16.dp),
        ) {
            Text("Paste a link", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
            Text(
                "A video, music or stream address — or any web page with a video on it.",
                color = VdoColors.Muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            OutlinedTextField(
                value = url,
                onValueChange = onUrl,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("https://…") },
                leadingIcon = { Icon(Icons.Rounded.Link, null) },
                trailingIcon = {
                    if (url.isNotEmpty()) IconButton(onClick = { onUrl("") }) { Icon(Icons.Rounded.Close, "Clear") }
                    else TextButton(onClick = onPaste, modifier = Modifier.padding(end = 4.dp)) { Text("Paste", color = VdoColors.RoseGold) }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onPlay() }),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VdoColors.Violet, unfocusedBorderColor = Color(0xFF3A4288),
                    focusedContainerColor = Color(0xFF10163F), unfocusedContainerColor = Color(0xFF10163F),
                ),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onPlay, enabled = !busy, modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                    else { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play", fontSize = 16.sp) }
                }
                OutlinedButton(
                    onClick = onDownload, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, VdoColors.RoseGold.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VdoColors.RoseGold),
                ) { Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(6.dp)); Text("Download", fontSize = 16.sp) }
            }
        }

        // Downloads in flight, so progress is visible without leaving Home.
        if (active.isNotEmpty()) {
            val t = active.first()
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(VdoColors.Surface)
                    .clickable { AppBus.tab.value = Tab.DOWNLOADS }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Download, null, tint = VdoColors.RoseGold)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (active.size == 1) t.title else "Downloading ${active.size} files",
                        color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    val f = active.map { it.fraction }.filter { it >= 0 }.average().toFloat()
                    if (f.isNaN()) LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = VdoColors.Violet, trackColor = VdoColors.SurfaceHigh)
                    else LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = VdoColors.Violet, trackColor = VdoColors.SurfaceHigh)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = VdoColors.Muted)
            }
            Spacer(Modifier.height(16.dp))
        }

        if (recents.isNotEmpty()) {
            SectionTitle("Continue watching") {
                TextButton(onClick = { Store.clearRecents() }) { Text("Clear", color = VdoColors.Muted) }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recents.take(12), key = { it.uri }) { item ->
                    Column(Modifier.width(168.dp).clip(RoundedCornerShape(16.dp)).clickable { onOpenItem(item) }) {
                        Thumb(
                            if (item.isAudio) item.artwork else item.uri.takeIf { it.startsWith("content") || it.startsWith("file") },
                            if (item.isAudio) Icons.Rounded.MusicNote else Icons.Rounded.PlayArrow,
                            Modifier.fillMaxWidth().aspectRatio(16f / 9f), watched = Store.watched(item.uri),
                        )
                        Text(
                            item.title, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                        )
                        val pos = Store.position(item.uri)
                        Text(
                            if (pos > 0) "Resume at ${formatTime(pos)}" else item.subtitle.ifBlank { "Play again" },
                            color = VdoColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        if (finished.isNotEmpty()) {
            SectionTitle("Latest downloads") {
                TextButton(onClick = { AppBus.tab.value = Tab.DOWNLOADS }) { Text("See all", color = VdoColors.RoseGold) }
            }
            finished.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenDownload(t) }.padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Thumb(
                        if (t.isAudio) null else t.output?.let(Exporter::playUri),
                        if (t.isAudio) Icons.Rounded.MusicNote else Icons.Rounded.PlayArrow,
                        Modifier.width(96.dp).height(56.dp), corner = 12.dp,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatSize(t.total), color = VdoColors.Muted, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // First-run guidance: shown until the user has something of their own on this screen.
        if (recents.isEmpty() && finished.isEmpty()) {
            SectionTitle("How to save a video from a website")
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(VdoColors.Surface).padding(vertical = 8.dp),
            ) {
                Step(1, "Open the page", "Use the Browser tab, or paste the page address above.")
                Step(2, "Start the video", "VDO spots the video as soon as it begins to play.")
                Step(3, "Tap the download button", "Pick a quality — it is saved to your gallery.")
            }
            TextButton(onClick = { AppBus.tab.value = Tab.BROWSER }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Icon(Icons.Rounded.Public, null, tint = VdoColors.RoseGold)
                Spacer(Modifier.width(8.dp))
                Text("Open the browser", color = VdoColors.RoseGold)
            }
        }
    }
}

@Composable
fun Step(number: Int, title: String, body: String) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(50)).background(VdoColors.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) { Text("$number", color = VdoColors.RoseGold, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(body, color = VdoColors.Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

/**
 * Inspects a link and lets the user pick a name, quality and destination before queueing it.
 * Shared by the Home screen and the browser.
 */
@Composable
fun DownloadSheet(url: String, headers: Map<String, String>, suggestedTitle: String?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var probe by remember(url) { mutableStateOf<ProbeResult?>(null) }
    var failure by remember(url) { mutableStateOf<String?>(null) }
    var title by remember(url) { mutableStateOf(suggestedTitle?.takeIf { it.isNotBlank() } ?: Probe.titleFromUrl(url)) }
    var height by remember(url) { mutableIntStateOf(0) }
    var private by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        runCatching { Probe.probe(url, headers) }
            .onSuccess { p ->
                probe = p
                if (suggestedTitle.isNullOrBlank()) p.fileName?.let { title = it.substringBeforeLast('.') }
            }
            .onFailure { failure = it.message ?: "Couldn't reach this link" }
    }

    fun start(kind: DlKind, target: String, audio: Boolean) {
        Downloads.enqueue(target, title, kind, headers, height, private, audio)
        Toast.makeText(ctx, "Download started", Toast.LENGTH_SHORT).show()
        AppBus.tab.value = Tab.DOWNLOADS
        onClose()
    }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = VdoColors.Surface) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text("Download", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(url, color = VdoColors.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            val p = probe
            when {
                p == null && failure == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Checking the link…")
                }
                p == null -> {
                    Text("Couldn't inspect this link: $failure", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { start(DlKind.DIRECT, url, false) }) { Text("Try downloading anyway") }
                }
                p.isHtml -> {
                    Text("This link is a web page, not a media file. Open it in the browser and VDO will detect the videos on it.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { AppBus.openInBrowser(p.url); onClose() }) { Text("Open in browser") }
                }
                p.drm -> Text(
                    "This stream is protected by DRM. VDO doesn't bypass copy protection, so it can't be downloaded.",
                    color = MaterialTheme.colorScheme.error,
                )
                p.live -> Text(
                    "This is a live broadcast. It can be played, but can only be downloaded once it has ended.",
                    color = MaterialTheme.colorScheme.error,
                )
                else -> {
                    OutlinedTextField(
                        value = title, onValueChange = { title = it }, label = { Text("File name") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (p.kind) {
                            DlKind.HLS -> "HLS stream — segments are cached, merged and saved as one file"
                            DlKind.DASH -> "DASH stream — audio and video are cached, merged and saved as one file"
                            else -> listOfNotNull(p.mime, if (p.size > 0) formatSize(p.size) else null,
                                if (p.acceptsRanges) "multi-connection" else null).joinToString(" · ")
                        },
                        color = VdoColors.Muted, fontSize = 12.sp,
                    )
                    if (p.variants.size > 1) {
                        Spacer(Modifier.height(12.dp))
                        Text("Quality", fontWeight = FontWeight.SemiBold)
                        (listOf(0 to "Best available") + p.variants.filter { it.height > 0 }.map { it.height to it.label })
                            .forEach { (h, label) ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { height = h },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = height == h, onClick = { height = h })
                                    Text(label)
                                }
                            }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Icon(Icons.Rounded.Lock, null, tint = VdoColors.RoseGold, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Save to private folder")
                            Text("Hidden from the gallery, PIN protected", color = VdoColors.Muted, fontSize = 12.sp)
                        }
                        Switch(checked = private, onCheckedChange = { private = it })
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = {
                                PlayerHub.play(ctx, listOf(PlayItem(p.url, title, "Web stream", p.isAudio, mimeForKind(p.kind))), 0, headers)
                                onClose()
                            },
                            modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(16.dp),
                        ) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play") }
                        Button(
                            onClick = { start(p.kind ?: DlKind.DIRECT, p.url, p.isAudio) },
                            modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(16.dp),
                        ) { Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(6.dp)); Text("Download") }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
