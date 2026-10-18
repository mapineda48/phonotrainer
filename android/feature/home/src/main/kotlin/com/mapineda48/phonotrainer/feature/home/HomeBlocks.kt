package com.mapineda48.phonotrainer.feature.home

import android.content.res.Resources
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.CardVariant
import com.mapineda48.phonotrainer.core.designsystem.component.IconWell
import com.mapineda48.phonotrainer.core.designsystem.component.Illustration
import com.mapineda48.phonotrainer.core.designsystem.component.Kicker
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.NoticeKind
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.focusRing
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.illustration.PhonoIllustrations
import com.mapineda48.phonotrainer.core.designsystem.texture.halftone
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.ui.EmptyState
import com.mapineda48.phonotrainer.core.ui.EmptyStateAction
import com.mapineda48.phonotrainer.core.ui.LoadingGate
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.PhonoCard
import com.mapineda48.phonotrainer.core.ui.PosterArt
import com.mapineda48.phonotrainer.core.ui.familyLineName
import com.mapineda48.phonotrainer.core.ui.familyMeaning
import com.mapineda48.phonotrainer.core.ui.familyName
import com.mapineda48.phonotrainer.core.ui.marks.MarkLines
import com.mapineda48.phonotrainer.core.ui.component.ClipCard
import com.mapineda48.phonotrainer.core.ui.component.ClipCardState
import com.mapineda48.phonotrainer.core.ui.formatClock

// The blocks of Home (spec F-03 items 3 to 10 and its States; design 9.16). Each is a pure function of its state.

/** First run (F-03 States): the web's empty library adapted, with `ill_welcome_clips` and the numbered steps. */
@Composable
internal fun TeachingBlock(actions: HomeActions) {
  val colors = PhonoTheme.colors
  PhonoCard {
    Illustration(PhonoIllustrations.WelcomeClips)
    listOf(R.string.home_teach_step_1, R.string.home_teach_step_2, R.string.home_teach_step_3).forEachIndexed { index, step ->
      Row(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10), modifier = Modifier.semantics(mergeDescendants = true) {}) {
        PhonoText(stringResource(R.string.home_teach_step_number, index + 1), style = PhonoTheme.type.accentItalic, color = colors.ink)
        PhonoText(stringResource(step), style = MaterialTheme.typography.bodyLarge, color = colors.ink, modifier = Modifier.weight(1f))
      }
    }
    PhonoButton(
      text = stringResource(R.string.home_teach_connect),
      onClick = actions.onConnect,
      fillWidth = true,
      modifier = Modifier.testTag(HomeTags.TEACH_CONNECT),
    )
    PhonoButton(
      text = stringResource(R.string.home_teach_learn),
      onClick = actions.onLearn,
      kind = PhonoButtonKind.QUIET,
      modifier = Modifier.testTag(HomeTags.TEACH_LEARN),
    )
  }
}

/** Paired, and the computer has no clips yet (F-03 States, design 9.21). */
@Composable
internal fun EmptyBlock(actions: HomeActions) {
  PhonoCard {
    EmptyState(
      illustration = PhonoIllustrations.HomeNoClips,
      title = stringResource(R.string.home_empty_title),
      body = stringResource(R.string.home_empty_body),
      actions = listOf(EmptyStateAction(stringResource(R.string.home_empty_add), actions.onAdd)),
    )
  }
}

/** The catalogue could not be read (F-03 States): a caution notice in place of the clips; the rest keeps working. */
@Composable
internal fun ErrorBlock(actions: HomeActions) {
  Notice(
    body = stringResource(R.string.home_error),
    kind = NoticeKind.CAUTION,
    action = {
      PhonoButton(
        text = stringResource(R.string.home_error_retry),
        onClick = actions.onRetry,
        kind = PhonoButtonKind.SECONDARY,
        modifier = Modifier.testTag(HomeTags.ERROR_RETRY),
      )
    },
  )
}

