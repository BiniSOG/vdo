package com.vdo.app.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
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
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.vdo.app.R
import com.vdo.app.data.Store
import com.vdo.app.ui.VdoColors
import com.vdo.app.ui.VdoTheme
import com.vdo.app.ui.formatTime
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

class PlayerActivity : ComponentActivity() {
    private var inPip by mutableStateOf(false)
    internal var controller: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent {
            VdoTheme { PlayerScreen(this, inPip) }
        }
    }

    private fun hasVideo(): Boolean = controller?.let(::hasVideoTrack) == true

    fun enterPip() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val size = controller?.videoSize
        val ratio = if (size != null && size.width > 0 && size.height > 0) {
            val r = size.width.toFloat() / size.height
            // Android only accepts ratios between 1:2.39 and 2.39:1.
            if (r in 0.42f..2.39f) Rational(size.width, size.height) else Rational(16, 9)
        } else Rational(16, 9)
        runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Store.autoPip && controller?.isPlaying == true && hasVideo() && !PlayerHub.audioOnly) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        // Audio (and "play as audio") keeps going in the background; plain video pauses unless enabled.
        if (!isChangingConfigurations && hasVideo() && !PlayerHub.audioOnly && !Store.backgroundVideo) {
            controller?.pause()
        }
    }
}

internal fun hasVideoTrack(p: Player): Boolean =
    p.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }

private enum class Drag { SEEK, VOLUME, BRIGHTNESS }

