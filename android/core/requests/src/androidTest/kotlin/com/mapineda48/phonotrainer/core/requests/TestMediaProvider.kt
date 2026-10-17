package com.mapineda48.phonotrainer.core.requests

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Test media for the core instrumented suite: `content://<test package>.media/file/<name>` serves `cacheDir/<name>` (a
 * seekable descriptor); `content://<test package>.media/slow/<bytes>` streams [patternByte]s through a pipe (not
 * seekable), [CHUNK] bytes every [PAUSE_MS] ms, as a slow provider would.
 */
class TestMediaProvider : ContentProvider() {
  override fun onCreate(): Boolean = true

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    val context = checkNotNull(context)
    return when (uri.pathSegments.firstOrNull()) {
      "file" -> ParcelFileDescriptor.open(File(context.cacheDir, uri.lastPathSegment!!), ParcelFileDescriptor.MODE_READ_ONLY)
      "slow" -> slow(uri.pathSegments[1].toLong())
      else -> throw java.io.FileNotFoundException("no such test media")
    }
  }

  private fun slow(size: Long): ParcelFileDescriptor {
    val (read, write) = ParcelFileDescriptor.createPipe()
    thread(name = "slow-media") {
      try {
        ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
          val chunk = ByteArray(CHUNK)
          var sent = 0L
          while (sent < size) {
            val length = minOf(CHUNK.toLong(), size - sent).toInt()
            for (i in 0 until length) chunk[i] = patternByte(sent + i)
            out.write(chunk, 0, length)
            sent += length
            Thread.sleep(PAUSE_MS)
          }
        }
      } catch (_: IOException) {
        // The reader went away (the copy was stopped).
      }
    }
    return read
  }

  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
    val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
    val size = when (uri.pathSegments.firstOrNull()) {
      "file" -> File(checkNotNull(context).cacheDir, uri.lastPathSegment!!).length()
      "slow" -> uri.pathSegments[1].toLong()
      else -> null
    }
    cursor.addRow(arrayOf<Any?>("clip.mp4", size))
    return cursor
  }

  override fun getType(uri: Uri): String = "video/mp4"

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0

  override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0

  companion object {
    const val CHUNK = 64 * 1024
    const val PAUSE_MS = 20L

    fun patternByte(position: Long): Byte = (position % 251).toByte()

    fun fileUri(context: Context, name: String): String = "content://${context.packageName}.media/file/$name"

    fun slowUri(context: Context, bytes: Long): String = "content://${context.packageName}.media/slow/$bytes"
  }
}

/** Stands for the share sheet: the activity whose life a shared item's read grant follows. */
class SheetStandInActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
  }
}
