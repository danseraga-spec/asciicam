package com.example.asciicam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Saves files into the public gallery folders (Pictures/AsciiCam, Movies/AsciiCam).
 * Android 10+ uses MediaStore without any permission; older versions write the file directly
 * and need WRITE_EXTERNAL_STORAGE.
 */
object GallerySaver {
    private const val FOLDER = "AsciiCam"

    /** Each save returns the gallery Uri of the new item (null if the system didn't report one). */
    fun saveImage(context: Context, bitmap: Bitmap, name: String): Uri? =
        save(context, name, "image/png", isVideo = false) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    fun saveFile(context: Context, src: File, name: String, mime: String, isVideo: Boolean): Uri? =
        save(context, name, mime, isVideo) { out -> src.inputStream().use { it.copyTo(out) } }

    private fun save(context: Context, name: String, mime: String, isVideo: Boolean, write: (OutputStream) -> Unit): Uri? {
        val dirType = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val collection = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dirType/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("Cannot open $uri")
                out.use { write(it) }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(dirType), FOLDER)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
            val file = File(dir, name)
            FileOutputStream(file).use { write(it) }
            // The scanner reports the content Uri asynchronously; we're on a background thread.
            val latch = CountDownLatch(1)
            var result: Uri? = null
            MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf(mime)) { _, uri ->
                result = uri
                latch.countDown()
            }
            latch.await(3, TimeUnit.SECONDS)
            return result
        }
    }
}
