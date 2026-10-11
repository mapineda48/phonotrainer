package com.mapineda48.phonotrainer.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme

/**
 * The top bar of secondary destinations (design 9.24): at least 64 dp, a 48 dp back arrow, the title in headlineLarge
 * (it wraps to two lines at large text, never truncated) and up to two [actions]. [scrolled] gives it the sheet fill
 * and a hairline. The lede follows in the content.
 */
@Composable
fun PhonoTopBar(
  title: String,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  backLabel: String? = null,
  scrolled: Boolean = false,
  actions: @Composable RowScope.() -> Unit = {},
) {
  val colors = PhonoTheme.colors
  Column(modifier.fillMaxWidth().background(if (scrolled) colors.sheet else Color.Transparent)) {
    Row(
      modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = PhonoSpacing.s4),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (onBack != null) {
        PhonoIconButton(PhonoIcons.Back, contentDescription = backLabel ?: title, onClick = onBack)
      }
      PhonoText(
        title,
        style = MaterialTheme.typography.headlineLarge,
        color = colors.ink,
        modifier = Modifier
          .weight(1f)
          .padding(horizontal = PhonoSpacing.s12, vertical = PhonoSpacing.s8)
          .semantics { heading() },
      )
      actions()
    }
    if (scrolled) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
  }
}

/** One item of the bottom bar: its word and its icon. */
data class BottomBarItem(val label: String, @DrawableRes val icon: Int, val testTag: String? = null)

/**
 * The bottom bar (design 9.24): sheet, the double rule above it, four items, each a 64 × 32 dp pill over its word.
 * Selected: an ink pill, an onInk icon and a bold word (two cues, no hue).
 */
@Composable
fun PhonoBottomBar(
  items: List<BottomBarItem>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = PhonoTheme.colors
  Column(modifier.fillMaxWidth().background(colors.sheet)) {
    DoubleRule()
    Row(
      modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).selectableGroup(),
      horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      items.forEachIndexed { index, item ->
        val selected = index == selectedIndex
        Column(
          modifier = Modifier
            .weight(1f)
            .focusRing(RoundedCornerShape(PhonoSizes.minTarget))
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(PhonoSizes.minTarget))
            .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) })
            .then(item.testTag?.let { Modifier.testTag(it) } ?: Modifier),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4, Alignment.CenterVertically),
        ) {
          Box(
            modifier = Modifier
              .size(64.dp, 32.dp)
              .clip(CircleShape)
              .background(if (selected) colors.ink else Color.Transparent),
            contentAlignment = Alignment.Center,
          ) {
            Icon(painterResource(item.icon), null, tint = if (selected) colors.onInk else colors.ink, modifier = Modifier.size(PhonoSizes.iconBar))
          }
          PhonoText(
            item.label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
            color = colors.ink,
          )
        }
      }
    }
  }
}

@PreviewLightDark
@Composable
private fun BarsPreview() {
  PhonoPreviewSurface {
    PhonoTopBar("Connection details", onBack = {}, backLabel = "Back")
    PhonoTopBar("Storage on this phone", onBack = {}, backLabel = "Back", scrolled = true) {
      PhonoIconButton(PhonoIcons.Help, "Help", {})
    }
    PhonoBottomBar(
      items = listOf(
        BottomBarItem("Home", PhonoIcons.TabHome),
        BottomBarItem("Watch", PhonoIcons.TabWatch),
        BottomBarItem("Learn", PhonoIcons.TabLearn),
        BottomBarItem("Practice", PhonoIcons.TabPractice),
      ),
      selectedIndex = 1,
      onSelect = {},
    )
  }
}