/**
 * F-03 item 3 (design 9.16 block 3): the stacked hero card with crop marks and a halftone corner; kicker, headline,
 * counts, the stations thread (one mark per short, its spotlight's family, saved ones ringed, watched ones filled; one
 * slug per question) and its legend; "Start" (or "Continue", or a quiet "Another set") is its only control (D-45). The
 * card's words are one TalkBack sentence (F-03-AC10).
 */
@Composable
internal fun TodayBlock(card: TodayCard, actions: HomeActions) {
  val colors = PhonoTheme.colors
  val resources = LocalResources.current
  PhonoCard(variant = CardVariant.STACK) {
    Box(Modifier.fillMaxWidth()) {
      Box(Modifier.align(Alignment.TopEnd).size(HALFTONE_SIZE).halftone(seed = HALFTONE_SEED, color = colors.halftone))
      Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s10)) {
        Kicker(stringResource(R.string.home_today_kicker))
        when (card) {
          TodayCard.NoClips -> {
            PhonoText(
              stringResource(R.string.home_today_first_run),
              style = MaterialTheme.typography.titleLarge,
              color = colors.ink,
              modifier = Modifier.testTag(HomeTags.TODAY_CARD),
            )
            Stations(List(HomeCopy.FULL_SET) { null }, Modifier.clearAndSetSemantics { })
          }
          TodayCard.Preparing -> Box(Modifier.testTag(HomeTags.TODAY_CARD)) {
            LoadingGate(loading = true, label = stringResource(R.string.home_today_preparing)) {}
          }
          TodayCard.NothingNew -> {
            PhonoText(
              stringResource(R.string.home_today_nothing_new),
              style = MaterialTheme.typography.bodyLarge,
              color = colors.ink,
              modifier = Modifier.testTag(HomeTags.TODAY_CARD),
            )
            PhonoButton(
              text = stringResource(R.string.home_today_start),
              onClick = actions.onStartToday,
              large = true,
              fillWidth = true,
              modifier = Modifier.testTag(HomeTags.TODAY_START),
            )
          }
          is TodayCard.Ready -> ReadyToday(card, actions, resources)
        }
      }
    }
  }
}

