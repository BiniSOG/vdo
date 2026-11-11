package com.vdo.app.ui

import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vdo.app.data.LocalMedia
import com.vdo.app.data.MediaRepository
import com.vdo.app.data.Store
import com.vdo.app.player.PlayerHub
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ways to categorise the library. Artist and genre only make sense for music. */
enum class GroupBy(val label: String, val icon: ImageVector, val musicOnly: Boolean = false) {
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

/** The Videos or Music tab: loads media from the device and hands it to [LibraryContent]. */
@Composable
fun LibraryScreen(audio: Boolean) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(MediaRepository.hasPermission(ctx)) }
    var items by remember(audio) { mutableStateOf<List<LocalMedia>?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted = r.values.any { it }
    }
    val deleter = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { reload++ }

    LaunchedEffect(granted, reload, audio) {
        if (granted) items = MediaRepository.load(ctx, audio)
    }

    LibraryContent(
        audio = audio,
        items = items,
        granted = granted,
        onGrant = { ask.launch(MediaRepository.permissions()) },
        onPlay = { list, index -> PlayerHub.play(ctx, list.map { it.toPlayItem() }, index) },
        onShare = { m ->
            val send = Intent(Intent.ACTION_SEND).setType(if (m.isAudio) "audio/*" else "video/*")
                .putExtra(Intent.EXTRA_STREAM, m.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(send, "Share"))
        },
        onDelete = if (Build.VERSION.SDK_INT >= 30) { m ->
            val pi = MediaStore.createDeleteRequest(ctx.contentResolver, listOf(m.uri))
            deleter.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } else null,
    )
}

