package com.mapineda48.phonotrainer.core.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoFonts
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.ui.ipa.IpaReading
import com.mapineda48.phonotrainer.core.ui.ipa.LocalIpaReading

/**
 * The parts of a text that holds IPA (design 4.3): `/dictionary/` and `[what was said]` segments, bare IPA words (a
 * word with an IPA-only letter, such as "pɪ‿kɪ‿ɾʌp"), the arrows between forms, and the prose around them.
 */
object IpaSegments {
  sealed interface Part {
    val text: String
  }

  @Immutable data class Prose(override val text: String) : Part

  /** IPA, with its delimiters when it had them; [inner] is the transcription alone. */
  @Immutable data class Ipa(override val text: String, val inner: String) : Part

  @Immutable data class Arrow(override val text: String) : Part

  private val delimited = Regex("""(?<![\p{L}\p{N}])/[^/\n]+?/(?![\p{L}\p{N}])|\[[^\]\n]+]""")

  /** [text] cut into parts, in order; adjacent prose is merged. [whole] reads all of it as one transcription. */
  fun parse(text: String, whole: Boolean = false): List<Part> {
    if (whole) return if (text.isEmpty()) emptyList() else listOf(Ipa(text, text.trim().removeSurrounding("/").removeSurrounding("[", "]")))
    val out = ArrayList<Part>()
    var last = 0
    for (m in delimited.findAll(text)) {
      if (m.range.first > last) splitWords(text.substring(last, m.range.first), out)
      out += Ipa(m.value, m.value.substring(1, m.value.length - 1))
      last = m.range.last + 1
    }
    if (last < text.length) splitWords(text.substring(last), out)
    return merge(out)
  }

  /** True when [parts] hold nothing but IPA, arrows and white space: the whole string is then set in Charis. */
  fun isAllIpa(parts: List<Part>): Boolean =
    parts.any { it is Ipa } && parts.all { it is Ipa || it is Arrow || it.text.isBlank() }

  /** A letter that only IPA uses (IPA Extensions, modifier letters, combining marks, phonetic extensions, ‿, …). */
  fun isIpaLetter(char: Char): Boolean = char in 'ɐ'..'ͯ' || char in 'ᴀ'..'᷿' ||
    char in "æðøŋœθβχ‿ⁿ"

  private fun splitWords(chunk: String, out: MutableList<Part>) {
    val word = StringBuilder()
    fun flush() {
      if (word.isEmpty()) return
      val w = word.toString()
      out += if (w.any(::isIpaLetter)) Ipa(w, w) else Prose(w)
      word.setLength(0)
    }
    for (c in chunk) {
      when {
        c in '←'..'↔' -> {
          flush()
          out += Arrow(c.toString())
        }
        c.isWhitespace() -> {
          flush()
          out += Prose(c.toString())
        }
        else -> word.append(c)
      }
    }
    flush()
  }

  private fun merge(parts: List<Part>): List<Part> {
    val out = ArrayList<Part>()
    for (p in parts) {
      val prev = out.lastOrNull()
      if (p is Prose && prev is Prose) out[out.lastIndex] = Prose(prev.text + p.text) else out += p
    }
    return out
  }

  /**
   * What TalkBack reads (A-05): each IPA part as written followed by its symbols' names ("[ɾ]: voiced alveolar
   * tap"), arrows read as [to], prose as it is. Null when the text holds no IPA or no symbol has a name.
   */
  fun spoken(parts: List<Part>, reading: IpaReading, to: String, format: (String, String) -> String): String? {
    if (parts.none { it is Ipa }) return null
    var named = false
    val text = buildString {
      for (p in parts) {
        when (p) {
          is Prose -> append(p.text)
          is Arrow -> append(to)
          is Ipa -> {
            val names = reading.split(p.inner).map { symbol -> reading.nameOf(symbol)?.also { named = true } ?: symbol }
            append(format(p.text, names.joinToString(", ")))
          }
        }
      }
    }
    return text.replace(Regex(" {2,}"), " ").takeIf { named }
  }
}

/**
 * Text with IPA (design 4.3, architecture 18): IPA is always set in Charis for the whole transcription, never with
 * per-glyph fallback (combining marks would misplace). A string that is all IPA (`/ˈwɔtɚ/ → [ˈwɔɾɚ]`) is set in
 * Charis at the size of [style]; IPA inside prose is a Charis span at 1.12 times the surrounding size. The accessible
 * text names the symbols ("[ɾ]: voiced alveolar tap", A-05) through [LocalIpaReading]; [onPlay] adds a "Play this
 * sound" action where the sound can be played. [transcription] says the whole text is IPA, for symbols that use
 * only Latin letters ("t", "ʃ" alike: the IPA chart, sound cards); otherwise IPA is found by its delimiters and letters.
 */
@Composable
fun IpaText(
  text: String,
  modifier: Modifier = Modifier,
  style: TextStyle = LocalTextStyle.current,
  color: Color = Color.Unspecified,
  onPlay: (() -> Unit)? = null,
  transcription: Boolean = false,
) {
  val reading = LocalIpaReading.current
  val parts = remember(text, transcription) { IpaSegments.parse(text, transcription) }
  val whole = IpaSegments.isAllIpa(parts)
  val inline = PhonoTheme.type.ipaInline
  val annotated = remember(parts, whole, inline) { annotate(parts, whole, inline) }
  val to = stringResource(R.string.ui_ipa_to)
  val symbolsFormat = stringResource(R.string.ui_ipa_symbols)
  val spoken = remember(parts, reading, to, symbolsFormat) {
    IpaSegments.spoken(parts, reading, " $to ") { written, names -> symbolsFormat.format(written, names) }
  }
  val playLabel = stringResource(R.string.ui_ipa_play)
  Text(
    text = annotated,
    style = if (whole) style.copy(fontFamily = PhonoFonts.Charis) else style,
    color = if (color == Color.Unspecified) PhonoTheme.colors.ink else color,
    modifier = modifier.semantics {
      if (spoken != null) contentDescription = spoken
      if (onPlay != null) customActions = listOf(CustomAccessibilityAction(playLabel) { onPlay(); true })
    },
  )
}

private fun annotate(parts: List<IpaSegments.Part>, whole: Boolean, inline: SpanStyle): AnnotatedString =
  buildAnnotatedString {
    val charis = SpanStyle(fontFamily = PhonoFonts.Charis)
    for (p in parts) {
      val start = length
      append(p.text)
      when {
        whole -> Unit
        p is IpaSegments.Ipa -> addStyle(inline, start, length)
        p is IpaSegments.Arrow -> addStyle(charis, start, length)
      }
    }
  }

@PreviewLightDark
@Composable
internal fun IpaTextPreview() {
  PhonoPreviewSurface {
    IpaText("/ˈwɔtɚ/ → [ˈwɔɾɚ]", style = PhonoTheme.type.ipaSlip)
    IpaText("The schwa /ə/ is the most common sound of English.")
    IpaText("pick it up → pɪ‿kɪ‿ɾʌp")
  }
}