@Composable
private fun ReadyToday(card: TodayCard.Ready, actions: HomeActions, resources: Resources) {
  val colors = PhonoTheme.colors
  val sentence = remember(card, resources) { HomeCopy.todayCardSentence(card, resources) }
  Column(
    modifier = Modifier.testTag(HomeTags.TODAY_CARD).clearAndSetSemantics { contentDescription = sentence },
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
  ) {
    PhonoText(HomeCopy.todayHeadline(card, resources), style = MaterialTheme.typography.displayMedium, color = colors.ink)
    if (card.phase == TodayPhase.DONE) {
      HomeCopy.met(card, resources)?.let { PhonoText(it, style = MaterialTheme.typography.bodyLarge, color = colors.ink) }
    } else {
      PhonoText(HomeCopy.todayCounts(card.summary, resources), style = MaterialTheme.typography.bodyLarge, color = colors.ink2)
    }
    Stations(card.stations, Modifier)
    StationsLegend(card.summary)
    HomeCopy.shortSet(card.summary, resources)?.let {
      PhonoText(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
    }
  }
  when (card.phase) {
    TodayPhase.FRESH -> PhonoButton(
      text = stringResource(R.string.home_today_start),
      onClick = actions.onStartToday,
      large = true,
      fillWidth = true,
      modifier = Modifier.testTag(HomeTags.TODAY_START),
    )
    TodayPhase.IN_PROGRESS -> PhonoButton(
      text = stringResource(R.string.home_today_continue),
      onClick = actions.onStartToday,
      large = true,
      fillWidth = true,
      modifier = Modifier.testTag(HomeTags.TODAY_START),
    )
    TodayPhase.DONE -> PhonoButton(
      text = stringResource(R.string.home_today_another),
      onClick = actions.onAnotherSet,
      kind = PhonoButtonKind.QUIET,
      modifier = Modifier.testTag(HomeTags.TODAY_ANOTHER),
    )
  }
}

/**
 * The stations thread (design 9.16 block 3): a dotted trace through one circle per short (its spotlight family's icon
 * in its hue on sheet; a saved one with a 2 dp ink ring and a bookmark; a watched one filled with ink) and one slug per
 * question. A null station is an empty dashed circle (first run). Decorative: the card's sentence says what it holds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Stations(stations: List<Station?>, modifier: Modifier) {
  val colors = PhonoTheme.colors
  Box(modifier.fillMaxWidth().clearAndSetSemantics { }) {
    Canvas(Modifier.fillMaxWidth().height(STATION_SIZE)) {
      val y = STATION_SIZE.toPx() / 2
      drawLine(
        colors.inkMuted,
        Offset(0f, y),
        Offset(size.width, y),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 5.dp.toPx())),
      )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s6), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s6)) {
      stations.forEach { station ->
        when (station) {
          null -> Canvas(Modifier.size(STATION_SIZE)) {
            drawCircle(
              colors.inkMuted,
              radius = size.minDimension / 2 - 1.dp.toPx(),
              style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
            )
          }
          is Station.Short -> ShortStation(station)
          is Station.Question -> Box(
            Modifier
              .padding(top = (STATION_SIZE - SLUG_HEIGHT) / 2)
              .size(SLUG_WIDTH, SLUG_HEIGHT)
              .clip(RoundedCornerShape(PhonoRadius.extraSmall))
              .background(if (station.answered) colors.ink else colors.sheet)
              .border(PhonoSizes.ring, colors.ink, RoundedCornerShape(PhonoRadius.extraSmall)),
          )
        }
      }
    }
  }
}

@Composable
private fun ShortStation(station: Station.Short) {
  val colors = PhonoTheme.colors
  val family = station.family ?: Family.OTHER
  Box(
    modifier = Modifier
      .size(STATION_SIZE)
      .clip(CircleShape)
      .background(if (station.done) colors.ink else colors.sheet)
      .border(if (station.saved) 2.dp else PhonoSizes.ring, if (station.saved) colors.ink else colors.family.of(family), CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      painterResource(if (station.saved) PhonoIcons.Saved else MarkLines.iconOf(family)),
      contentDescription = null,
      tint = if (station.done) colors.onInk else if (station.saved) colors.ink else colors.family.of(family),
      modifier = Modifier.size(PhonoSizes.iconInline),
    )
  }
}

/** "8 shorts · one change each" / "5 questions". */
@Composable
private fun StationsLegend(summary: TodaySummary) {
  val colors = PhonoTheme.colors
  Row(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s16)) {
    PhonoText(
      pluralStringResource(R.plurals.home_today_legend_shorts, summary.shorts, summary.shorts),
      style = MaterialTheme.typography.labelMedium,
      color = colors.ink2,
    )
    if (summary.questions > 0) {
      PhonoText(
        pluralStringResource(R.plurals.home_today_questions, summary.questions, summary.questions),
        style = MaterialTheme.typography.labelMedium,
        color = colors.ink2,
      )
    }
  }
}

