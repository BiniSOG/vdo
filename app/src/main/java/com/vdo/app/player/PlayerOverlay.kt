package com.vdo.app.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vdo.app.R
import com.vdo.app.ui.VdoColors
import com.vdo.app.ui.formatTime

enum class Sheet { NONE, MORE, SPEED, AUDIO, SUBS, QUALITY, SLEEP, QUEUE, SOUND }

/** Everything the player controls need to draw themselves. */
data class PlayerUi(
    val title: String = "",
    val artist: String = "",
    val playing: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val hasPrev: Boolean = false,
    val hasNext: Boolean = false,
    val queueSize: Int = 1,
    val speed: Float = 1f,
    val resizeLabel: String = "Fit",
    val repeatMode: Int = 0, // 0 off, 1 one, 2 all (matches Player.REPEAT_MODE_*)
    val shuffle: Boolean = false,
    val sleepActive: Boolean = false,
    /** True when a video is being played with its picture switched off. */
    val audioOnly: Boolean = false,
    val soundOn: Boolean = false,
    val visualizer: Boolean = true,
    /** Fixed levels for previews and screenshots; null means "follow the live audio". */
    val previewLevels: FloatArray? = null,
)

class PlayerActions(
    val back: () -> Unit = {},
    val playPause: () -> Unit = {},
    val seekBy: (Long) -> Unit = {},
    val seekTo: (Long) -> Unit = {},
    val prev: () -> Unit = {},
    val next: () -> Unit = {},
    val pip: () -> Unit = {},
    val lock: () -> Unit = {},
    val cycleResize: () -> Unit = {},
    val rotate: () -> Unit = {},
    val cycleRepeat: () -> Unit = {},
    val toggleShuffle: () -> Unit = {},
    val toggleAudioOnly: () -> Unit = {},
    val open: (Sheet) -> Unit = {},
    val scrubbing: (Boolean) -> Unit = {},
)

