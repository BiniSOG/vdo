package com.vdo.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vdo.app.data.DlStatus
import com.vdo.app.data.DownloadTask
import com.vdo.app.data.PlayItem
import com.vdo.app.data.Store
import com.vdo.app.download.Downloads
import com.vdo.app.download.Exporter
import com.vdo.app.player.PlayerHub

@Composable
fun DownloadsScreen() {
    val ctx = LocalContext.current
    val tasks by Downloads.tasks.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var unlocked by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<DownloadTask?>(null) }
    var delete by remember { mutableStateOf<DownloadTask?>(null) }

    val inProgress = tasks.filter { !it.finished }
    val done = tasks.filter { it.finished && !it.isPrivate }
    val hidden = tasks.filter { it.finished && it.isPrivate }

    fun play(list: List<DownloadTask>, task: DownloadTask) {
        val playable = list.filter { it.output != null }
        if (!Exporter.exists(ctx, task.output)) {
            Toast.makeText(ctx, "This file was moved or deleted outside VDO", Toast.LENGTH_LONG).show()
            return
        }
        PlayerHub.play(
            ctx,
            playable.map { PlayItem(Exporter.playUri(it.output!!), it.title, "Downloaded", it.isAudio) },
            playable.indexOf(task).coerceAtLeast(0),
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Downloads", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            if (tab == 0 && inProgress.isNotEmpty()) {
                val anyActive = inProgress.any { it.active || it.status == DlStatus.QUEUED }
                TextButton(onClick = { if (anyActive) Downloads.pauseAll() else Downloads.resumeAll() }) {
                    Text(if (anyActive) "Pause all" else "Resume all", color = VdoColors.RoseGold)
                }
            }
        }
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = VdoColors.RoseGold) {
            Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Active" + if (inProgress.isNotEmpty()) " (${inProgress.size})" else "") })
            Tab(tab == 1, onClick = { tab = 1 }, text = { Text("Finished" + if (done.isNotEmpty()) " (${done.size})" else "") })
            Tab(tab == 2, onClick = { tab = 2 }, text = { Text("Private") }, icon = null)
        }

        when (tab) {
            0 -> if (inProgress.isEmpty()) {
                Empty(Icons.Rounded.Download, "No active downloads", "Paste a link on Home, or browse to a video and tap the download button.")
            } else LazyColumn(contentPadding = PaddingValues(12.dp)) {
                items(inProgress, key = { it.id }) { t -> ActiveCard(t) { delete = t } }
            }
            1 -> if (done.isEmpty()) {
                Empty(Icons.Rounded.Download, "Nothing downloaded yet", "Finished downloads appear here and in your gallery.")
            } else LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(done, key = { it.id }) { t ->
                    DoneRow(t, onPlay = { play(done, t) }, onRename = { rename = t }, onDelete = { delete = t })
                }
            }
            else -> if (!unlocked) {
                PinGate { unlocked = true }
            } else if (hidden.isEmpty()) {
                Empty(Icons.Rounded.Lock, "Private folder is empty", "Choose “Save to private folder” when downloading, or move a finished download here.")
            } else LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(hidden, key = { it.id }) { t ->
                    DoneRow(t, onPlay = { play(hidden, t) }, onRename = { rename = t }, onDelete = { delete = t })
                }
            }
        }
    }

    rename?.let { t ->
        var name by remember(t.id) { mutableStateOf(t.title) }
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { Downloads.rename(t.id, name); rename = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancel") } },
        )
    }
    delete?.let { t ->
        AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text(if (t.finished) "Delete download?" else "Cancel download?") },
            text = { Text(if (t.finished) "“${t.title}” will be removed from this device." else "“${t.title}” and its cached data will be removed.") },
            confirmButton = {
                TextButton(onClick = { Downloads.remove(t.id, deleteFile = true); delete = null }) {
                    Text(if (t.finished) "Delete" else "Cancel download", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    if (t.finished) TextButton(onClick = { Downloads.remove(t.id, deleteFile = false); delete = null }) { Text("Remove from list") }
                    TextButton(onClick = { delete = null }) { Text("Keep") }
                }
            },
        )
    }
}