/** F-03 item 4 (design 9.16 block 4): poster, title, "Short 4 of 15", "Listen for: [change] in “water”", the action. */
@Composable
internal fun KeepWatchingBlock(card: KeepWatchingCard, actions: HomeActions) {
  val colors = PhonoTheme.colors
  val clip = card.clip.clip
  val position = if (card.ordinal != null && card.total != null) stringResource(R.string.home_keep_short, card.ordinal, card.total) else null
  val listen = when {
    card.changeName != null && card.words != null -> stringResource(R.string.home_keep_listen_sentence, card.changeName, card.words)
    card.changeName != null -> stringResource(R.string.home_keep_listen_change, card.changeName)
    else -> null
  }
  PhonoCard {
    Kicker(stringResource(R.string.home_keep_kicker))
    Column(
      modifier = Modifier.semantics(mergeDescendants = true) {},
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
    ) {
      PosterArt(
        poster = card.clip.poster,
        jobId = clip.id,
        audioOnly = clip.video == VideoAvailability.AudioOnlyClip,
        modifier = Modifier.fillMaxWidth().aspectRatio(POSTER_RATIO),
        title = clip.title,
        chip = clip.durationS?.let { formatClock((it * 1_000).toLong()) },
      )
      PhonoText(clip.title, style = MaterialTheme.typography.titleMedium, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
      position?.let { PhonoText(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink2) }
      if (listen != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s6)) {
          card.family?.let { family ->
            Icon(
              painterResource(MarkLines.iconOf(family)),
              contentDescription = null,
              tint = colors.family.of(family),
              modifier = Modifier.size(PhonoSizes.iconInline),
            )
          }
          PhonoText(listen, style = MaterialTheme.typography.bodyLarge, color = colors.ink, modifier = Modifier.weight(1f))
        }
      }
    }
    PhonoButton(
      text = stringResource(R.string.home_keep_action),
      onClick = { actions.onKeepWatching(card.short) },
      fillWidth = true,
      modifier = Modifier.testTag(HomeTags.KEEP_WATCHING),
    )
  }
}

/** F-03 item 5 (design 9.16 block 5): two cards, "Paste a link" and "Choose a file"; the clipboard is never read here. */
@Composable
internal fun AddBlock(actions: HomeActions) {
  val colors = PhonoTheme.colors
  Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s10)) {
    Kicker(stringResource(R.string.home_add_kicker))
    AddCard(PhonoIcons.Paste, R.string.home_add_paste, R.string.home_add_paste_sub, actions.onPaste, HomeTags.ADD_PASTE)
    AddCard(PhonoIcons.ChooseFile, R.string.home_add_file, R.string.home_add_file_sub, actions.onChooseFile, HomeTags.ADD_FILE)
    PhonoText(stringResource(R.string.home_add_note), style = PhonoTheme.type.accentItalic, color = colors.ink2)
  }
}

@Composable
private fun AddCard(icon: Int, title: Int, body: Int, onClick: () -> Unit, tag: String) {
  val colors = PhonoTheme.colors
  val label = stringResource(title)
  val sub = stringResource(body)
  // The whole card is one target; its words are its label (a clickable merges what it holds).
  PhonoCard(onClick = onClick, modifier = Modifier.testTag(tag)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
    ) {
      Box(
        Modifier
          .size(WELL_SIZE)
          .clip(RoundedCornerShape(PhonoRadius.medium))
          .background(colors.galley)
          .dashedRing(colors.inkMuted),
        contentAlignment = Alignment.Center,
      ) {
        Icon(painterResource(icon), contentDescription = null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconWell))
      }
      Column(Modifier.weight(1f)) {
        PhonoText(label, style = MaterialTheme.typography.titleMedium, color = colors.ink)
        PhonoText(sub, style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
      }
      Icon(painterResource(PhonoIcons.ChevronRight), contentDescription = null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconButton))
    }
  }
}

