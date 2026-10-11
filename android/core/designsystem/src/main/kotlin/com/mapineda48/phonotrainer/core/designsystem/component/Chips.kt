package com.mapineda48.phonotrainer.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme

/** The look of a chip (design 9.1). */
enum class ChipKind {
  /** Sheet fill, lineStrong ring. */
  OUTLINE,

  /** Galley fill ("On this phone" in the feed header). */
  MUTED,

  /** Ink fill and onInk words, with a check: selected. */
  SOLID,

  /** "Not in your clips yet": inkMuted words and a hairline ring. */
  ZERO,
}

/**
 * A chip (design 9.1): a pill at least 32 dp tall (a 48 dp target when it acts), labelMedium words and an optional
 * icon. [onRemove] adds the trailing cross of a removable filter ("Only: flapping ✕"), a separate 48 dp action named
 * [removeLabel].
 */
@Composable
fun PhonoChip(
  text: String,
  modifier: Modifier = Modifier,
  kind: ChipKind = ChipKind.OUTLINE,
  @DrawableRes icon: Int? = null,
  onClick: (() -> Unit)? = null,
  onRemove: (() -> Unit)? = null,
  removeLabel: String? = null,
) {
  val colors = PhonoTheme.colors
  val fill = when (kind) {
    ChipKind.OUTLINE, ChipKind.ZERO -> colors.sheet
    ChipKind.MUTED -> colors.galley
    ChipKind.SOLID -> colors.ink
  }
  val ring: Color? = when (kind) {
    ChipKind.OUTLINE -> colors.lineStrong
    ChipKind.ZERO -> colors.hairline
    else -> null
  }
  val content = when (kind) {
    ChipKind.SOLID -> colors.onInk
    ChipKind.ZERO -> colors.inkMuted
    else -> colors.ink
  }
  Row(
    modifier = modifier
      .then(if (onClick != null) Modifier.focusRing(CircleShape).minimumInteractiveComponentSize() else Modifier)
      .heightIn(min = 32.dp)
      .clip(CircleShape)
      .background(fill)
      .then(if (ring != null) Modifier.border(PhonoSizes.ring, ring, CircleShape) else Modifier)
      .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
      .padding(start = PhonoSpacing.s12, end = if (onRemove != null) 0.dp else PhonoSpacing.s12),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
  ) {
    val lead = icon ?: if (kind == ChipKind.SOLID) PhonoIcons.StepDone else null
    lead?.let { Icon(painterResource(it), null, tint = content, modifier = Modifier.size(PhonoSizes.iconInline)) }
    PhonoText(
      text,
      style = MaterialTheme.typography.labelMedium,
      color = content,
      modifier = Modifier.padding(vertical = PhonoSpacing.s6),
    )
    if (onRemove != null) {
      Box(
        modifier = Modifier
          .size(PhonoSizes.minTarget)
          .clip(CircleShape)
          .clickable(role = Role.Button, onClick = onRemove)
          .semantics { contentDescription = removeLabel ?: text },
        contentAlignment = Alignment.Center,
      ) {
        Icon(painterResource(PhonoIcons.Close), null, tint = content, modifier = Modifier.size(PhonoSizes.iconInline))
      }
    }
  }
}

/**
 * A badge (design 9.1): a galley pill with an optional icon and words, never coloured. [dashed] draws the 1.5 dp dashed
 * inkMuted outline of the reliability badge ("Low confidence", "Nothing heard here", design 9.9).
 */
@Composable
fun PhonoBadge(
  text: String,
  modifier: Modifier = Modifier,
  @DrawableRes icon: Int? = null,
  dashed: Boolean = false,
) {
  val colors = PhonoTheme.colors
  Row(
    modifier = modifier
      .heightIn(min = 28.dp)
      .clip(CircleShape)
      .background(if (dashed) colors.sheet else colors.galley)
      .then(
        if (dashed) {
          Modifier.drawBehind {
            val w = 1.5.dp.toPx()
            drawRoundRect(
              color = colors.inkMuted,
              cornerRadius = CornerRadius(size.height / 2f),
              style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
              topLeft = androidx.compose.ui.geometry.Offset(w / 2f, w / 2f),
              size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
            )
          }
        } else {
          Modifier
        },
      )
      .padding(horizontal = PhonoSpacing.s10, vertical = PhonoSpacing.s4),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
  ) {
    icon?.let { Icon(painterResource(it), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconInline)) }
    PhonoText(text, style = MaterialTheme.typography.labelMedium, color = colors.ink)
  }
}

@PreviewLightDark
@Composable
private fun ChipsPreview() {
  PhonoPreviewSurface {
    Row(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.chipGap)) {
      PhonoChip("Outline", onClick = {})
      PhonoChip("On this phone", kind = ChipKind.MUTED, icon = PhonoIcons.AvailOnPhone)
      PhonoChip("Mix", kind = ChipKind.SOLID, onClick = {})
    }
    Row(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.chipGap)) {
      PhonoChip("Not in your clips yet", kind = ChipKind.ZERO)
      PhonoChip("Only: flapping", onRemove = {}, removeLabel = "Practice everything instead")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.chipGap)) {
      PhonoBadge("Safe to produce", icon = PhonoIcons.PracticeProduce)
      PhonoBadge("Low confidence", dashed = true)
    }
  }
}
