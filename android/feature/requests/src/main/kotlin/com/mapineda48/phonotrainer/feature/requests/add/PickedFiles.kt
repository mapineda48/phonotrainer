package com.mapineda48.phonotrainer.feature.requests.add

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import androidx.core.net.toUri
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Whether a picked file is a video or an audio file (null when neither its type nor its name says). */
internal enum class PickedKind { VIDEO, AUDIO }

/**
 * A file chosen in the system picker (spec F-19 "Choose a file"), with what its provider says about it: a printable
 * name, its type, size and duration. Provider metadata is untrusted (architecture 8.3): these are hints for the list,
 * and `:core:requests` checks the type, counts the bytes and keeps its own copy name.
 */
@Immutable
internal data class PickedFile(
  val uri: String,
  val name: String?,
  val mimeType: String?,
  val bytes: Long?,
  val durationMs: Long?,
  val kind: PickedKind?,
)

/** Reads what the picker's providers say about [uris] (name, type, size, duration), off the main thread. */
internal fun interface PickedFiles {
  suspend fun inspect(uris: List<String>): List<PickedFile>
}

/**
 * [PickedFiles] over the app's `ContentResolver`. It takes no permission: `:core:requests` keeps the lasting read
 * permission of a picked file itself (`FileSource.persisted`, architecture 8.3) and releases it when the copy is done.
 */
internal class ContentPickedFiles @Inject constructor(@ApplicationContext private val context: Context) : PickedFiles {
  override suspend fun inspect(uris: List<String>): List<PickedFile> = withContext(Dispatchers.IO) {
    uris.map { inspectOne(it) }
  }

  private fun inspectOne(text: String): PickedFile {
    val uri = text.toUri()
    val resolver = context.contentResolver
    var name: String? = null
    var bytes: Long? = null
    runCatching {
      resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
          if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
          if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) bytes = cursor.getLong(sizeColumn).takeIf { it >= 0 }
        }
      }
    }
    val mime = runCatching { resolver.getType(uri) }.getOrNull()
    val clean = PickedNames.clean(name)
    return PickedFile(
      uri = text,
      name = clean,
      mimeType = mime,
      bytes = bytes,
      durationMs = duration(uri),
      kind = PickedNames.kindOf(clean, mime),
    )
  }

  private fun duration(uri: Uri): Long? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(context, uri)
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
    } finally {
      retriever.release()
    }
  }.getOrNull()
}

/** How a picked file's untrusted name and type are read for the list (architecture 8.3, security review M-4). */
internal object PickedNames {
  private const val MAX_CHARS = 200
  private val VIDEO = setOf("webm", "mp4", "mkv", "mov", "avi", "m4v")
  private val AUDIO = setOf("mp3", "wav", "m4a", "flac", "ogg", "opus", "aac")

  /** The base name only, `Cc` and `Cf` characters removed, at most 200 characters; null when nothing is left. */
  fun clean(name: String?): String? {
    val base = name?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
    val out = StringBuilder()
    base.codePoints().forEach { point ->
      val type = Character.getType(point)
      if (type != Character.CONTROL.toInt() && type != Character.FORMAT.toInt()) out.appendCodePoint(point)
    }
    val text = out.toString().trim()
    if (text.isEmpty()) return null
    return if (text.codePointCount(0, text.length) <= MAX_CHARS) text else text.substring(0, text.offsetByCodePoints(0, MAX_CHARS))
  }

  /** Video or audio from the type ("video/…", "audio/…"), else from the name's suffix; null when neither says. */
  fun kindOf(name: String?, mime: String?): PickedKind? {
    val type = mime?.lowercase(Locale.ROOT)
    return when {
      type?.startsWith("video/") == true -> PickedKind.VIDEO
      type?.startsWith("audio/") == true -> PickedKind.AUDIO
      else -> when (name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)) {
        in VIDEO -> PickedKind.VIDEO
        in AUDIO -> PickedKind.AUDIO
        else -> null
      }
    }
  }
}

/** Binds the picker's file facts to the content resolver. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class PickedFilesModule {
  @Binds
  abstract fun pickedFiles(impl: ContentPickedFiles): PickedFiles
}
