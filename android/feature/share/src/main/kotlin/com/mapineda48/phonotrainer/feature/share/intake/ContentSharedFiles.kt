package com.mapineda48.phonotrainer.feature.share.intake

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.provider.OpenableColumns
import com.mapineda48.phonotrainer.core.domain.model.FileRefusalReason
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.components.ViewModelComponent
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [SharedFiles] through the application's content resolver (architecture 8.3, 12, 20.2). Only `content://` URIs of
 * other apps are asked anything: `file://` and every authority of this app are refused unread, so a sharer cannot make
 * the sheet query this app's own providers. Names, types and sizes are taken as hints; the duration is read only from
 * a seekable descriptor (a regular file), never by draining a pipe, and is given up after [DURATION_TIMEOUT_MS].
 */
internal class ContentSharedFiles @Inject constructor(
  @param:ApplicationContext private val context: Context,
) : SharedFiles {
  private val resolver: ContentResolver get() = context.contentResolver

  override suspend fun inspect(uris: List<String>, intentType: String?): List<SharedItem> = withContext(Dispatchers.IO) {
    uris.map { inspectOne(it, intentType) }
  }

  private suspend fun inspectOne(value: String, intentType: String?): SharedItem {
    val uri = runCatching { value.toUri() }.getOrNull()
    if (uri == null || uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrEmpty()) {
      return SharedItem(value, null, null, null, null, null, refused = FileRefusalReason.UNREADABLE)
    }
    if (checkNotNull(uri.authority).split(';').any(::ownsAuthority)) {
      return SharedItem(value, null, null, null, null, null, refused = FileRefusalReason.OWN_PROVIDER)
    }
    var name: String? = null
    var size: Long? = null
    runCatching {
      resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
          if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
          if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn).takeIf { it >= 0 }
        }
      }
    }
    val providerType = runCatching { resolver.getType(uri) }.getOrNull()?.take(MAX_TYPE_CHARS)
    val mime = providerType ?: intentType?.takeUnless { it.endsWith("/*") || it == "*/*" }
    val safeName = SharedMedia.safeName(name)
    val suffix = SharedMedia.suffixOf(safeName, mime)
    val kind = SharedMedia.kindOf(suffix, mime)
    if (suffix == null) {
      return SharedItem(value, safeName, mime, size, null, kind, refused = FileRefusalReason.UNSUPPORTED_TYPE)
    }
    return SharedItem(value, safeName, mime, size, duration(uri), kind)
  }

  private suspend fun duration(uri: Uri): Long? = withTimeoutOrNull(DURATION_TIMEOUT_MS) {
    runCatching {
      resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        // A pipe reports no size: reading its header here would consume what the copy must read.
        if (descriptor.statSize <= 0) return@use null
        val retriever = MediaMetadataRetriever()
        try {
          retriever.setDataSource(descriptor.fileDescriptor)
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
        } finally {
          retriever.release()
        }
      }
    }.getOrNull()
  }

  private fun ownsAuthority(authority: String): Boolean {
    val provider = runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.resolveContentProvider(authority, PackageManager.ComponentInfoFlags.of(0))
      } else {
        @Suppress("DEPRECATION")
        context.packageManager.resolveContentProvider(authority, 0)
      }
    }.getOrNull()
    return provider?.packageName == context.packageName || authority.startsWith(context.packageName)
  }

  private companion object {
    const val DURATION_TIMEOUT_MS = 1_500L
    const val MAX_TYPE_CHARS = 100
  }
}

@Module
@InstallIn(ViewModelComponent::class)
internal interface SharedFilesModule {
  @Binds
  fun sharedFiles(files: ContentSharedFiles): SharedFiles
}
