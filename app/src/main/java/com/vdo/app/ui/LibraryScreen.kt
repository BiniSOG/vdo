package com.vdo.app.ui

import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.ui.graphics.vector.ImageVector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vdo.app.data.LocalMedia
import com.vdo.app.data.MediaRepository
import com.vdo.app.player.PlayerHub

/** Ways to categorise the library. Artist and genre only make sense for music. */
private enum class GroupBy(val label: String, val icon: ImageVector, val musicOnly: Boolean = false) {
    FOLDER("Folders", Icons.Rounded.Folder),
    ARTIST("Artists", Icons.Rounded.Person, true),
    GENRE("Genres", Icons.Rounded.Category, true),
    DATE("Date", Icons.Rounded.CalendarMonth),
    NAME("A–Z", Icons.Rounded.SortByAlpha);

    fun key(m: LocalMedia): String = when (this) {
        FOLDER -> m.folder
        ARTIST -> m.artist.ifBlank { "Unknown artist" }
        GENRE -> m.genre.ifBlank { "Unknown genre" }
        DATE -> SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(m.date * 1000))
        NAME -> m.title.trim().firstOrNull()?.takeIf { it.isLetter() }?.uppercase() ?: "#"
    }
}

private enum class SortBy(val label: String) { DATE("Newest first"), NAME("Name"), SIZE("Largest first"), DURATION("Longest first") }

