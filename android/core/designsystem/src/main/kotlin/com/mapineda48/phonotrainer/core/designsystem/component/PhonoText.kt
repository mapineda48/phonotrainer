package com.mapineda48.phonotrainer.core.designsystem.component

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoFonts

/**
 * The glyph rule of design 4.1: Atkinson Hyperlegible Next and Newsreader have no arrows, so every U+2190 to U+2194
 * inside UI copy is set in Charis at the same size and weight. The string itself is unchanged, so TalkBack reads
 * what it read before. Trailing arrows, chevrons, checks and crosses of controls are icons, never text.
 */
object GlyphRule {
  /** The arrows ←, ↑, →, ↓, ↔ (U+2190 to U+2194). */
  fun isArrow(char: Char): Boolean = char in '←'..'↔'

  /** [text] with each arrow wrapped in a Charis span; [text] as it is when it has none. */
  fun annotate(text: String): AnnotatedString {
    if (text.none(::isArrow)) return AnnotatedString(text)
    val charis = SpanStyle(fontFamily = PhonoFonts.Charis)
    return buildAnnotatedString {
      append(text)
      text.forEachIndexed { i, c -> if (isArrow(c)) addStyle(charis, i, i + 1) }
    }
  }
}

/**
 * The `Text` every feature uses for UI copy (design 4.1): [GlyphRule] applied once per string. IPA goes through
 * `IpaText` (`:core:ui`) instead.
 */
@Composable
fun PhonoText(
  text: String,
  modifier: Modifier = Modifier,
  style: TextStyle = LocalTextStyle.current,
  color: Color = Color.Unspecified,
  textAlign: TextAlign? = null,
  maxLines: Int = Int.MAX_VALUE,
  overflow: TextOverflow = TextOverflow.Clip,
) {
  val annotated = remember(text) { GlyphRule.annotate(text) }
  Text(
    text = annotated,
    modifier = modifier,
    style = style,
    color = color,
    textAlign = textAlign,
    maxLines = maxLines,
    overflow = overflow,
  )
}

@PreviewLightDark
@Composable
private fun PhonoTextPreview() {
  PhonoPreviewSurface {
    PhonoText("Sounds change between words.", style = MaterialTheme.typography.bodyLarge)
    PhonoText("want to → wanna", style = MaterialTheme.typography.titleMedium)
    PhonoText("Listen → repeat → compare", style = MaterialTheme.typography.labelLarge)
  }
}