@Composable
private fun ActiveCard(t: DownloadTask, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(VdoColors.Surface)
            .padding(start = 16.dp, top = 12.dp, bottom = 14.dp, end = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = when (t.status) {
                    DlStatus.RUNNING -> buildString {
                        append(formatSize(t.downloaded))
                        if (t.total > 0) append(" / ${formatSize(t.total)}")
                        append(" · ${formatSize(t.speed)}/s")
                        if (t.kind != com.vdo.app.data.DlKind.DIRECT && t.partsTotal > 0) append(" · ${t.partsDone}/${t.partsTotal} segments")
                    }
                    DlStatus.MERGING -> "Merging cached segments into one file…"
                    DlStatus.QUEUED -> t.error ?: "Waiting in queue"
                    DlStatus.PAUSED -> "Paused" + if (t.downloaded > 0) " · ${formatSize(t.downloaded)} cached" else ""
                    DlStatus.FAILED -> t.error ?: "Failed"
                    DlStatus.COMPLETED -> "Done"
                }
                Text(
                    status, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (t.status == DlStatus.FAILED) MaterialTheme.colorScheme.error else VdoColors.Muted,
                )
            }
            when (t.status) {
                DlStatus.RUNNING, DlStatus.QUEUED, DlStatus.MERGING ->
                    IconButton(onClick = { Downloads.pause(t.id) }) { Icon(Icons.Rounded.Pause, "Pause", tint = Color.White) }
                DlStatus.PAUSED ->
                    IconButton(onClick = { Downloads.resume(t.id) }) { Icon(Icons.Rounded.PlayArrow, "Resume", tint = Color.White) }
                else -> IconButton(onClick = { Downloads.resume(t.id) }) { Icon(Icons.Rounded.Refresh, "Retry", tint = Color.White) }
            }
            IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel", tint = VdoColors.Muted) }
        }
        Spacer(Modifier.height(8.dp))
        val f = t.fraction
        val mod = Modifier.fillMaxWidth().padding(end = 12.dp).height(6.dp).clip(RoundedCornerShape(3.dp))
        when {
            t.status == DlStatus.FAILED ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Cached data is kept — tap retry to continue", color = VdoColors.Muted, fontSize = 11.sp)
                }
            t.status == DlStatus.MERGING || (t.status == DlStatus.RUNNING && f < 0) ->
                LinearProgressIndicator(mod, color = VdoColors.RoseGold, trackColor = VdoColors.SurfaceHigh)
            else -> LinearProgressIndicator(
                progress = { f.coerceAtLeast(0f) }, modifier = mod,
                color = if (t.status == DlStatus.RUNNING) VdoColors.Violet else VdoColors.Muted, trackColor = VdoColors.SurfaceHigh,
            )
        }
    }
}

@Composable
private fun DoneRow(t: DownloadTask, onPlay: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(if (t.isAudio) 56.dp else 112.dp).height(if (t.isAudio) 56.dp else 64.dp)
                .clip(RoundedCornerShape(12.dp)).background(VdoColors.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (t.isAudio) Icons.Rounded.MusicNote else Icons.Rounded.PlayArrow, null, tint = VdoColors.RoseGold)
            if (!t.isAudio && t.output != null) {
                AsyncImage(
                    model = Exporter.playUri(t.output), contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
            val pos = t.output?.let { Store.position(Exporter.playUri(it)) } ?: 0
            Text(
                formatSize(t.total) + if (pos > 0) " · resume at ${formatTime(pos)}" else "",
                color = VdoColors.Muted, fontSize = 12.sp,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = VdoColors.Muted) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Play") }, onClick = { menu = false; onPlay() })
                DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; runCatching { Exporter.share(ctx, t) } })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(
                    text = { Text(if (t.isPrivate) "Move to gallery" else "Move to private folder") },
                    onClick = {
                        menu = false
                        Downloads.setPrivate(t.id, !t.isPrivate) { e ->
                            Toast.makeText(ctx, if (e == null) "Moved" else "Couldn't move: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                DropdownMenuItem(text = { Text("Copy source link") }, onClick = {
                    menu = false
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("link", t.url))
                })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

/** Asks for the private-folder PIN, or lets the user create one the first time. */
@Composable
fun PinGate(onUnlocked: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val creating = !Store.hasPin
    Empty(
        Icons.Rounded.Lock,
        if (creating) "Create a PIN" else "Private folder",
        if (creating) "Choose a 4–8 digit PIN. Private downloads are hidden from the gallery and other apps."
        else "Enter your PIN to see your private downloads.",
    ) {
        OutlinedTextField(
            value = pin,
            onValueChange = { v -> pin = v.filter(Char::isDigit).take(8); error = null },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            isError = error != null,
            supportingText = { error?.let { Text(it) } },
            shape = RoundedCornerShape(16.dp),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            when {
                pin.length < 4 -> error = "Use at least 4 digits"
                creating -> { Store.setPin(pin); onUnlocked() }
                Store.checkPin(pin) -> onUnlocked()
                else -> { error = "Wrong PIN"; pin = "" }
            }
        }) { Text(if (creating) "Set PIN" else "Unlock") }
    }
}