/** The dashed outline of the "Add a clip" icon wells (design 9.16 block 5). */
private fun Modifier.dashedRing(color: Color): Modifier = drawWithContent {
  drawContent()
  drawRoundRect(
    color,
    cornerRadius = CornerRadius(PhonoRadius.medium.toPx()),
    style = Stroke(PhonoSizes.ring.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
  )
}

/** F-03 item 6 (design 9.16 block 6): "Ready to watch: {title}", "New on this phone · 12 shorts", "Watch". */
@Composable
internal fun NewBlock(clips: List<NewClip>, actions: HomeActions) {
  val colors = PhonoTheme.colors
  Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s10)) {
    Kicker(stringResource(R.string.home_new_kicker))
    clips.forEach { item ->
      PhonoCard(variant = CardVariant.CROP_MARKS) {
        Box(Modifier.fillMaxWidth()) {
          PhonoText(
            stringResource(R.string.home_new_stamp).uppercase(),
            style = MaterialTheme.typography.displaySmall,
            color = colors.inkMuted.copy(alpha = STAMP_ALPHA),
            modifier = Modifier.align(Alignment.TopEnd).clearAndSetSemantics { },
          )
          Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
            Row(
              modifier = Modifier.semantics(mergeDescendants = true) {},
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
            ) {
              IconWell(PhonoIcons.StatusReady, size = SMALL_WELL)
              Column(Modifier.weight(1f)) {
                PhonoText(
                  stringResource(R.string.home_new_ready, item.clip.title),
                  style = MaterialTheme.typography.titleMedium,
                  color = colors.ink,
                  maxLines = 3,
                  overflow = TextOverflow.Ellipsis,
                )
                PhonoText(
                  if (item.shorts > 0) {
                    pluralStringResource(R.plurals.home_new_meta, item.shorts, item.shorts)
                  } else {
                    stringResource(R.string.home_new_meta_plain)
                  },
                  style = MaterialTheme.typography.bodyMedium,
                  color = colors.ink2,
                )
              }
            }
            PhonoButton(
              text = stringResource(R.string.home_new_watch),
              onClick = { actions.onWatchClip(item.clip.id) },
              kind = PhonoButtonKind.SECONDARY,
              leadingIcon = PhonoIcons.Play,
              modifier = Modifier.testTag(HomeTags.newWatch(item.clip.id)),
            )
          }
        }
      }
    }
  }
}

/**
 * F-03 item 7 (design 9.16 block 7, 9.18): the shelf of the 10 clips most recently studied or added, as 176 dp clip
 * cards in a row, and "All clips" (always there, so the Library's "In progress" is one tap away even before a clip).
 */
@Composable
internal fun ShelfBlock(state: HomeUiState, actions: HomeActions) {
  Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s10)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Kicker(stringResource(R.string.home_shelf_kicker), modifier = Modifier.weight(1f))
      PhonoButton(
        text = stringResource(R.string.home_shelf_all),
        onClick = actions.onAllClips,
        kind = PhonoButtonKind.QUIET,
        trailingIcon = PhonoIcons.ArrowRight,
        modifier = Modifier.testTag(HomeTags.LINK_LIBRARY),
      )
    }
    when (state.catalogue) {
      HomeCatalogue.Loading -> LoadingGate(loading = true, label = stringResource(R.string.home_shelf_loading)) {}
      else -> if (state.shelf.isNotEmpty()) {
        LazyRow(
          modifier = Modifier.fillMaxWidth().testTag(HomeTags.SHELF_ROW),
          horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.gridGap),
          contentPadding = PaddingValues(bottom = PhonoSpacing.s8, end = PhonoSpacing.s8),
        ) {
          items(state.shelf, key = { it.clip.id.value }) { card ->
            ShelfCard(card, actions)
          }
        }
      }
    }
  }
}

@Composable
private fun ShelfCard(card: ClipCardState, actions: HomeActions) {
  var menu by rememberSaveable(card.clip.id.value) { mutableStateOf(false) }
  val id = card.clip.id
  Box(Modifier.testTag(HomeTags.shelfCard(id))) {
    ClipCard(
      state = card,
      onWatch = { actions.onWatchClip(id) },
      onOpen = { actions.onOpenClip(id) },
      onMore = { menu = true },
    )
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
      DropdownMenuItem(
        text = { PhonoText(stringResource(R.string.home_shelf_menu_watch)) },
        onClick = {
          menu = false
          actions.onWatchClip(id)
        },
        modifier = Modifier.testTag(HomeTags.SHELF_MENU_WATCH),
      )
      DropdownMenuItem(
        text = { PhonoText(stringResource(R.string.home_shelf_menu_open)) },
        onClick = {
          menu = false
          actions.onOpenClip(id)
        },
        modifier = Modifier.testTag(HomeTags.SHELF_MENU_OPEN),
      )
    }
  }
}

