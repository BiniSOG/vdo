package com.vdo.app.player

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.vdo.app.data.PlayItem
import com.vdo.app.data.Store
import com.vdo.app.download.Http
import kotlinx.coroutines.flow.MutableStateFlow

/** Entry point the rest of the app uses to start playback. */
object PlayerHub {
    class Request(val items: List<PlayItem>, val index: Int)

    /** Extra HTTP headers (referer, cookies...) for the stream currently being played. */
    @Volatile var headers: Map<String, String> = emptyMap()

    val pending = MutableStateFlow<Request?>(null)

    /** True while the user chose "play as audio" for a video. */
    @Volatile var audioOnly = false

    fun play(ctx: Context, items: List<PlayItem>, index: Int = 0, headers: Map<String, String> = emptyMap()) {
        if (items.isEmpty()) return
        this.headers = headers.filterKeys { !it.equals("Range", true) }
        audioOnly = false
        pending.value = Request(items, index.coerceIn(0, items.lastIndex))
        Store.addRecent(items[index.coerceIn(0, items.lastIndex)])
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun open(ctx: Context) =
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun mimeFor(item: PlayItem): String? = item.mime ?: run {
        val path = item.uri.substringBefore('?').lowercase()
        when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    fun toMediaItem(item: PlayItem): MediaItem {
        val mime = mimeFor(item)
        return MediaItem.Builder()
            .setMediaId(item.uri)
            .setUri(item.uri)
            .setMimeType(mime)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(Uri.parse(item.uri))
                    .setExtras(Bundle().apply { putString("mime", mime) })
                    .build(),
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.subtitle.ifBlank { "VDO" })
                    .setArtworkUri(item.artwork?.let(Uri::parse))
                    .setExtras(Bundle().apply { putBoolean("isAudio", item.isAudio) })
                    .build(),
            )
            .build()
    }
}

/** Stops playback after a delay; lives outside the UI so it survives the player screen closing. */
object SleepTimer {
    private val handler = Handler(Looper.getMainLooper())
    internal var player: Player? = null
    var endsAt by mutableStateOf(0L)
        private set
    private val stop = Runnable { player?.pause(); endsAt = 0 }

    fun set(minutes: Int) {
        handler.removeCallbacks(stop)
        if (minutes <= 0) { endsAt = 0; return }
        endsAt = System.currentTimeMillis() + minutes * 60_000L
        handler.postDelayed(stop, minutes * 60_000L)
    }
}

/** Hosts the single ExoPlayer instance so audio keeps playing in the background. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
        Store.init(this)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val withHeaders = ResolvingDataSource.Factory(http) { spec ->
            val h = PlayerHub.headers
            if (h.isEmpty()) spec else spec.withAdditionalHeaders(h)
        }
        // Same audio pipeline as usual, plus a tap that feeds the visualizer.
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(AudioLevels.processor()))
                .build()
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this).setDataSourceFactory(DefaultDataSource.Factory(this, withHeaders)),
            )
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        AudioFx.attach(player.audioSessionId)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) = AudioFx.attach(audioSessionId)

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) save(player.currentMediaItem?.mediaId, player.currentPosition, player.duration)
            }

            override fun onPositionDiscontinuity(
                old: Player.PositionInfo, new: Player.PositionInfo, reason: Int,
            ) {
                if (old.mediaItemIndex == new.mediaItemIndex) return
                val ended = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                Store.setPosition(old.mediaItem?.mediaId ?: return, if (ended) 0 else old.positionMs)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) player.currentMediaItem?.mediaId?.let { Store.setPosition(it, 0) }
            }
        })
        SleepTimer.player = player

        // Also save every few seconds while playing, so the place is kept even if the app is
        // swiped away, the battery dies or the system stops it without warning.
        val tick = object : Runnable {
            override fun run() {
                if (player.isPlaying) save(player.currentMediaItem?.mediaId, player.currentPosition, player.duration)
                saver.postDelayed(this, 4000)
            }
        }
        saver.postDelayed(tick, 4000)

        val activity = PendingIntent.getActivity(
            this, 0, Intent(this, PlayerActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(activity)
            .setCallback(object : MediaSession.Callback {
                // Controllers can't send URIs across the process boundary, so restore them here.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    mediaItems.map {
                        it.buildUpon()
                            .setUri(it.requestMetadata.mediaUri ?: Uri.parse(it.mediaId))
                            .setMimeType(it.requestMetadata.extras?.getString("mime"))
                            .build()
                    }.toMutableList(),
                )
            })
            .build()
    }

    private val saver = Handler(Looper.getMainLooper())

    private fun saveNow() {
        val p = session?.player ?: return
        if (p.mediaItemCount > 0 && p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE) {
            save(p.currentMediaItem?.mediaId, p.currentPosition, p.duration)
        }
    }

    private fun save(id: String?, pos: Long, duration: Long) {
        if (id == null) return
        val nearEnd = duration > 0 && pos > duration - 5000
        Store.setProgress(id, if (nearEnd) 0 else pos, duration)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveNow()
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        saveNow()
        saver.removeCallbacksAndMessages(null)
        SleepTimer.player = null
        AudioFx.release()
        session?.run { player.release(); release() }
        session = null
        alive = false
        super.onDestroy()
    }

    companion object {
        /** Whether the player service exists, so screens can avoid starting it just to look. */
        var alive by mutableStateOf(false)
            private set
    }
}

/** Connects to [PlaybackService] for as long as the calling composable is on screen. */
@Composable
fun rememberController(): MediaController? {
    val ctx = LocalContext.current.applicationContext
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val future = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java)))
            .buildAsync()
        future.addListener({ controller = runCatching { future.get() }.getOrNull() }, MoreExecutors.directExecutor())
        onDispose {
            MediaController.releaseFuture(future)
            controller = null
        }
    }
    return controller
}
