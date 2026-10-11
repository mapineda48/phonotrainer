package com.mapineda48.phonotrainer.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme

/** The kind of a notice, which picks its icon (design 9.1). */
enum class NoticeKind { INFO, CAUTION, TIP }

/**
 * A notice (design 9.1): a galley well with an 18 dp icon, an optional bold [lead] and an ink2 body. A caution adds a
 * 3 dp ink bar at the start. [live] makes it a polite live region (it is announced when it changes). [action] holds an
 * optional button below the words (errors: "what happened + what to do + a button", design 9.21).
 */
@Composable
fun Notice(
  body: String,
  modifier: Modifier = Modifier,
  kind: NoticeKind = NoticeKind.INFO,
  lead: String? = null,
  live: Boolean = false,
  action: (@Composable () -> Unit)? = null,
) {
  val colors = PhonoTheme.colors
  val icon = when (kind) {
    NoticeKind.INFO -> PhonoIcons.Info
    NoticeKind.CAUTION -> PhonoIcons.Caution
    NoticeKind.TIP -> PhonoIcons.Tip
  }
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(PhonoRadius.medium))
      .background(colors.galley)
      .then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
      .height(IntrinsicSize.Min)
      .heightIn(min = 48.dp),
  ) {
    if (kind == NoticeKind.CAUTION) {
      Box(Modifier.width(3.dp).fillMaxHeight().background(colors.ink))
    }
    Row(
      modifier = Modifier.padding(PhonoSpacing.s12),
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
    ) {
      Icon(painterResource(icon), null, tint = colors.ink, modifier = Modifier.padding(top = 2.dp).size(18.dp))
      Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
        val text = buildAnnotatedString {
          if (lead != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.ink)) { append(lead) }
            append(" ")
          }
          append(body)
        }
        androidx.compose.material3.Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
        action?.invoke()
      }
    }
  }
}

@PreviewLightDark
@Composable
internal fun NoticePreview() {
  PhonoPreviewSurface {
    Notice("Clips you add wait on this phone until it reaches your computer.")
    Notice(
      "This short couldn't be played.",
      kind = NoticeKind.CAUTION,
      action = { PhonoBaseButton("Try again", {}, emphasis = ButtonEmphasis.SECONDARY) },
    )
    Notice("Say the t as a quick tap of the tongue.", kind = NoticeKind.TIP, lead = "Tip.")
  }
}
