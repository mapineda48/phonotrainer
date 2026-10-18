package com.mapineda48.phonotrainer.feature.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.mapineda48.phonotrainer.core.designsystem.component.LinkButton
import com.mapineda48.phonotrainer.core.designsystem.component.PaperCard
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoIconToggle
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoTopBar
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.illustration.PhonoIllustrations
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.SavedKind
import com.mapineda48.phonotrainer.core.ui.ChangeChip
import com.mapineda48.phonotrainer.core.ui.EmptyState
import com.mapineda48.phonotrainer.core.ui.EmptyStateAction
import com.mapineda48.phonotrainer.core.ui.LoadingGate
import com.mapineda48.phonotrainer.core.ui.MarkedWordSpan
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.a11y.paneTitle
import com.mapineda48.phonotrainer.core.ui.component.OccurrenceRow
import com.mapineda48.phonotrainer.core.ui.component.OccurrenceRowState
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What Saved's controls ask for; the route turns each into a ViewModel call or a destination. */
internal class SavedActions(
  val onBack: () -> Unit = {},
  val onWatchSaved: () -> Unit = {},
  val onGoToWatch: () -> Unit = {},
  val onPlay: (SavedRowUi) -> Unit = {},
  val onOpenLesson: (SavedRowUi) -> Unit = {},
  val onWatchShort: (SavedRowUi) -> Unit = {},
  val onOpenClip: (SavedRowUi) -> Unit = {},
  val onLearn: (SavedRowUi) -> Unit = {},
  val onUnsave: (Long) -> Unit = {},
)

/**
 * Saved (spec F-09 item 3; design 10.2 P-11, render `24`): the top bar, the lede, "Watch saved" (the Saved mode of the
 * feed) and one row per item, most recently saved first; empty: `ill_saved_empty`, "Nothing saved yet" and "Go to
 * Watch". The list is one lazy column, so its saveable state keeps the scroll position across Back and tab switches.
 */
@Composable
internal fun SavedScreen(state: SavedUiState, actions: SavedActions, modifier: Modifier = Modifier) {
  val title = stringResource(R.string.saved_title)
  val listState = rememberLazyListState()
  Column(
    modifier = modifier
      .fillMaxSize()
      .paneTitle(title)
      .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
      .testTag(PracticeTags.SAVED),
  ) {
    PhonoTopBar(
      title = title,
      onBack = actions.onBack,
      backLabel = stringResource(R.string.saved_back),
      scrolled = listState.canScrollBackward,
    )
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize().testTag(PracticeTags.SAVED_LIST),
      contentPadding = PaddingValues(start = PhonoSpacing.gutter, end = PhonoSpacing.gutter, bottom = PhonoSpacing.s24),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
    ) {
      item(key = "lede") {
        PhonoText(stringResource(R.string.saved_lede), style = MaterialTheme.typography.bodyLarge, color = PhonoTheme.colors.ink2)
      }
      when {
        state.loading -> item(key = "loading") {
          Box(Modifier.testTag(PracticeTags.SAVED_LOADING)) {
            LoadingGate(loading = true, label = stringResource(R.string.saved_loading)) {}
          }
        }
        state.rows.isEmpty() -> item(key = "empty") {
          Box(Modifier.testTag(PracticeTags.SAVED_EMPTY)) {
            EmptyState(
              illustration = PhonoIllustrations.SavedEmpty,
              title = stringResource(R.string.saved_empty_title),
              body = stringResource(R.string.saved_empty_body),
              actions = listOf(EmptyStateAction(stringResource(R.string.saved_empty_watch), actions.onGoToWatch)),
            )
          }
        }
        else -> {
          item(key = "watch") {
            PhonoButton(
              text = stringResource(R.string.saved_watch),
              onClick = actions.onWatchSaved,
              modifier = Modifier.testTag(PracticeTags.SAVED_WATCH),
              leadingIcon = PhonoIcons.TabWatch,
              fillWidth = true,
            )
          }
          items(state.rows, key = { "saved:" + it.id }) { row -> SavedRowView(row, actions) }
        }
      }
    }
  }
}

