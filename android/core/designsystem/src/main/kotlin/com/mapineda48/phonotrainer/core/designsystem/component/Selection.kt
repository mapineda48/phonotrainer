package com.mapineda48.phonotrainer.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoColors
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme

/** One option of a [SegmentedControl]: its words and an optional 18 dp icon. */
data class SegmentedOption(val label: String, @DrawableRes val icon: Int? = null)

/**
 * A segmented control (design 9.1): a 1.5 dp outline ring, 3 dp inset, options 42 dp tall in a 48 dp row; a real radio
 * group (TalkBack reads "selected, 2 of 3"). Selected: ink fill and onInk words; each option has its focus ring.
 */
@Composable
fun SegmentedControl(
  options: List<SegmentedOption>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val colors = PhonoTheme.colors
  val shape = RoundedCornerShape(PhonoRadius.medium)
  Row(
    modifier = modifier
      .alpha(if (enabled) 1f else PhonoColors.DISABLED_ALPHA)
      .border(PhonoSizes.ring, colors.outline, shape)
      .padding(3.dp)
      .selectableGroup(),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    options.forEachIndexed { index, option ->
      val selected = index == selectedIndex
      val inner = RoundedCornerShape(PhonoRadius.medium - 3.dp)
      Row(
        modifier = Modifier
          .weight(1f)
          .focusRing(inner)
          .heightIn(min = 42.dp)
          .clip(inner)
          .background(if (selected) colors.ink else colors.sheet.copy(alpha = 0f))
          .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(index) })
          .padding(horizontal = PhonoSpacing.s8, vertical = PhonoSpacing.s8),
        horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s6, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val content = if (selected) colors.onInk else colors.ink
        option.icon?.let { Icon(painterResource(it), null, tint = content, modifier = Modifier.size(18.dp)) }
        PhonoText(
          option.label,
          style = MaterialTheme.typography.labelMedium,
          color = content,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

/**
 * A switch with its label (design 9.1): the whole row toggles. Track 52 × 32 dp; off = galley with a 2 dp outline
 * ring and a 16 dp outline thumb; on = ink track and a 24 dp onInk thumb carrying a check (two cues, no hue).
 */
@Composable
fun PhonoSwitch(
  label: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  description: String? = null,
  enabled: Boolean = true,
) {
  val colors = PhonoTheme.colors
  val motion = PhonoTheme.motion
  val thumbOffset by animateDpAsState(if (checked) 24.dp else 8.dp, motion.selection(), label = "thumb")
  val shape = RoundedCornerShape(PhonoRadius.medium)
  Row(
    modifier = modifier
      .focusRing(shape)
      .fillMaxWidth()
      .heightIn(min = PhonoSizes.row)
      .alpha(if (enabled) 1f else PhonoColors.DISABLED_ALPHA)
      .clip(shape)
      .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
      .padding(vertical = PhonoSpacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
  ) {
    Column(Modifier.weight(1f)) {
      PhonoText(label, style = MaterialTheme.typography.titleMedium, color = colors.ink)
      description?.let { PhonoText(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink2) }
    }
    Box(
      modifier = Modifier
        .size(52.dp, 32.dp)
        .clip(CircleShape)
        .background(if (checked) colors.ink else colors.galley)
        .then(if (checked) Modifier else Modifier.border(2.dp, colors.outline, CircleShape)),
      contentAlignment = Alignment.CenterStart,
    ) {
      val thumb = if (checked) 24.dp else 16.dp
      Box(
        modifier = Modifier
          .offset(x = thumbOffset - if (checked) 4.dp else 0.dp)
          .size(thumb)
          .clip(CircleShape)
          .background(if (checked) colors.onInk else colors.outline),
        contentAlignment = Alignment.Center,
      ) {
        if (checked) {
          Icon(painterResource(PhonoIcons.StepDone), null, tint = colors.ink, modifier = Modifier.size(16.dp))
        }
      }
    }
  }
}

/**
 * A radio card (design 9.1, Settings > Display): a sheet card with a 22 dp radio dot, a title, a body and an optional
 * live [preview]. Selected: a 2.5 dp ink ring and a filled dot.
 */
@Composable
fun RadioCard(
  title: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  body: String? = null,
  preview: (@Composable () -> Unit)? = null,
) {
  val colors = PhonoTheme.colors
  val shape = RoundedCornerShape(PhonoRadius.large)
  Row(
    modifier = modifier
      .focusRing(shape)
      .fillMaxWidth()
      .heightIn(min = PhonoSizes.row)
      .clip(shape)
      .background(colors.sheet)
      .border(if (selected) 2.5.dp else PhonoSizes.hairline, if (selected) colors.ink else colors.hairline, shape)
      .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
      .padding(PhonoSpacing.s14),
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
  ) {
    Box(
      modifier = Modifier
        .size(22.dp)
        .border(2.dp, if (selected) colors.ink else colors.outline, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(colors.ink))
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s6)) {
      PhonoText(title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
      body?.let { PhonoText(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink2) }
      preview?.invoke()
    }
  }
}

@PreviewLightDark
@Composable
private fun SelectionPreview() {
  PhonoPreviewSurface {
    SegmentedControl(
      options = listOf(SegmentedOption("Marks"), SegmentedOption("Plain"), SegmentedOption("IPA"), SegmentedOption("Off")),
      selectedIndex = 0,
      onSelect = {},
    )
    SegmentedControl(
      options = listOf(
        SegmentedOption("System", PhonoIcons.AppearanceSystem),
        SegmentedOption("Light", PhonoIcons.AppearanceLight),
        SegmentedOption("Dark", PhonoIcons.AppearanceDark),
      ),
      selectedIndex = 2,
      onSelect = {},
    )
    PhonoSwitch("Listen first", checked = false, onCheckedChange = {}, description = "The words appear after it plays.")
    PhonoSwitch("Weak spots first", checked = true, onCheckedChange = {})
    RadioCard("Standard", selected = true, onClick = {}, body = "The web's colours.")
    RadioCard("Color-vision friendly", selected = false, onClick = {}, body = "Patterns on by default.")
  }
}