@Composable
private fun SeekBar(ui: PlayerUi, a: PlayerActions, modifier: Modifier = Modifier) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    val shown = scrub?.let { (it * ui.duration).toLong() } ?: ui.position
    val colors = SliderDefaults.colors(
        thumbColor = VdoColors.RoseGold, activeTrackColor = VdoColors.Violet,
        inactiveTrackColor = Color(0x55FFFFFF), disabledInactiveTrackColor = Color(0x33FFFFFF),
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(shown), color = Color.White, fontSize = 12.sp, modifier = Modifier.width(48.dp))
        Slider(
            value = scrub ?: if (ui.duration > 0) (ui.position.toFloat() / ui.duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { scrub = it; a.scrubbing(true) },
            onValueChangeFinished = {
                scrub?.let { a.seekTo((it * ui.duration).toLong()) }
                scrub = null
                a.scrubbing(false)
            },
            enabled = ui.duration > 0,
            colors = colors,
            // A slim track with a round thumb reads better over video than the default chunky bar.
            thumb = { Box(Modifier.size(16.dp).clip(CircleShape).background(VdoColors.RoseGold)) },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state, colors = colors, enabled = ui.duration > 0,
                    modifier = Modifier.height(4.dp), drawStopIndicator = null, thumbTrackGapSize = 0.dp,
                )
            },
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        Text(
            if (ui.duration > 0) formatTime(ui.duration) else "LIVE", color = Color.White, fontSize = 12.sp,
            modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
private fun PlayButton(ui: PlayerUi, a: PlayerActions, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(VdoColors.Accent)
            .border(1.5.dp, VdoColors.RoseGold.copy(alpha = 0.8f), CircleShape).clickable(onClick = a.playPause),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (ui.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            if (ui.playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(size * 0.56f),
        )
    }
}

@Composable
private fun Round(icon: ImageVector, label: String, enabled: Boolean = true, scrim: Boolean = true, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(if (scrim) Color(0x59000000) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (enabled) tint else Color(0x55FFFFFF), modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun Tool(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    val tint = if (active) VdoColors.RoseGold else Color.White
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, color = tint, fontSize = 11.sp, maxLines = 1)
    }
}

/** Controls drawn over a playing video. Only the essentials are on screen; the rest is under "More". */
@Composable
fun VideoControls(ui: PlayerUi, a: PlayerActions) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Color(0xB3000000), 0.28f to Color.Transparent,
                0.62f to Color.Transparent, 1f to Color(0xE6000000),
            ),
        ).windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = a.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
            Text(
                ui.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { a.open(Sheet.SUBS) }) { Icon(Icons.Rounded.Subtitles, "Subtitles", tint = Color.White) }
            IconButton(onClick = a.pip) { Icon(Icons.Rounded.PictureInPictureAlt, "Picture in picture", tint = Color.White) }
            IconButton(onClick = { a.open(Sheet.MORE) }) { Icon(Icons.Rounded.MoreVert, "More options", tint = Color.White) }
        }

        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (ui.queueSize > 1) Round(Icons.Rounded.SkipPrevious, "Previous", ui.hasPrev, onClick = a.prev)
            Round(Icons.Rounded.FastRewind, "Rewind") { a.seekBy(-1) }
            PlayButton(ui, a, 72.dp)
            Round(Icons.Rounded.FastForward, "Forward") { a.seekBy(1) }
            if (ui.queueSize > 1) Round(Icons.Rounded.SkipNext, "Next", ui.hasNext, onClick = a.next)
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp)) {
            SeekBar(ui, a)
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Tool(Icons.Rounded.LockOpen, "Lock", onClick = a.lock)
                Tool(Icons.Rounded.Speed, if (ui.speed == 1f) "Speed" else "${ui.speed}x", active = ui.speed != 1f) { a.open(Sheet.SPEED) }
                Tool(Icons.Rounded.AspectRatio, ui.resizeLabel, active = ui.resizeLabel != "Fit", onClick = a.cycleResize)
                Tool(Icons.Rounded.ScreenRotation, "Rotate", onClick = a.rotate)
                if (ui.queueSize > 1) Tool(Icons.AutoMirrored.Rounded.QueueMusic, "Queue") { a.open(Sheet.QUEUE) }
            }
        }
    }
}

/**
 * Bars that move with the music. Levels come from [AudioLevels]; the animation only runs
 * while something is playing (plus a short fade-out), so it costs nothing when paused.
 */
@Composable
fun VisualizerBars(playing: Boolean, modifier: Modifier = Modifier, preview: FloatArray? = null) {
    val shown = remember { FloatArray(AudioLevels.BANDS) }
    var frame by remember { mutableIntStateOf(0) }
    if (preview == null) {
        LaunchedEffect(playing) {
            var idle = 0
            while (playing || idle < 45) {
                withFrameNanos { }
                val target = if (playing) AudioLevels.current() else null
                var any = false
                for (i in shown.indices) {
                    val t = target?.getOrNull(i) ?: 0f
                    // Rise quickly, fall slowly: it reads as "bouncy" rather than jittery.
                    shown[i] += (t - shown[i]) * if (t > shown[i]) 0.5f else 0.14f
                    if (shown[i] > 0.01f) any = true
                }
                idle = if (playing || any) 0 else idle + 1
                frame++
            }
        }
    }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame
        val v = preview ?: shown
        val n = v.size
        val slot = size.width / n
        val w = slot * 0.58f
        val brush = Brush.verticalGradient(listOf(VdoColors.RoseGold, VdoColors.Violet, VdoColors.Indigo))
        for (i in 0 until n) {
            val h = (w + (size.height - w) * v[i].coerceIn(0f, 1f))
            drawRoundRect(
                brush = brush,
                topLeft = androidx.compose.ui.geometry.Offset(i * slot + (slot - w) / 2, (size.height - h) / 2),
                size = androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2, w / 2),
                alpha = 0.35f + 0.65f * v[i].coerceIn(0f, 1f),
            )
        }
    }
}