/**
 * A saved item: the kind ("Saved short" / "Saved word"), the word with its forms and "Play" (the shared example row,
 * which also opens the word's lesson), the change chip and "Last watched {time}", the bookmark-check toggle that
 * unsaves, and "Watch this short"; a clip not on this phone reads "Download the clip to watch it." instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedRowView(row: SavedRowUi, actions: SavedActions) {
  val colors = PhonoTheme.colors
  val word = row.wordText?.let(PracticeLogic::displayWord)
  PaperCard(modifier = Modifier.fillMaxWidth().testTag(PracticeTags.savedRow(row.id))) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s6)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s6)) {
          Icon(
            painterResource(if (row.kind == SavedKind.SHORT) PhonoIcons.TabWatch else PhonoIcons.Glossary),
            contentDescription = null,
            tint = colors.ink2,
            modifier = Modifier.size(PhonoSizes.iconInline),
          )
          PhonoText(
            stringResource(if (row.kind == SavedKind.SHORT) R.string.saved_kind_short else R.string.saved_kind_word),
            style = MaterialTheme.typography.labelMedium,
            color = colors.ink2,
          )
        }
        val occurrence = row.occurrence
        if (occurrence != null && row.available) {
          OccurrenceRow(
            state = OccurrenceRowState(occurrence, playing = row.playing),
            onPlay = { actions.onPlay(row) },
            onOpenLesson = { actions.onOpenLesson(row) },
          )
        } else {
          word?.let { MarkedWordSpan(text = it, family = row.change?.family, style = MaterialTheme.typography.titleMedium) }
          PhonoText(stringResource(R.string.saved_from_clip, row.clipTitle), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
        }
      }
      PhonoIconToggle(
        icon = PhonoIcons.Saved,
        contentDescription = word?.let { stringResource(R.string.saved_unsave, it) } ?: stringResource(R.string.saved_unsave_unknown),
        checked = true,
        onCheckedChange = { kept -> if (!kept) actions.onUnsave(row.id) },
        modifier = Modifier.testTag(PracticeTags.savedUnsave(row.id)),
      )
    }
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
      itemVerticalAlignment = Alignment.CenterVertically,
    ) {
      row.change?.let { change -> ChangeChip(capitalized(change.name), change.family, onClick = { actions.onLearn(row) }) }
      PhonoText(
        lastWatchedText(row.lastWatched),
        style = MaterialTheme.typography.bodySmall,
        color = colors.ink2,
        modifier = Modifier.testTag(PracticeTags.savedLast(row.id)),
      )
    }
    if (!row.available) {
      PhonoText(
        stringResource(R.string.saved_not_on_phone),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.ink,
        modifier = Modifier.testTag(PracticeTags.savedNotOnPhone(row.id)),
      )
      LinkButton(
        text = stringResource(R.string.saved_open_clip),
        onClick = { actions.onOpenClip(row) },
        trailingArrow = true,
        modifier = Modifier.testTag(PracticeTags.savedOpenClip(row.id)),
      )
    } else if (row.kind == SavedKind.SHORT) {
      PhonoButton(
        text = stringResource(R.string.saved_watch_short),
        onClick = { actions.onWatchShort(row) },
        modifier = Modifier.padding(top = PhonoSpacing.s2).testTag(PracticeTags.savedWatchShort(row.id)),
        kind = PhonoButtonKind.SECONDARY,
        leadingIcon = PhonoIcons.Play,
      )
    }
  }
}

/** "Last watched today", "… yesterday", "… 3 days ago", "… on 4 Oct", or "Not watched since you saved it". */
@Composable
private fun lastWatchedText(last: LastWatched): String = when (last) {
  LastWatched.Never -> stringResource(R.string.saved_last_never)
  LastWatched.Today -> stringResource(R.string.saved_last_today)
  LastWatched.Yesterday -> stringResource(R.string.saved_last_yesterday)
  is LastWatched.DaysAgo -> pluralStringResource(R.plurals.saved_last_days, last.days, last.days)
  is LastWatched.On -> stringResource(R.string.saved_last_on, (if (last.sameYear) SHORT_DATE else LONG_DATE).format(last.date))
}

/** The app's own English dates (CLAUDE.md: English only, whatever the phone's language). */
private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.US)
private val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
