package com.mapineda48.phonotrainer.feature.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoTopBar
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind

/**
 * The frame of both pages of this module (design 9.24): the top bar with the title and Back, then one vertical list,
 * so the list's saveable state keeps the scroll position across Back and process death.
 */
@Composable
internal fun ConnectPage(
  title: String,
  onBack: () -> Unit,
  listTag: String,
  modifier: Modifier = Modifier,
  state: LazyListState = rememberLazyListState(),
  content: LazyListScope.() -> Unit,
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .semantics { paneTitle = title }
      .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
  ) {
    PhonoTopBar(title = title, onBack = onBack, backLabel = stringResource(R.string.connect_back), scrolled = state.canScrollBackward)
    LazyColumn(
      state = state,
      modifier = Modifier.fillMaxSize().testTag(listTag),
      contentPadding = PaddingValues(start = PhonoSpacing.gutter, end = PhonoSpacing.gutter, bottom = PhonoSpacing.s32),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s16),
      content = content,
    )
  }
}

/** The one-sentence lede under the top bar (F-25 item 1). */
@Composable
internal fun Lede(text: String, modifier: Modifier = Modifier) {
  PhonoText(text, style = MaterialTheme.typography.bodyLarge, color = PhonoTheme.colors.ink2, modifier = modifier)
}

/** A group head (titleLarge, a heading for TalkBack). */
@Composable
internal fun GroupTitle(text: String, modifier: Modifier = Modifier) {
  PhonoText(
    text,
    style = MaterialTheme.typography.titleLarge,
    color = PhonoTheme.colors.ink,
    modifier = modifier.semantics { heading() },
  )
}

/** A hairline across the list. Decorative. */
@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
  Box(modifier.fillMaxWidth().height(PhonoSizes.hairline).background(PhonoTheme.colors.hairline))
}

/** [template] with [command] set in the code style of the instruction card (design 10.2 P-06: a galley well). */
@Composable
internal fun withCommand(template: String, command: String): AnnotatedString {
  val galley = PhonoTheme.colors.galley
  return remember(template, command, galley) {
    val at = if (command.isEmpty()) -1 else template.indexOf(command)
    buildAnnotatedString {
      if (at < 0) {
        append(template)
      } else {
        append(template.substring(0, at))
        withStyle(SpanStyle(background = galley)) { append(command) }
        append(template.substring(at + command.length))
      }
    }
  }
}

/**
 * A numbered list (render `21`; "numbered, not checkboxes", design 10.2 P-07): an italic numeral in a ring, the
 * sentence, a hairline between items. Each item is read as its number and its sentence.
 */
@Composable
internal fun NumberedList(items: List<AnnotatedString>, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  Column(modifier.fillMaxWidth()) {
    items.forEachIndexed { index, item ->
      if (index > 0) Hairline()
      Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = PhonoSpacing.s12),
        horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s14),
      ) {
        Numeral(index + 1)
        Text(item, style = MaterialTheme.typography.bodyLarge, color = colors.ink, modifier = Modifier.weight(1f))
      }
    }
  }
}

/** An italic Newsreader numeral in a 1.5 dp ink ring (render `21`). */
@Composable
internal fun Numeral(number: Int, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  Box(
    modifier = modifier.size(NUMERAL).border(PhonoSizes.ring, colors.ink, CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    PhonoText(number.toString(), style = PhonoTheme.type.accentItalic, color = colors.ink)
  }
}

/**
 * A terminal command in a galley code well (design 10.2 P-06: selectable, with "Copy"). The command is a product
 * string that never carries an address (server-contract 2.2). The command has the well's whole width, so at large
 * text it wraps between its words, never inside one; "Copy" sits under it at the end. After a copy it says "Copied".
 * (A column, not a flow row: the well lives in a card measured by its intrinsic height, which a flow row
 * under-reports at large text, clipping the card.)
 */
@Composable
internal fun CommandWell(command: String, onCopy: (String) -> Unit, copyTag: String, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  var copied by rememberSaveable(command) { mutableStateOf(false) }
  val copyLabel = stringResource(R.string.connect_copy_command, command)
  Column(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(PhonoRadius.medium))
      .background(colors.galley)
      .padding(start = PhonoSpacing.s12, end = PhonoSpacing.s4, top = PhonoSpacing.s10, bottom = PhonoSpacing.s2),
  ) {
    SelectionContainer(Modifier.fillMaxWidth().padding(end = PhonoSpacing.s8)) {
      PhonoText(command, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      PhonoButton(
        text = stringResource(if (copied) R.string.connect_copied else R.string.connect_copy),
        onClick = {
          onCopy(command)
          copied = true
        },
        kind = PhonoButtonKind.QUIET,
        modifier = Modifier.testTag(copyTag).semantics {
          contentDescription = copyLabel
          if (copied) liveRegion = LiveRegionMode.Polite
        },
      )
    }
  }
}

/** An icon and a line of words, the icon decorative (the words carry the meaning). [live]: announced on change. */
@Composable
internal fun IconLine(
  icon: Int,
  text: String,
  modifier: Modifier = Modifier,
  iconTint: Color = Color.Unspecified,
  live: Boolean = false,
) {
  val colors = PhonoTheme.colors
  Row(
    modifier = modifier
      .fillMaxWidth()
      .semantics(mergeDescendants = true) { if (live) liveRegion = LiveRegionMode.Polite },
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
    verticalAlignment = Alignment.Top,
  ) {
    Icon(
      painterResource(icon),
      contentDescription = null,
      tint = if (iconTint == Color.Unspecified) colors.ink else iconTint,
      modifier = Modifier.padding(top = PhonoSpacing.s2).size(PhonoSizes.iconButton),
    )
    PhonoText(text, style = MaterialTheme.typography.bodyMedium, color = colors.ink, modifier = Modifier.weight(1f))
  }
}

/** "Looking for your computer…" with the radar icon (spec 7.5 "Looking"); announced once when it appears. */
@Composable
internal fun LookingLine(modifier: Modifier = Modifier) {
  IconLine(PhonoIcons.ConnChecking, stringResource(R.string.connect_looking), modifier.testTag(ConnectTags.LOOKING), live = true)
}

/** A label above a value that can be selected and copied by hand (the facts of Connection details). */
@Composable
internal fun FactRow(label: String, value: String, modifier: Modifier = Modifier, selectable: Boolean = false) {
  val colors = PhonoTheme.colors
  Column(
    modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = PhonoSpacing.s6),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s2),
  ) {
    PhonoText(label, style = MaterialTheme.typography.labelLarge, color = colors.ink2)
    if (selectable) {
      SelectionContainer { PhonoText(value, style = MaterialTheme.typography.bodyLarge, color = colors.ink) }
    } else {
      PhonoText(value, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
    }
  }
}

private val NUMERAL = 32.dp
