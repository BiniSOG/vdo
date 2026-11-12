package com.vdo.app.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalMedia(
    val id: Long,
    val uri: Uri,
    val title: String,
    val duration: Long,
    val size: Long,
    val date: Long,
    val folder: String,
    val genre: String = "",
    val artist: String,
    val albumId: Long,
    val isAudio: Boolean,
    val height: Int,
) {
    val artwork: String?
        get() = if (isAudio && albumId > 0) "content://media/external/audio/albumart/$albumId" else null

    fun toPlayItem() = PlayItem(uri.toString(), title, if (isAudio) artist else folder, isAudio, artwork = artwork)
}

object MediaRepository {
    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun hasPermission(ctx: Context) = permissions().any {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun load(ctx: Context, audio: Boolean): List<LocalMedia> = withContext(Dispatchers.IO) {
        val collection =
            if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val cols = buildList {
            add(MediaStore.MediaColumns._ID); add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE); add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATA); add("duration")
            if (audio) {
                add(MediaStore.Audio.Media.TITLE); add(MediaStore.Audio.Media.ARTIST); add(MediaStore.Audio.Media.ALBUM_ID)
                if (Build.VERSION.SDK_INT >= 30) add("genre")
            } else { add(MediaStore.MediaColumns.HEIGHT); add(MediaStore.MediaColumns.WIDTH) }
        }.toTypedArray()
        val out = ArrayList<LocalMedia>()
        runCatching {
            ctx.contentResolver.query(
                collection, cols, if (audio) "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR duration > 30000" else null,
                null, "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                fun col(n: String) = c.getColumnIndex(n)
                while (c.moveToNext()) {
                    val id = c.getLong(col(MediaStore.MediaColumns._ID))
                    val name = c.getString(col(MediaStore.MediaColumns.DISPLAY_NAME)) ?: "Untitled"
                    val path = c.getString(col(MediaStore.MediaColumns.DATA)).orEmpty()
                    val title = if (audio) c.getString(col(MediaStore.Audio.Media.TITLE)) ?: name else name.substringBeforeLast('.')
                    val artist = if (audio) c.getString(col(MediaStore.Audio.Media.ARTIST)).orEmpty() else ""
                    out += LocalMedia(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        title = title,
                        duration = c.getLong(col("duration")),
                        size = c.getLong(col(MediaStore.MediaColumns.SIZE)),
                        date = c.getLong(col(MediaStore.MediaColumns.DATE_ADDED)),
                        folder = path.substringBeforeLast('/', "").substringAfterLast('/').ifEmpty { "Internal storage" },
                        genre = (if (audio && col("genre") >= 0) c.getString(col("genre")) else null)
                            ?.trim().orEmpty().ifEmpty { "Unknown genre" },
                        artist = if (artist == "<unknown>") "Unknown artist" else artist,
                        albumId = if (audio) c.getLong(col(MediaStore.Audio.Media.ALBUM_ID)) else 0,
                        isAudio = audio,
                        // The short side, so an upright 576x1024 clip is labelled 576p.
                        height = if (audio) 0 else {
                            val h = c.getInt(col(MediaStore.MediaColumns.HEIGHT)); val w = c.getInt(col(MediaStore.MediaColumns.WIDTH))
                            if (w > 0 && h > 0) minOf(w, h) else h
                        },
                    )
                }
            }
        }
        out
    }
}