@Composable
private fun PlayerScreen(activity: PlayerActivity, inPip: Boolean) {
    val controller = rememberController()
    val pending by PlayerHub.pending.collectAsState()

    // Bumped on every player event so the UI re-reads player state.
    var version by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var resize by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var scrubbing by remember { mutableStateOf(false) }
    var hud by remember { mutableStateOf<Pair<ImageVector, String>?>(null) }
    var audioOnly by remember { mutableStateOf(PlayerHub.audioOnly) }

    DisposableEffect(controller) {
        activity.controller = controller
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { version++ }
            override fun onPlayerError(e: PlaybackException) {
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach the server. Check your connection."
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused this link. It may have expired."
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "This file no longer exists."
                    PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED -> "This content is DRM-protected."
                    else -> "This media can't be played (${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')})."
                }
            }
            override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) { error = null }
        }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener); activity.controller = null }
    }

    // Load a newly requested playlist as soon as the service connection is ready.
    LaunchedEffect(controller, pending) {
        val c = controller ?: return@LaunchedEffect
        val req = pending ?: return@LaunchedEffect
        PlayerHub.pending.value = null
        error = null
        audioOnly = false
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        val start = if (Store.resumePlayback) Store.position(req.items[req.index].uri) else 0L
        c.setMediaItems(req.items.map(PlayerHub::toMediaItem), req.index, start)
        c.setPlaybackSpeed(1f)
        c.prepare()
        c.play()
    }

    LaunchedEffect(controller) {
        while (true) {
            controller?.let { position = it.currentPosition }
            delay(400)
        }
    }

    val c = controller
    @Suppress("UNUSED_EXPRESSION") version
    val playing = c?.isPlaying == true
    val buffering = c?.playbackState == Player.STATE_BUFFERING
    val duration = c?.duration?.takeIf { it > 0 } ?: 0L
    val meta = c?.mediaMetadata
    val isAudioItem = meta?.extras?.getBoolean("isAudio") == true
    // Until tracks are known, trust what the caller said the item is, so music never flashes a black video frame.
    val tracksKnown = c != null && !c.currentTracks.isEmpty
    val showVideo = !audioOnly && if (tracksKnown) hasVideoTrack(c!!) else !isAudioItem
    val seekStep = Store.seekSeconds * 1000L

    fun setAudioOnly(on: Boolean) {
        audioOnly = on
        PlayerHub.audioOnly = on
        c?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, on).build() }
    }

    val ui = PlayerUi(
        title = meta?.title?.toString().orEmpty(),
        artist = meta?.artist?.toString().orEmpty().takeIf { it != "VDO" }.orEmpty(),
        playing = playing, position = position, duration = duration,
        hasPrev = c?.hasPreviousMediaItem() == true, hasNext = c?.hasNextMediaItem() == true,
        queueSize = c?.mediaItemCount ?: 0,
        speed = c?.playbackParameters?.speed ?: 1f,
        resizeLabel = when (resize) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Crop"
            else -> "Fit"
        },
        repeatMode = c?.repeatMode ?: Player.REPEAT_MODE_OFF,
        shuffle = c?.shuffleModeEnabled == true,
        sleepActive = SleepTimer.endsAt > 0,
        audioOnly = audioOnly,
        soundOn = AudioFx.enabled,
        visualizer = Store.visualizer,
    )
    val actions = PlayerActions(
        back = { activity.finish() },
        playPause = {
            c?.let {
                if (it.playbackState == Player.STATE_ENDED) it.seekTo(0)
                if (it.playbackState == Player.STATE_IDLE) it.prepare()
                if (it.isPlaying) it.pause() else it.play()
            }
        },
        seekBy = { dir -> c?.let { it.seekTo((it.currentPosition + dir * seekStep).coerceAtLeast(0)) } },
        seekTo = { c?.seekTo(it) },
        prev = { c?.seekToPrevious() },
        next = { c?.seekToNextMediaItem() },
        pip = { activity.enterPip() },
        lock = { locked = true; controls = false },
        cycleResize = {
            resize = when (resize) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        rotate = {
            val land = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            activity.requestedOrientation =
                if (land) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        },
        cycleRepeat = {
            c?.repeatMode = when (c?.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        },
        toggleShuffle = { c?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } },
        toggleAudioOnly = { setAudioOnly(!audioOnly) },
        open = { sheet = it },
        scrubbing = { scrubbing = it },
    )

    // Keep the screen awake only while a picture is actually playing.
    DisposableEffect(playing, showVideo) {
        if (playing && showVideo) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    // Auto-hide the controls during video playback.
    LaunchedEffect(controls, playing, sheet, scrubbing, showVideo) {
        if (controls && playing && showVideo && sheet == Sheet.NONE && !scrubbing) {
            delay(3500)
            controls = false
        }
    }
    LaunchedEffect(hud) { if (hud != null) { delay(700); hud = null } }

    val audio = remember { activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    artworkDisplayMode = PlayerView.ARTWORK_DISPLAY_MODE_OFF
                    setKeepContentOnPlayerReset(true)
                }
            },
            update = { view ->
                if (view.player !== c) view.player = c
                view.resizeMode = resize
            },
        )
        if (inPip) return@Box

        if (!showVideo) {
            AudioPlayerLayout(ui, actions)
        } else {
            // --- Gesture layer ---------------------------------------------
            var dragMode by remember { mutableStateOf<Drag?>(null) }
            var dragStartPos by remember { mutableLongStateOf(0L) }
            var dragAccum by remember { mutableFloatStateOf(0f) }
            var dragTarget by remember { mutableLongStateOf(0L) }
            var startLevel by remember { mutableFloatStateOf(0f) }
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(locked, seekStep) {
                        detectTapGestures(
                            onTap = { controls = !controls },
                            onDoubleTap = { o ->
                                if (locked || c == null) return@detectTapGestures
                                when {
                                    o.x < size.width * 0.35f -> {
                                        c.seekTo((c.currentPosition - seekStep).coerceAtLeast(0))
                                        hud = Icons.Rounded.FastRewind to "-${Store.seekSeconds}s"
                                    }
                                    o.x > size.width * 0.65f -> {
                                        c.seekTo(c.currentPosition + seekStep)
                                        hud = Icons.Rounded.FastForward to "+${Store.seekSeconds}s"
                                    }
                                    else -> if (c.isPlaying) c.pause() else c.play()
                                }
                            },
                        )
                    }
                    .pointerInput(locked) {
                        if (locked || !Store.gestures) return@pointerInput
                        var startX = 0f
                        var ignore = false
                        detectDragGestures(
                            onDragStart = { o ->
                                dragMode = null; dragAccum = 0f; startX = o.x
                                // Leave the screen edges to the system's own gestures.
                                ignore = o.y < size.height * 0.1f || o.y > size.height * 0.9f
                                dragStartPos = c?.currentPosition ?: 0
                            },
                            onDragEnd = {
                                if (dragMode == Drag.SEEK) c?.seekTo(dragTarget)
                                dragMode = null
                            },
                            onDragCancel = { dragMode = null },
                        ) { change, amount ->
                            if (ignore || c == null) return@detectDragGestures
                            change.consume()
                            if (dragMode == null) {
                                dragMode = when {
                                    abs(amount.x) > abs(amount.y) -> Drag.SEEK
                                    startX < size.width / 2f -> Drag.BRIGHTNESS
                                    else -> Drag.VOLUME
                                }
                                startLevel = if (dragMode == Drag.VOLUME) {
                                    audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                                        audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                } else {
                                    activity.window.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f
                                }
                            }
                            when (dragMode) {
                                Drag.SEEK -> {
                                    dragAccum += amount.x
                                    // A full-width swipe scrubs about two minutes.
                                    val delta = (dragAccum / size.width * 120_000).toLong()
                                    val max = if (duration > 0) duration else Long.MAX_VALUE
                                    dragTarget = (dragStartPos + delta).coerceIn(0, max)
                                    val sign = if (delta >= 0) "+" else "-"
                                    hud = (if (delta >= 0) Icons.Rounded.FastForward else Icons.Rounded.FastRewind) to
                                        "${formatTime(dragTarget)}  ($sign${formatTime(abs(delta))})"
                                }
                                Drag.VOLUME -> {
                                    dragAccum -= amount.y
                                    val level = (startLevel + dragAccum / (size.height * 0.7f)).coerceIn(0f, 1f)
                                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).roundToInt(), 0)
                                    hud = Icons.Rounded.VolumeUp to "${(level * 100).roundToInt()}%"
                                }
                                Drag.BRIGHTNESS -> {
                                    dragAccum -= amount.y
                                    val level = (startLevel + dragAccum / (size.height * 0.7f)).coerceIn(0.01f, 1f)
                                    activity.window.attributes = activity.window.attributes.apply { screenBrightness = level }
                                    hud = Icons.Rounded.BrightnessMedium to "${(level * 100).roundToInt()}%"
                                }
                                null -> {}
                            }
                        }
                    },
            )

            if (locked) {
                AnimatedVisibility(controls, Modifier.align(Alignment.CenterStart), enter = fadeIn(), exit = fadeOut()) {
                    Row(
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp).clip(RoundedCornerShape(50))
                            .background(Color(0xCC0B1030)).clickable { locked = false; controls = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Lock, null, tint = VdoColors.RoseGold)
                        Spacer(Modifier.width(8.dp))
                        Text("Tap to unlock", color = Color.White)
                    }
                }
            } else {
                AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut()) { VideoControls(ui, actions) }
            }
        }

        if (buffering && error == null) {
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).size(if (showVideo) 96.dp else 40.dp).padding(if (showVideo) 0.dp else 4.dp),
                color = VdoColors.RoseGold, strokeWidth = 3.dp,
            )
        }

        hud?.let { (icon, text) ->
            Row(
                Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 72.dp)
                    .clip(RoundedCornerShape(50)).background(Color(0xCC0B1030)).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = VdoColors.RoseGold, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(text, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }

        error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).padding(32.dp).clip(RoundedCornerShape(20.dp))
                    .background(Color(0xF2151B45)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Can't play this", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text(msg, color = VdoColors.Muted)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Close", color = VdoColors.Muted,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { activity.finish() }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Text(
                        "Try again", color = VdoColors.RoseGold, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { error = null; c?.prepare(); c?.play() }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    if (sheet != Sheet.NONE && c != null) {
        ModalBottomSheet(onDismissRequest = { sheet = Sheet.NONE }, containerColor = VdoColors.Surface) {
            val close = { sheet = Sheet.NONE }
            when (sheet) {
                Sheet.MORE -> {
                    SheetTitle("Options")
                    if (showVideo) {
                        MoreRow(Icons.Rounded.HighQuality, "Video quality") { sheet = Sheet.QUALITY }
                        MoreRow(Icons.Rounded.Subtitles, "Subtitles") { sheet = Sheet.SUBS }
                    }
                    MoreRow(Icons.Rounded.Audiotrack, "Audio track") { sheet = Sheet.AUDIO }
                    MoreRow(Icons.Rounded.GraphicEq, "Sound & equalizer", if (AudioFx.enabled) "On" else "Off") { sheet = Sheet.SOUND }
                    MoreRow(Icons.Rounded.Speed, "Playback speed", if (ui.speed == 1f) "Normal" else "${ui.speed}x") { sheet = Sheet.SPEED }
                    if (!isAudioItem) {
                        MoreRow(
                            Icons.Rounded.Headphones, if (audioOnly) "Show the video again" else "Play as audio",
                            if (audioOnly) null else "Keeps playing with the screen off",
                        ) { setAudioOnly(!audioOnly); close() }
                    }
                    if (showVideo) {
                        MoreRow(
                            if (ui.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat",
                            when (ui.repeatMode) { Player.REPEAT_MODE_ONE -> "This one"; Player.REPEAT_MODE_ALL -> "All"; else -> "Off" },
                            onClick = actions.cycleRepeat,
                        )
                        if (ui.queueSize > 1) MoreRow(Icons.Rounded.Shuffle, "Shuffle", if (ui.shuffle) "On" else "Off", onClick = actions.toggleShuffle)
                    }
                    MoreRow(Icons.Rounded.Timer, "Sleep timer", if (ui.sleepActive) "On" else "Off") { sheet = Sheet.SLEEP }
                }
                Sheet.SPEED -> {
                    SheetTitle("Playback speed")
                    val current = c.playbackParameters.speed
                    listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f).forEach { s ->
                        Option(if (s == 1f) "Normal" else "${s}x", current == s) { c.setPlaybackSpeed(s); close() }
                    }
                }
                Sheet.SLEEP -> {
                    SheetTitle("Sleep timer")
                    val left = SleepTimer.endsAt - System.currentTimeMillis()
                    if (left > 0) Option("Turn off (${left / 60000 + 1} min left)", false) { SleepTimer.set(0); close() }
                    listOf(5, 10, 15, 30, 45, 60, 90).forEach { m ->
                        Option("$m minutes", false) { SleepTimer.set(m); close() }
                    }
                }
                Sheet.QUEUE -> {
                    SheetTitle("Up next")
                    LazyColumn(Modifier.height(360.dp)) {
                        itemsIndexed((0 until c.mediaItemCount).map { c.getMediaItemAt(it) }) { i, item ->
                            Option(item.mediaMetadata.title?.toString() ?: "Item ${i + 1}", i == c.currentMediaItemIndex) {
                                c.seekTo(i, 0); c.play(); close()
                            }
                        }
                    }
                }
                Sheet.SOUND -> SoundSheet()
                Sheet.AUDIO -> TrackSheet(c, C.TRACK_TYPE_AUDIO, "Audio track", close)
                Sheet.SUBS -> TrackSheet(c, C.TRACK_TYPE_TEXT, "Subtitles", close)
                Sheet.QUALITY -> TrackSheet(c, C.TRACK_TYPE_VIDEO, "Video quality", close)
                Sheet.NONE -> {}
            }
            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }
}

@Composable
private fun MoreRow(icon: ImageVector, label: String, value: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = VdoColors.RoseGold, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        if (value != null) Text(value, color = VdoColors.Muted, fontSize = 13.sp)
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

@Composable
private fun Option(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) VdoColors.RoseGold else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (selected) Icon(Icons.Rounded.Check, null, tint = VdoColors.RoseGold)
    }
}