/**
 * F-03 item 8 (design 9.16 block 8, D-48): one card; per family a header (icon, name, its line and what it means) and
 * a chip per change with its short count, "Flapping · 34", which opens the change mode with exactly that many shorts;
 * a change with none reads "Not in your clips yet" (muted) and opens its Learn page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChangesBlock(groups: List<ChangeGroup>, actions: HomeActions) {
  val colors = PhonoTheme.colors
  Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s10)) {
    Kicker(stringResource(R.string.home_changes_kicker))
    PhonoCard {
      groups.forEach { group ->
        Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
          Row(
            modifier = Modifier.semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
          ) {
            Icon(
              painterResource(MarkLines.iconOf(group.family)),
              contentDescription = null,
              tint = colors.family.of(group.family),
              modifier = Modifier.size(PhonoSizes.iconButton),
            )
            Column {
              PhonoText(familyName(group.family), style = MaterialTheme.typography.titleSmall, color = colors.ink)
              PhonoText(
                stringResource(R.string.home_changes_family_line, familyLineName(group.family), familyMeaning(group.family)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink2,
              )
            }
          }
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.chipGap),
            verticalArrangement = Arrangement.spacedBy(PhonoSpacing.chipGap),
          ) {
            group.chips.forEach { chip -> ChangeCountChip(chip) { actions.onChange(chip.label, chip.shorts) } }
          }
        }
      }
    }
  }
}

/** A Watch by change chip: the family's tint and ring, its icon, "Flapping · 34"; muted "Not in your clips yet" at 0. */
@Composable
private fun ChangeCountChip(chip: ChangeChipState, onClick: () -> Unit) {
  val colors = PhonoTheme.colors
  val familyWord = familyName(chip.family)
  val none = chip.shorts == 0
  val description = if (none) {
    stringResource(R.string.home_change_none_description, chip.name, familyWord)
  } else {
    pluralStringResource(R.plurals.home_change_description, chip.shorts, chip.name, familyWord, chip.shorts)
  }
  val label = stringResource(if (none) R.string.home_change_open_learn else R.string.home_change_open_feed)
  val shape = CircleShape
  Row(
    modifier = Modifier
      .testTag(HomeTags.chip(chip.label))
      .focusRing(shape)
      .heightIn(min = PhonoSizes.minTarget)
      .clip(shape)
      .background(if (none) colors.sheet else colors.family.tintOf(chip.family))
      .border(PhonoSizes.ring, if (none) colors.hairline else colors.family.of(chip.family), shape)
      .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
      // One stop: its words stay on the node (the visible "Flapping · 34"), TalkBack reads the fuller description.
      .semantics(mergeDescendants = true) { contentDescription = description }
      .padding(horizontal = PhonoSpacing.s12, vertical = PhonoSpacing.s6),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
  ) {
    Icon(
      painterResource(MarkLines.iconOf(chip.family)),
      contentDescription = null,
      tint = if (none) colors.inkMuted else colors.ink,
      modifier = Modifier.size(PhonoSizes.iconInline),
    )
    if (none) {
      Column {
        PhonoText(chip.name, style = MaterialTheme.typography.labelMedium, color = colors.ink2)
        PhonoText(stringResource(R.string.home_change_none), style = MaterialTheme.typography.labelSmall, color = colors.ink2)
      }
    } else {
      PhonoText(stringResource(R.string.home_change_count, chip.name, chip.shorts), style = MaterialTheme.typography.labelMedium, color = colors.ink)
    }
  }
}

/**
 * F-03 item 9 (design 9.16 block 9): "10 questions · about 3 minutes", the weakest change with a cell meter (filled =
 * right), "12 saved shorts" (Saved), the practice spot illustration and "Start".
 */
