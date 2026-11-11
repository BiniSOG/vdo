package com.vdo.app.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.vdo.app.data.DownloadTask
import java.io.File
import java.io.IOException

/** Moves finished files to their final home: the device gallery, or VDO's private folder. */
object Exporter {
    fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").replace(Regex("\\s+"), " ").trim().take(120)
            .ifEmpty { "VDO download" }

    fun mimeFor(ext: String, isAudio: Boolean): String = when (ext.lowercase()) {
        "ts" -> "video/mp2t"
        "mkv" -> "video/x-matroska"
        "m4a" -> "audio/mp4"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
            ?: if (isAudio) "audio/*" else "application/octet-stream"
    }

    fun privateDir(ctx: Context) = File(ctx.filesDir, "private").apply { mkdirs() }

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 1
        while (f.exists()) {
            f = File(dir, "${name.substringBeforeLast('.')} ($i).${name.substringAfterLast('.', "")}")
            i++
        }
        return f
    }

    /** Returns the content URI or file path of the stored file. The source file is consumed. */
    fun export(ctx: Context, file: File, title: String, mime: String, private: Boolean): String {
        val ext = file.extension.ifEmpty { "bin" }
        val name = sanitize(title) + "." + ext
        if (private) {
            val dest = unique(privateDir(ctx), name)
            move(file, dest)
            return dest.path
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val (collection, folder) = when {
                mime.startsWith("video") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                    Environment.DIRECTORY_MOVIES
                mime.startsWith("audio") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                    Environment.DIRECTORY_MUSIC
                else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                    Environment.DIRECTORY_DOWNLOADS
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/VDO")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(collection, values) ?: throw IOException("Could not create file in gallery")
            try {
                resolver.openOutputStream(uri)!!.use { os -> file.inputStream().use { it.copyTo(os, 1 shl 16) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            file.delete()
            return uri.toString()
        }
        // Android 8/9: keep it in the app's own external folder (no storage permission needed).
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "VDO").apply { mkdirs() }
        val dest = unique(dir, name)
        move(file, dest)
        return dest.path
    }

    private fun move(src: File, dest: File) {
        if (!src.renameTo(dest)) {
            src.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            src.delete()
        }
    }

    fun delete(ctx: Context, output: String?) {
        if (output == null) return
        runCatching {
            if (output.startsWith("content://")) ctx.contentResolver.delete(Uri.parse(output), null, null)
            else File(output).delete()
        }
    }

    fun exists(ctx: Context, output: String?): Boolean {
        if (output == null) return false
        return if (output.startsWith("content://")) {
            runCatching { ctx.contentResolver.openAssetFileDescriptor(Uri.parse(output), "r")?.close() }.isSuccess
        } else File(output).exists()
    }

    /** URI the player can open. */
    fun playUri(output: String): String =
        if (output.startsWith("content://")) output else Uri.fromFile(File(output)).toString()

    /** URI another app can open. */
    fun shareUri(ctx: Context, output: String): Uri =
        if (output.startsWith("content://")) Uri.parse(output)
        else FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(output))

    fun share(ctx: Context, task: DownloadTask) {
        val out = task.output ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = task.mime ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, shareUri(ctx, out))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Moves a finished download between the gallery and the private folder. Returns the new location. */
    fun relocate(ctx: Context, task: DownloadTask, toPrivate: Boolean): String {
        val out = task.output ?: throw IOException("File missing")
        val ext = when {
            !out.startsWith("content://") -> File(out).extension
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(task.mime ?: "")
                ?: if (task.isAudio) "m4a" else "mp4"
        }
        val tmpDir = File(ctx.cacheDir, "move").apply { mkdirs() }
        val tmp = File(tmpDir, "${task.id}.$ext")
        if (out.startsWith("content://")) {
            ctx.contentResolver.openInputStream(Uri.parse(out))!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
        } else {
            File(out).inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
        }
        val result = export(ctx, tmp, task.title, task.mime ?: mimeFor(ext, task.isAudio), toPrivate)
        delete(ctx, out)
        return result
    }
}
