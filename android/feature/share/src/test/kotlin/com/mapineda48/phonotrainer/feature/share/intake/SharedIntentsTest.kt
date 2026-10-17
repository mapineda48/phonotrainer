package com.mapineda48.phonotrainer.feature.share.intake

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The share intent read as untrusted input (architecture 12, 20.2): what is read, how much, and nothing else. */
@RunWith(RobolectricTestRunner::class)
class SharedIntentsTest {
  private fun text(text: CharSequence?, subject: String? = null) = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    text?.let { putExtra(Intent.EXTRA_TEXT, it) }
    subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
  }

  private fun uri(n: Int) = Uri.parse("content://media.test/item/$n")

  @Test
  fun aTextShareGivesItsTextAndSubject() {
    assertThat(SharedIntents.read(text("Great scene https://youtu.be/abc?si=xyz", "Great scene")))
      .isEqualTo(SharedInput.Text("Great scene https://youtu.be/abc?si=xyz", "Great scene"))
  }

  @Test
  fun styledTextIsReadAsPlainText() {
    assertThat(SharedIntents.read(text(SpannableString("https://x.com/a/status/1")))).isEqualTo(
      SharedInput.Text("https://x.com/a/status/1", null),
    )
  }

  @Test
  fun sharedTextAndSubjectAreCutAtEightKibibytes() {
    val long = "a".repeat(SharedIntents.MAX_TEXT_CHARS + 500)
    val read = SharedIntents.read(text(long, long)) as SharedInput.Text

    assertThat(read.text).hasLength(SharedIntents.MAX_TEXT_CHARS)
    assertThat(read.subject).hasLength(SharedIntents.MAX_TEXT_CHARS)
  }

  @Test
  fun aTextShareWithoutExtraTextReadsTheClipData() {
    val intent = text(null).apply { clipData = ClipData.newPlainText("shared", "https://youtu.be/abc") }

    assertThat(SharedIntents.read(intent)).isEqualTo(SharedInput.Text("https://youtu.be/abc", null))
  }

  @Test
  fun anIntentWithNothingOrAnotherActionSharesNoText() {
    assertThat(SharedIntents.read(null)).isEqualTo(SharedInput.Text("", null))
    assertThat(SharedIntents.read(text(null))).isEqualTo(SharedInput.Text("", null))
    val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://youtu.be/abc")).putExtra(Intent.EXTRA_TEXT, "https://youtu.be/abc")
    assertThat(SharedIntents.read(view)).isEqualTo(SharedInput.Text("", null))
  }

  @Test
  fun aSingleMediaShareGivesItsStream() {
    val intent = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri(1))

    assertThat(SharedIntents.read(intent)).isEqualTo(SharedInput.Files(listOf(uri(1).toString()), "video/mp4"))
  }

  @Test
  fun aMultipleShareReadsTheStreamsAndTheClipDataEachItemOnce() {
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/*")
      .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri(1), uri(2)))
    intent.clipData = ClipData(ClipDescription("media", arrayOf("video/*")), ClipData.Item(uri(2))).apply {
      addItem(ClipData.Item(uri(3)))
    }

    assertThat(SharedIntents.read(intent)).isEqualTo(
      SharedInput.Files(listOf(uri(1), uri(2), uri(3)).map(Uri::toString), "video/*"),
    )
  }

  @Test
  fun atMostTenItemsAreReadAndTheRestCounted() {
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("audio/*")
      .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList((1..13).map(::uri)))

    val read = SharedIntents.read(intent) as SharedInput.Files
    assertThat(read.uris).hasSize(SharedIntents.MAX_ITEMS)
    assertThat(read.uris.first()).isEqualTo(uri(1).toString())
    assertThat(read.overLimit).isEqualTo(3)
  }

  @Test
  fun aMediaShareWithoutItemsSharesNothing() {
    assertThat(SharedIntents.read(Intent(Intent.ACTION_SEND).setType("video/mp4")))
      .isEqualTo(SharedInput.Files(emptyList(), "video/mp4"))
  }

  @Test
  fun aStreamOfTheWrongTypeIsIgnored() {
    val intent = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, Bundle())

    assertThat(SharedIntents.read(intent)).isEqualTo(SharedInput.Files(emptyList(), "video/mp4"))
  }

  @Test
  fun aMediaShareIgnoresItsText() {
    val intent = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri(1))
      .putExtra(Intent.EXTRA_TEXT, "https://youtu.be/abc")

    assertThat(SharedIntents.read(intent)).isEqualTo(SharedInput.Files(listOf(uri(1).toString()), "video/mp4"))
  }
}