@Composable
internal fun PracticeBlock(card: PracticeCard, actions: HomeActions) {
  val colors = PhonoTheme.colors
  Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s10)) {
    Kicker(stringResource(R.string.home_practice_kicker))
    PhonoCard {
      Row(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
          PhonoText(stringResource(R.string.home_practice_size), style = MaterialTheme.typography.titleMedium, color = colors.ink)
          card.weakSpot?.let { spot ->
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s6)) {
              PhonoText(
                stringResource(R.string.home_practice_worth, spot.name, spot.correct, spot.seen),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
              )
              AccuracyMeter(spot)
            }
          }
        }
        Box(Modifier.width(SPOT_WIDTH)) { Illustration(PhonoIllustrations.SpotPractice) }
      }
      if (card.savedShorts > 0) {
        PhonoButton(
          text = pluralStringResource(R.plurals.home_practice_saved, card.savedShorts, card.savedShorts),
          onClick = actions.onSaved,
          kind = PhonoButtonKind.QUIET,
          leadingIcon = PhonoIcons.Saved,
          trailingIcon = PhonoIcons.ArrowRight,
          modifier = Modifier.testTag(HomeTags.PRACTICE_SAVED),
        )
      }
      PhonoButton(
        text = stringResource(R.string.home_practice_start),
        onClick = actions.onPractice,
        kind = PhonoButtonKind.SECONDARY,
        fillWidth = true,
        modifier = Modifier.testTag(HomeTags.PRACTICE_START),
      )
    }
  }
}

/** One cell per answer (scaled to at most [METER_CELLS]); filled cells are right answers. Decorative. */
@Composable
private fun AccuracyMeter(spot: WeakSpot) {
  val colors = PhonoTheme.colors
  val cells = spot.seen.coerceAtMost(METER_CELLS)
  val filled = if (spot.seen <= METER_CELLS) spot.correct else Math.round(spot.correct * METER_CELLS.toFloat() / spot.seen)
  Row(Modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
    repeat(cells) { index ->
      Box(
        Modifier
          .size(METER_CELL)
          .clip(RoundedCornerShape(PhonoRadius.extraSmall))
          .background(if (index < filled) colors.ink else colors.sheet)
          .border(PhonoSizes.hairline, colors.ink, RoundedCornerShape(PhonoRadius.extraSmall)),
      )
    }
  }
}

/** F-03 item 10 (v1-complete; only when Insights is bound): one takeaway and "See Insights". */
@Composable
internal fun InsightsBlock(teaser: String, actions: HomeActions) {
  val colors = PhonoTheme.colors
  Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s10)) {
    Kicker(stringResource(R.string.home_insights_kicker))
    PhonoCard {
      Row(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
        PhonoText(teaser, style = MaterialTheme.typography.titleLarge, color = colors.ink, modifier = Modifier.weight(1f))
        Box(Modifier.width(SPOT_WIDTH)) { Illustration(PhonoIllustrations.SpotInsights) }
      }
      PhonoButton(
        text = stringResource(R.string.home_insights_see),
        onClick = actions.onInsights,
        kind = PhonoButtonKind.QUIET,
        trailingIcon = PhonoIcons.ArrowRight,
        modifier = Modifier.testTag(HomeTags.INSIGHTS),
      )
    }
  }
}

private val HALFTONE_SIZE = 72.dp
private val STATION_SIZE = 28.dp
private val SLUG_WIDTH = 14.dp
private val SLUG_HEIGHT = 10.dp
private val WELL_SIZE = 48.dp
private val SMALL_WELL = 36.dp
private val SPOT_WIDTH = 88.dp
private val METER_CELL = 12.dp
private const val METER_CELLS = 10
private const val POSTER_RATIO = 16f / 9f
private const val STAMP_ALPHA = 0.35f
private const val HALFTONE_SEED = 45
