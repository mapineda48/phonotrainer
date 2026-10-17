package com.mapineda48.phonotrainer.feature.share.intake

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.FileRefusalReason
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * What the sheet asks a sharing app's provider (architecture 8.3, 12, 20.2): only `content://` URIs of other apps are
 * queried, names are cleaned before they are shown, and a type the computer cannot analyze is refused when the sheet
 * opens (SH-AC10).
 */
@RunWith(RobolectricTestRunner::class)
class ContentSharedFilesTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val files = ContentSharedFiles(context)

  @Before
  fun providers() {
    queries.clear()
    Robolectric.buildContentProvider(LyingProvider::class.java).create(OTHER)
    Robolectric.buildContentProvider(LyingProvider::class.java).create("${context.packageName}.files")
  }

  private fun inspect(vararg uris: String) = runBlocking { files.inspect(uris.toList(), "video/*") }

  @Test
  fun anotherAppsItemGivesItsNameSizeAndType() {
    val item = inspect("content://$OTHER/clip.mp4").single()

    assertThat(item.displayName).isEqualTo("clip.mp4")
    assertThat(item.declaredBytes).isEqualTo(48_000_000)
    assertThat(item.mimeType).isEqualTo("video/mp4")
    assertThat(item.kind).isEqualTo(SharedKind.VIDEO)
    assertThat(item.refused).isNull()
    assertThat(item.source().persisted).isFalse()
  }

  @Test
  fun aFileUriAndThisAppsOwnProviderAreRefusedWithoutBeingQueried() {
    val items = inspect("file:///sdcard/clip.mp4", "content://${context.packageName}.files/clip.mp4", "http://x/y")

    assertThat(items.map { it.refused }).containsExactly(
      FileRefusalReason.UNREADABLE,
      FileRefusalReason.OWN_PROVIDER,
      FileRefusalReason.UNREADABLE,
    ).inOrder()
    assertThat(queries).isEmpty()
  }

  @Test
  fun anAmrVoiceNoteIsRefusedAsUnsupported() {
    val item = inspect("content://$OTHER/voice.amr").single()

    assertThat(item.refused).isEqualTo(FileRefusalReason.UNSUPPORTED_TYPE)
    assertThat(item.kind).isEqualTo(SharedKind.AUDIO)
  }

  @Test
  fun aProviderThatLiesAboutTheNameIsShownOnlyItsCleanedBaseName() {
    val item = inspect("content://$OTHER/hostile").single()

    // "../../databases/evil‮gnp.mp4" names a base name with a right-to-left override: the override goes.
    assertThat(item.displayName).isEqualTo("evilgnp.mp4")
    assertThat(item.declaredBytes).isNull()
  }

  @Test
  fun aTypeOnlyTheIntentNamesIsUsedWhenTheProviderNamesNone() {
    val item = runBlocking { files.inspect(listOf("content://$OTHER/untyped"), "audio/mpeg") }.single()

    assertThat(item.mimeType).isEqualTo("audio/mpeg")
    assertThat(item.kind).isEqualTo(SharedKind.AUDIO)
    assertThat(item.refused).isNull()
  }

  class LyingProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
      queries += uri.toString()
      val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
      when (uri.lastPathSegment) {
        "hostile" -> cursor.addRow(arrayOf<Any?>("../../databases/evil‮gnp.mp4", null))
        "untyped" -> cursor.addRow(arrayOf<Any?>("recording", 3_000_000L))
        else -> cursor.addRow(arrayOf<Any?>(uri.lastPathSegment, 48_000_000L))
      }
      return cursor
    }

    override fun getType(uri: Uri): String? = when {
      uri.lastPathSegment == "untyped" -> null
      uri.lastPathSegment.orEmpty().endsWith(".amr") -> "audio/amr"
      else -> "video/mp4"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0
  }

  companion object {
    private const val OTHER = "media.test.provider"
    val queries = mutableListOf<String>()
  }
}