/** Full-screen layout for music and for videos played as audio. */
@Composable
fun AudioPlayerLayout(ui: PlayerUi, a: PlayerActions) {
    BoxWithConstraints(Modifier.fillMaxSize().background(VdoColors.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val wide = maxWidth > maxHeight

        @Composable
        fun Art(modifier: Modifier) {
            BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
                // Square artwork that fits whichever side is shorter.
                val side = minOf(maxWidth, maxHeight) * 0.86f
                Image(
                    painterResource(R.drawable.vdo_icon), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(side)
                        .clip(RoundedCornerShape(32.dp))
                        .border(1.dp, VdoColors.RoseGold.copy(alpha = 0.45f), RoundedCornerShape(32.dp)),
                )
            }
        }

        // Quick tools sit directly under the artwork, where the thumb already is.
        @Composable
        fun Tools(modifier: Modifier = Modifier) {
            Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Tool(Icons.Rounded.Speed, if (ui.speed == 1f) "Speed" else "${ui.speed}x", active = ui.speed != 1f) { a.open(Sheet.SPEED) }
                Tool(Icons.Rounded.Timer, "Sleep timer", active = ui.sleepActive) { a.open(Sheet.SLEEP) }
                Tool(Icons.AutoMirrored.Rounded.QueueMusic, "Queue") { a.open(Sheet.QUEUE) }
                Tool(Icons.Rounded.GraphicEq, "Sound", active = ui.soundOn) { a.open(Sheet.SOUND) }
                if (ui.audioOnly) Tool(Icons.Rounded.Videocam, "Show video", onClick = a.toggleAudioOnly)
            }
        }

        @Composable
        fun Panel(modifier: Modifier) {
            Column(modifier.padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    ui.title.ifBlank { "Nothing playing" }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                if (ui.artist.isNotBlank()) {
                    Text(
                        ui.artist.replace(Regex("\\s+"), " "), color = VdoColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (ui.visualizer) {
                    VisualizerBars(
                        ui.playing, Modifier.fillMaxWidth().padding(top = 10.dp).height(if (wide) 36.dp else 56.dp), ui.previewLevels,
                    )
                } else Spacer(Modifier.height(12.dp))
                SeekBar(ui, a, Modifier.fillMaxWidth())
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Round(Icons.Rounded.Shuffle, "Shuffle", scrim = false, tint = if (ui.shuffle) VdoColors.RoseGold else Color.White, onClick = a.toggleShuffle)
                    Round(Icons.Rounded.SkipPrevious, "Previous", ui.hasPrev || ui.position > 3000, scrim = false, onClick = a.prev)
                    PlayButton(ui, a, 76.dp)
                    Round(Icons.Rounded.SkipNext, "Next", ui.hasNext, scrim = false, onClick = a.next)
                    Round(
                        if (ui.repeatMode == 1) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", scrim = false,
                        tint = if (ui.repeatMode != 0) VdoColors.RoseGold else Color.White, onClick = a.cycleRepeat,
                    )
                }
            }
        }

        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = a.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                Text(
                    if (ui.audioOnly) "Playing as audio" else "Now playing", color = VdoColors.Muted, fontSize = 13.sp,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(onClick = { a.open(Sheet.MORE) }) { Icon(Icons.Rounded.MoreVert, "More options", tint = Color.White) }
            }
            if (wide) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(0.8f).fillMaxHeight()) {
                        Art(Modifier.weight(1f).fillMaxWidth())
                        Tools(Modifier.padding(bottom = 4.dp))
                    }
                    Panel(Modifier.weight(1.2f))
                }
            } else {
                Art(Modifier.weight(1f).fillMaxWidth())
                Tools(Modifier.padding(bottom = 12.dp))
                Panel(Modifier.fillMaxWidth())
            }
        }
    }
}