private fun describe(f: Format, type: Int): String = when (type) {
    C.TRACK_TYPE_VIDEO -> buildString {
        val shortSide = if (f.width > 0 && f.height > 0) minOf(f.width, f.height) else f.height
        append(if (shortSide > 0) "${shortSide}p" else "Video")
        if (f.bitrate > 0) append(" · ${f.bitrate / 1000} kbps")
    }
    else -> listOfNotNull(
        f.label,
        f.language?.takeIf { it != "und" }?.let { java.util.Locale(it).displayLanguage },
        if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) {
            when (f.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; else -> "${f.channelCount} ch" }
        } else null,
    ).distinct().joinToString(" · ").ifEmpty { "Track" }
}

@Composable
private fun TrackSheet(c: Player, type: Int, title: String, close: () -> Unit) {
    SheetTitle(title)
    val groups: List<Tracks.Group> = c.currentTracks.groups.filter { it.type == type }
    val params = c.trackSelectionParameters
    val disabled = params.disabledTrackTypes.contains(type)
    val hasOverride = params.overrides.values.any { it.type == type }
    if (groups.isEmpty()) {
        Text("Nothing to choose from for this media.", color = VdoColors.Muted, modifier = Modifier.padding(24.dp))
        return
    }
    if (type == C.TRACK_TYPE_TEXT) {
        Option("Off", disabled) {
            c.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(type, true).build(); close()
        }
    }
    Option("Auto", !disabled && !hasOverride) {
        c.trackSelectionParameters = params.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, false).build()
        close()
    }
    groups.forEach { g ->
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            Option(describe(g.getTrackFormat(i), type), hasOverride && !disabled && g.isTrackSelected(i)) {
                c.trackSelectionParameters = params.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                    .setTrackTypeDisabled(type, false).build()
                close()
            }
        }
    }
}


