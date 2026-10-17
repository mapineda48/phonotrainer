package com.mapineda48.phonotrainer.core.requests.android

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.mapineda48.phonotrainer.core.requests.intake.ExtractorInput
import com.mapineda48.phonotrainer.core.requests.intake.SourceFacts
import com.mapineda48.phonotrainer.core.requests.intake.SourceReader
import com.mapineda48.phonotrainer.core.requests.intake.SourceStream
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject

/**
 * Shared and picked items through the application's `ContentResolver` (architecture 8.3, 20.2). Only `content://`
 * URIs of other apps are read: `file://` is refused (Android review L8), and so is any authority that belongs to this
 * app, so a sharer cannot make it read its own private providers (security review M-4). What a provider reports is
 * untrusted and only ever a hint.
 */
internal class ContentSourceReader @Inject constructor(
  @param:ApplicationContext private val context: Context,
) : SourceReader {
  private val resolver: ContentResolver get() = context.contentResolver

  override fun refuses(uri: String): Boolean {
    val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return true
    if (parsed.scheme != ContentResolver.SCHEME_CONTENT) return true
    val authority = parsed.authority?.takeIf { it.isNotEmpty() } ?: return true
    // A content URI may name several authorities ("a;b"); every one must belong to another app.
    return authority.split(';').any { ownsAuthority(it) }
  }

  private fun ownsAuthority(authority: String): Boolean {
    val provider = runCatching { context.packageManager.resolveContentProvider(authority, 0) }.getOrNull()
    return provider?.packageName == context.packageName || authority.startsWith(context.packageName)
  }

  override fun facts(uri: String): SourceFacts {
    val parsed = Uri.parse(uri)
    var name: String? = null
    var size: Long? = null
    runCatching {
      resolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
          if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
          if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn).takeIf { it >= 0 }
        }
      }
    }
    val mime = runCatching { resolver.getType(parsed) }.getOrNull()
    return SourceFacts(name, size, mime)
  }

  /** Opens a URI that [refuses] accepted when its request was made (FileIntake checks before any request exists). */
  override fun open(uri: String): SourceStream {
    val descriptor = resolver.openFileDescriptor(Uri.parse(uri), "r")
      ?: throw FileNotFoundException("no descriptor")
    return DescriptorStream(descriptor)
  }

  override fun keepReadable(uri: String): Boolean = runCatching {
    resolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }.isSuccess

  override fun release(uri: String) {
    runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
  }
}

/** A provider's descriptor: seekable when it is a regular file, a pipe otherwise. */
internal class DescriptorStream(private val descriptor: ParcelFileDescriptor) : SourceStream {
  private val input = FileInputStream(descriptor.fileDescriptor)
  private var consumed = false

  override val seekable: Boolean = descriptor.statSize >= 0 && runCatching { input.channel.position() }.isSuccess

  override fun rewind(): Boolean {
    if (!seekable) return !consumed
    // Always seek: an extractor reading the same descriptor moves its offset without this stream knowing.
    return runCatching { input.channel.position(0) }.isSuccess.also { if (it) consumed = false }
  }

  override fun stream(): InputStream {
    consumed = true
    // Closing what the copy reads never closes the descriptor, which the registry owns.
    return object : FilterInputStream(input) {
      override fun close() = Unit
    }
  }

  override fun extractorInput(): ExtractorInput? {
    if (!seekable) return null
    consumed = true
    return ExtractorInput.Descriptor(descriptor.fileDescriptor, descriptor.statSize)
  }

  override fun close() {
    try {
      input.close()
    } catch (_: IOException) {
      // already closed
    }
    try {
      descriptor.close()
    } catch (_: IOException) {
      // already closed
    }
  }
}