@Composable
fun LibraryContent(
    audio: Boolean,
    items: List<LocalMedia>?,
    granted: Boolean,
    onGrant: () -> Unit,
    onPlay: (List<LocalMedia>, Int) -> Unit,
    onShare: (LocalMedia) -> Unit,
    onDelete: ((LocalMedia) -> Unit)?,
    initialGroupBy: GroupBy? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(SortBy.DATE) }
    var sortMenu by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(!audio && Store.videoGrid) }
    // How the list is categorised (null = everything in one list) and which category is open.
    var groupBy by rememberSaveable { mutableStateOf(initialGroupBy) }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<LocalMedia?>(null) }
    val noun = if (audio) "song" else "video"

    Column(Modifier.fillMaxSize()) {
        if (searching) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text(if (audio) "Search songs and artists" else "Search videos") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    IconButton(onClick = { query = ""; searching = false }) { Icon(Icons.Rounded.Close, "Close search") }
                },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        } else {
            ScreenTitle(if (audio) "Music" else "Videos") {
                if (!granted || items.isNullOrEmpty()) return@ScreenTitle
                IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, "Search", tint = Color.White) }
                if (!audio) {
                    IconButton(onClick = { grid = !grid; Store.videoGrid = grid }) {
                        Icon(
                            if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                            if (grid) "Show as list" else "Show as grid", tint = Color.White,
                        )
                    }
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
        }

        if (!granted) {
            Empty(
                if (audio) Icons.Rounded.MusicNote else Icons.Rounded.VideoLibrary,
                if (audio) "Play the music on your phone" else "Play the videos on your phone",
                "Allow access and VDO will list everything on this device, ready to play.",
            ) { Button(onClick = onGrant) { Text("Allow access") } }
            return@Column
        }
        val all = items ?: return@Column

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

        if (all.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                item { FilterChip(by == null, onClick = { groupBy = null; group = null }, label = { Text("All ${noun}s") }) }
                items(GroupBy.entries.filter { audio || !it.musicOnly }) { g ->
                    FilterChip(by == g, onClick = { groupBy = g; group = null }, label = { Text(g.label) })
                }
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
            LazyVerticalGrid(GridCells.Adaptive(320.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(groups, key = { it.first }) { (name, members) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { group = name }.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(VdoColors.Surface),
                            contentAlignment = Alignment.Center,
                        ) { Icon(by.icon, null, tint = VdoColors.RoseGold) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                            Text(
                                "${members.size} $noun${if (members.size == 1) "" else "s"} · " + formatTime(members.sumOf { it.duration }),
                                color = VdoColors.Muted, fontSize = 13.sp,
                            )
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = VdoColors.Muted)
                    }
                }
            }
            return@Column
        }

        if (by != null && group != null) {
            BackHandler { group = null }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { group = null }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to ${by.label}", tint = Color.White)
                }
                Column {
                    Text(group!!, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${by.label} · ${shown.size} $noun${if (shown.size == 1) "" else "s"}", color = VdoColors.Muted, fontSize = 12.sp)
                }
            }
        }

        if (shown.isEmpty()) {
            Empty(
                if (audio) Icons.Rounded.MusicNote else Icons.Rounded.VideoLibrary,
                if (query.isBlank()) "No ${noun}s found" else "No matches",
                if (query.isBlank()) "Media saved on this device and your downloads will show up here." else "Try a different search.",
            )
            return@Column
        }

        val useGrid = grid && !audio
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (useGrid) 156.dp else 320.dp),
            contentPadding = PaddingValues(start = if (useGrid) 12.dp else 0.dp, end = if (useGrid) 12.dp else 0.dp, bottom = 16.dp),
        ) {
            if (shown.size > 1) item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.padding(horizontal = if (useGrid) 4.dp else 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(onClick = { onPlay(shown, 0) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play all")
                    }
                    FilledTonalButton(onClick = { onPlay(shown.shuffled(), 0) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle")
                    }
                }
            }
            itemsIndexed(shown, key = { _, m -> "${m.isAudio}-${m.id}" }) { index, m ->
                MediaItemView(
                    m, card = useGrid,
                    onPlay = { onPlay(shown, index) },
                    onShare = { onShare(m) },
                    onInfo = { info = m },
                    onDelete = onDelete?.let { d -> { d(m) } },
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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

/** One video or song, as a grid card (thumbnail on top) or a list row. Long-press opens the menu too. */
@Composable
private fun MediaItemView(
    m: LocalMedia, card: Boolean,
    onPlay: () -> Unit, onShare: () -> Unit, onInfo: () -> Unit, onDelete: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    val watched = Store.watched(m.uri.toString())
    val meta = if (m.isAudio) "${m.artist} · ${formatTime(m.duration)}"
    else listOfNotNull(formatSize(m.size), if (m.height > 0) "${m.height}p" else null, if (card) null else m.folder).joinToString(" · ")

    @Composable
    fun Menu() {
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(if (card) 32.dp else 48.dp)) {
                Icon(Icons.Rounded.MoreVert, "More options", tint = VdoColors.Muted)
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Play") }, onClick = { menu = false; onPlay() })
                DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; onShare() })
                DropdownMenuItem(text = { Text("Details") }, onClick = { menu = false; onInfo() })
                if (onDelete != null) DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }

    if (card) {
        Column(
            Modifier.padding(4.dp).clip(RoundedCornerShape(16.dp))
                .combinedClickable(onClick = onPlay, onLongClick = { menu = true }).padding(bottom = 6.dp),
        ) {
            Thumb(
                m.uri, Icons.Rounded.PlayArrow, Modifier.fillMaxWidth().aspectRatio(16f / 10f),
                watched = watched, badge = if (m.duration > 0) formatTime(m.duration) else null,
            )
            Row(Modifier.padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(m.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, lineHeight = 18.sp)
                    Text(meta, color = VdoColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Menu()
            }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onPlay, onLongClick = { menu = true })
                .padding(start = 16.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (m.isAudio) Thumb(m.artwork, Icons.Rounded.MusicNote, Modifier.size(52.dp), corner = 12.dp)
            else Thumb(
                m.uri, Icons.Rounded.PlayArrow, Modifier.width(116.dp).height(68.dp), corner = 12.dp,
                watched = watched, badge = if (m.duration > 0) formatTime(m.duration) else null,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(m.title, color = Color.White, maxLines = if (m.isAudio) 1 else 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                Text(meta, color = VdoColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Menu()
        }
    }
}