/** Equalizer, bass, surround and volume boost. Changes are heard immediately and remembered. */
@Composable
fun SoundSheet() {
    var visualizer by remember { mutableStateOf(Store.visualizer) }
    Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sound", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Equalizer, bass and volume boost", color = VdoColors.Muted, fontSize = 12.sp)
            }
            Switch(checked = AudioFx.enabled, onCheckedChange = { AudioFx.setOn(it) }, enabled = AudioFx.available)
        }
        if (!AudioFx.available) {
            Text(
                "Start playing something and the sound controls will appear here.",
                color = VdoColors.Muted, modifier = Modifier.padding(24.dp),
            )
            return@Column
        }

        if (AudioFx.presets.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(AudioFx.enabled && AudioFx.preset == -1, onClick = { AudioFx.setBand(0, AudioFx.levels.getOrElse(0) { 0 }) }, label = { Text("Custom") })
                AudioFx.presets.forEachIndexed { i, name ->
                    FilterChip(AudioFx.enabled && AudioFx.preset == i, onClick = { AudioFx.choosePreset(i) }, label = { Text(name) })
                }
            }
        }

        AudioFx.bands.forEachIndexed { i, band ->
            val level = AudioFx.levels.getOrElse(i) { 0 }
            FxSlider(
                label = if (band.centerHz >= 1000) "${band.centerHz / 1000} kHz" else "${band.centerHz} Hz",
                value = level.toFloat(), range = band.min.toFloat()..band.max.toFloat(),
                valueText = (if (level > 0) "+" else "") + "${level / 100} dB",
            ) { AudioFx.setBand(i, it.toInt()) }
        }

        FxSlider("Bass boost", AudioFx.bassStrength.toFloat(), 0f..1000f, "${AudioFx.bassStrength / 10}%") { AudioFx.changeBass(it.toInt()) }
        FxSlider("Surround", AudioFx.surround.toFloat(), 0f..1000f, "${AudioFx.surround / 10}%") { AudioFx.changeSurround(it.toInt()) }
        FxSlider("Volume boost", AudioFx.boost.toFloat(), 0f..2000f, "+${AudioFx.boost / 100} dB") { AudioFx.changeBoost(it.toInt()) }
        if (AudioFx.boost > 1200) {
            Text(
                "High boost can distort the sound and is hard on small speakers.",
                color = VdoColors.RoseGold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 24.dp),
            )
        }

        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Music visualizer", modifier = Modifier.weight(1f))
            Switch(checked = visualizer, onCheckedChange = { visualizer = it; Store.visualizer = it })
        }
        TextButton(onClick = { AudioFx.reset() }, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("Reset sound", color = VdoColors.RoseGold)
        }
    }
}

@Composable
private fun FxSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(96.dp), fontSize = 14.sp)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = VdoColors.RoseGold, activeTrackColor = VdoColors.Violet),
            modifier = Modifier.weight(1f),
        )
        Text(valueText, color = VdoColors.Muted, fontSize = 12.sp, modifier = Modifier.width(52.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
