package com.mapineda48.phonotrainer.feature.share.intake

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * What a share intent brought, read once and bounded (architecture 12, 20.2): input is untrusted, so only
 * `EXTRA_TEXT` and `EXTRA_SUBJECT` (each cut at [MAX_TEXT_CHARS]), `EXTRA_STREAM` and the intent's `ClipData` URIs are
 * read, at most [MAX_ITEMS] items; nothing else of the intent is used, forwarded or opened.
 */
sealed interface SharedInput {
  /** A text share (`text/plain`): the first link is looked for in [text], then in [subject] (spec 8.2). */
  data class Text(val text: String, val subject: String?) : SharedInput

  /**
   * A media share (a video or audio type, one item or several): [uris] in the order shared, each once, at most
   * [MAX_ITEMS]; [overLimit] items beyond them were not read; [type] is the intent's own type, a hint only.
   */
  data class Files(val uris: List<String>, val type: String?, val overLimit: Int = 0) : SharedInput
}

/** Reads a share intent into a [SharedInput] (architecture 12). Never throws: an unreadable intent shares nothing. */
object SharedIntents {
  /** Shared text is cut here (architecture 12: 8 KiB). */
  const val MAX_TEXT_CHARS: Int = 8 * 1024

  /** A multiple share takes at most this many items, one request each (spec 8.1). */
  const val MAX_ITEMS: Int = 10

  fun read(intent: Intent?): SharedInput {
    if (intent == null) return SharedInput.Text("", null)
    val type = intent.type?.take(MAX_TYPE_CHARS)
    val media = intent.action == Intent.ACTION_SEND_MULTIPLE ||
      (intent.action == Intent.ACTION_SEND && type != null && !type.startsWith(TEXT_PREFIX, ignoreCase = true))
    return try {
      if (media) files(intent, type) else text(intent)
    } catch (malformed: RuntimeException) {
      // A sender can put anything in the extras (a bad parcel throws when the bundle is first read): nothing shared.
      if (media) SharedInput.Files(emptyList(), type) else SharedInput.Text("", null)
    }
  }

  private fun text(intent: Intent): SharedInput.Text {
    if (intent.action != Intent.ACTION_SEND) return SharedInput.Text("", null)
    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
      ?: intent.clipData?.firstText()
    val subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()
    return SharedInput.Text(text.orEmpty().take(MAX_TEXT_CHARS), subject?.take(MAX_TEXT_CHARS)?.takeIf { it.isNotBlank() })
  }

  private fun files(intent: Intent, type: String?): SharedInput.Files {
    val found = LinkedHashSet<String>()
    var seen = 0
    fun add(uri: Uri?) {
      val value = uri?.toString()?.takeIf { it.isNotEmpty() && it.length <= MAX_URI_CHARS } ?: return
      if (value in found) return
      seen++
      if (found.size < MAX_ITEMS) found += value
    }
    when (intent.action) {
      Intent.ACTION_SEND -> add(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
      Intent.ACTION_SEND_MULTIPLE ->
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.forEach(::add)
    }
    // The ClipData carries the read grants of a multiple share (architecture 8.3); an item named in both is one item.
    intent.clipData?.let { clip ->
      for (index in 0 until minOf(clip.itemCount, MAX_CLIP_ITEMS)) add(clip.getItemAt(index)?.uri)
    }
    return SharedInput.Files(found.toList(), type, overLimit = seen - found.size)
  }

  private fun ClipData.firstText(): String? {
    for (index in 0 until minOf(itemCount, MAX_CLIP_ITEMS)) {
      getItemAt(index)?.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
  }

  private const val TEXT_PREFIX = "text/"
  private const val MAX_TYPE_CHARS = 100
  private const val MAX_URI_CHARS = 4096

  /** A bound on the `ClipData` items looked at, whatever a sender puts there. */
  private const val MAX_CLIP_ITEMS = 100
}
