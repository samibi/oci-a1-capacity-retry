package com.pix2doc

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

object Storage {
    const val SUBDIR = "Pix2Doc"
    val defaultFolderLabel: String get() = Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR

    /** Saves [src] to the chosen folder (SAF tree) or to Download/Pix2Doc. Returns the new Uri. */
    fun saveToDefault(ctx: Context, src: File, displayName: String): Uri {
        val tree = Prefs.folderUri(ctx)
        if (tree != null) {
            try {
                val dir = DocumentFile.fromTreeUri(ctx, tree)
                if (dir != null && dir.canWrite()) {
                    val f = dir.createFile("application/pdf", displayName.removeSuffix(".pdf"))
                    if (f != null) {
                        copy(ctx, src, f.uri)
                        return f.uri
                    }
                }
            } catch (e: Exception) {
                // folder no longer accessible: fall back to Downloads
            }
        }
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, defaultFolderLabel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create file in Downloads")
        try {
            copy(ctx, src, uri)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    fun copy(ctx: Context, src: File, dest: Uri) {
        val out = ctx.contentResolver.openOutputStream(dest, "wt")
            ?: throw IOException("Cannot write $dest")
        out.use { o -> src.inputStream().use { it.copyTo(o) } }
    }

    fun copyToFile(ctx: Context, src: Uri, dest: File) {
        dest.parentFile?.mkdirs()
        val input = ctx.contentResolver.openInputStream(src) ?: throw IOException("Cannot read $src")
        input.use { i -> dest.outputStream().use { i.copyTo(it) } }
    }

    fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')

    fun folderLabel(ctx: Context): String {
        val tree = Prefs.folderUri(ctx) ?: return defaultFolderLabel
        return DocumentFile.fromTreeUri(ctx, tree)?.name ?: tree.lastPathSegment ?: defaultFolderLabel
    }

    fun cacheDir(ctx: Context, sub: String): File = File(ctx.cacheDir, sub).apply { mkdirs() }
}