@Composable
fun LibraryScreen() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(MediaRepository.hasPermission(ctx)) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var videos by remember { mutableStateOf<List<LocalMedia>?>(null) }
    var audio by remember { mutableStateOf<List<LocalMedia>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(SortBy.DATE) }
    var sortMenu by remember { mutableStateOf(false) }
    // How the list is categorised (null = everything in one list) and which category is open.
    var groupBy by rememberSaveable { mutableStateOf<GroupBy?>(null) }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<LocalMedia?>(null) }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted = r.values.any { it }
    }
    val deleter = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { reload++ }

    LaunchedEffect(granted, reload) {
        if (granted) {
            videos = MediaRepository.load(ctx, false)
            audio = MediaRepository.load(ctx, true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (searching) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search your media") }, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    trailingIcon = {
                        IconButton(onClick = { query = ""; searching = false }) { Icon(Icons.Rounded.Close, "Close search") }
                    },
                )
            } else {
                Text("Library", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, "Search", tint = Color.White) }
            }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort", tint = Color.White) }
                DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortBy.entries.forEach {
                        DropdownMenuItem(
                            text = { Text(it.label, color = if (it == sort) VdoColors.RoseGold else Color.Unspecified) },
                            onClick = { sort = it; sortMenu = false },
                        )
                    }
                }
            }
        }
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = VdoColors.RoseGold) {
            Tab(tab == 0, onClick = { tab = 0; groupBy = null; group = null }, text = { Text("Videos" + (videos?.let { " (${it.size})" } ?: "")) })
            Tab(tab == 1, onClick = { tab = 1; groupBy = null; group = null }, text = { Text("Music" + (audio?.let { " (${it.size})" } ?: "")) })
        }

        if (!granted) {
            Empty(Icons.Rounded.VideoLibrary, "See your videos and music", "Allow access so VDO can list the media on this device.") {
                Button(onClick = { ask.launch(MediaRepository.permissions()) }) { Text("Allow access") }
            }
            return@Column
        }

        val all = (if (tab == 0) videos else audio)
        if (all == null) return@Column
        val by = groupBy
        val matching = remember(all, query) {
            all.filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) }
        }
        val shown = remember(matching, sort, by, group) {
            matching.filter { by == null || group == null || by.key(it) == group }.let { list ->
                when (sort) {
                    SortBy.DATE -> list.sortedByDescending { it.date }
                    SortBy.NAME -> list.sortedBy { it.title.lowercase() }
                    SortBy.SIZE -> list.sortedByDescending { it.size }
                    SortBy.DURATION -> list.sortedByDescending { it.duration }
                }
            }
        }

        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(by == null, onClick = { groupBy = null; group = null }, label = { Text(if (tab == 0) "All videos" else "All songs") }) }
            items(GroupBy.entries.filter { tab == 1 || !it.musicOnly }) { g ->
                FilterChip(by == g, onClick = { groupBy = g; group = null }, label = { Text(g.label) })
            }
        }

        // A category is chosen but none is open yet: list the categories.
        if (by != null && group == null) {
            val groups = remember(matching, by) {
                val grouped = matching.groupBy { by.key(it) }
                val keys = if (by == GroupBy.DATE) grouped.keys.sortedByDescending { k -> grouped.getValue(k).maxOf { it.date } }
                else grouped.keys.sortedWith(compareBy({ it == "#" || it.startsWith("Unknown") }, { it.lowercase() }))
                keys.map { it to grouped.getValue(it) }
            }
            if (groups.isEmpty()) {
                Empty(by.icon, "Nothing here yet", "Media on your device will be organised here.")
                return@Column
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(groups, key = { it.first }) { (name, members) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { group = name }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(VdoColors.SurfaceHigh),
                            contentAlignment = Alignment.Center,
                        ) { Icon(by.icon, null, tint = VdoColors.RoseGold) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                            Text(
                                "${members.size} ${if (tab == 0) "video" else "song"}${if (members.size == 1) "" else "s"} · " +
                                    formatTime(members.sumOf { it.duration }),
                                color = VdoColors.Muted, fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
            return@Column
        }

        if (by != null && group != null) {
            BackHandler { group = null }
            Row(
                Modifier.fillMaxWidth().clickable { group = null }.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { group = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to ${by.label}", tint = Color.White) }
                Column {
                    Text(group!!, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(by.label, color = VdoColors.Muted, fontSize = 12.sp)
                }
            }
        }

        if (shown.isEmpty()) {
            Empty(
                if (tab == 0) Icons.Rounded.VideoLibrary else Icons.Rounded.MusicNote,
                if (query.isBlank()) "Nothing here yet" else "No matches",
                if (query.isBlank()) "Media on your device and your downloads will show up here." else "Try a different search.",
            )
            return@Column
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            if (shown.size > 1) item {
                TextButton(
                    onClick = { val s = shown.shuffled(); PlayerHub.play(ctx, s.map { it.toPlayItem() }, 0) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Icon(Icons.Rounded.Shuffle, null, tint = VdoColors.RoseGold)
                    Spacer(Modifier.width(8.dp))
                    Text("Shuffle all", color = VdoColors.RoseGold)
                }
            }
            itemsIndexed(shown, key = { _, m -> "${m.isAudio}-${m.id}" }) { index, m ->
                MediaRow(
                    m,
                    onPlay = { PlayerHub.play(ctx, shown.map { it.toPlayItem() }, index) },
                    onShare = {
                        val send = Intent(Intent.ACTION_SEND).setType(if (m.isAudio) "audio/*" else "video/*")
                            .putExtra(Intent.EXTRA_STREAM, m.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        ctx.startActivity(Intent.createChooser(send, "Share"))
                    },
                    onInfo = { info = m },
                    onDelete = if (Build.VERSION.SDK_INT >= 30) {
                        {
                            val pi = MediaStore.createDeleteRequest(ctx.contentResolver, listOf(m.uri))
                            deleter.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        }
                    } else null,
                )
            }
        }
    }

    info?.let { m ->
        AlertDialog(
            onDismissRequest = { info = null },
            confirmButton = { TextButton(onClick = { info = null }) { Text("Close") } },
            title = { Text(m.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Text("Folder: ${m.folder}")
                    Text("Duration: ${formatTime(m.duration)}")
                    Text("Size: ${formatSize(m.size)}")
                    if (m.height > 0) Text("Resolution: ${m.height}p")
                    if (m.isAudio) Text("Artist: ${m.artist}")
                    if (m.isAudio) Text("Genre: ${m.genre}")
                    Text("Added: ${SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(m.date * 1000))}")
                }
            },
        )
    }
}

@Composable
private fun MediaRow(m: LocalMedia, onPlay: () -> Unit, onShare: () -> Unit, onInfo: () -> Unit, onDelete: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(if (m.isAudio) 56.dp else 112.dp).height(if (m.isAudio) 56.dp else 64.dp)
                .clip(RoundedCornerShape(12.dp)).background(VdoColors.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (m.isAudio) Icons.Rounded.MusicNote else Icons.Rounded.VideoLibrary, null, tint = VdoColors.Muted)
            AsyncImage(
                model = if (m.isAudio) m.artwork else m.uri, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            if (!m.isAudio && m.duration > 0) {
                Text(
                    formatTime(m.duration), color = Color.White, fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(4.dp))
                        .background(Color(0xB3000000)).padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
            Text(
                if (m.isAudio) "${m.artist} · ${formatTime(m.duration)}"
                else listOfNotNull(formatSize(m.size), if (m.height > 0) "${m.height}p" else null, m.folder).joinToString(" · "),
                color = VdoColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = VdoColors.Muted) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Play") }, onClick = { menu = false; onPlay() })
                DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; onShare() })
                DropdownMenuItem(text = { Text("Details") }, onClick = { menu = false; onInfo() })
                if (onDelete != null) DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
fun Empty(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String,
    action: @Composable () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = VdoColors.Violet, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
        Text(body, color = VdoColors.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
        action()
    }
}
