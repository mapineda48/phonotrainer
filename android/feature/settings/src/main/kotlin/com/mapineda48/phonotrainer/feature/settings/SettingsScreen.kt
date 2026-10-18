package com.mapineda48.phonotrainer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.mapineda48.phonotrainer.core.designsystem.component.Kicker
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoSwitch
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.RadioCard
import com.mapineda48.phonotrainer.core.designsystem.component.SegmentedControl
import com.mapineda48.phonotrainer.core.designsystem.component.SegmentedOption
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.LocalPhonoColors
import com.mapineda48.phonotrainer.core.designsystem.theme.LocalPhonoPalette
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.theme.phonoColors
import com.mapineda48.phonotrainer.core.domain.model.Appearance
import com.mapineda48.phonotrainer.core.domain.model.CaptionMode
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice
import com.mapineda48.phonotrainer.core.domain.model.EndOfShort
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LessonGuidance
import com.mapineda48.phonotrainer.core.domain.model.MotionChoice
import com.mapineda48.phonotrainer.core.domain.model.NotificationPermission
import com.mapineda48.phonotrainer.core.domain.model.PatternChoice
import com.mapineda48.phonotrainer.core.domain.model.StorageBudget
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.ui.MarkedWordSpan
import com.mapineda48.phonotrainer.core.ui.component.ClipCardCopy
import com.mapineda48.phonotrainer.core.ui.component.ConnectionStatusCardCopy
import com.mapineda48.phonotrainer.core.ui.marks.MarkLines

/** What the Settings page's controls ask for; the route turns each into a ViewModel call or a destination. */
internal class SettingsActions(
  val onBack: () -> Unit = {},
  val onAppearance: (Appearance) -> Unit = {},
  val onColors: (ColorChoice) -> Unit = {},
  val onPatterns: (PatternChoice) -> Unit = {},
  val onMotion: (MotionChoice) -> Unit = {},
  val onSpeed: (Float) -> Unit = {},
  val onPlayEach: (Boolean) -> Unit = {},
  val onEndOfShort: (EndOfShort) -> Unit = {},
  val onCaptions: (CaptionMode) -> Unit = {},
  val onListenFirst: (Boolean) -> Unit = {},
  val onTrack: (Track) -> Unit = {},
  val onInMix: (JobId, Boolean) -> Unit = { _, _ -> },
  val onQuestions: (Boolean) -> Unit = {},
  val onGuidance: (LessonGuidance) -> Unit = {},
  val onComputer: () -> Unit = {},
  val onStorage: () -> Unit = {},
  val onNotifications: () -> Unit = {},
  val onHelp: () -> Unit = {},
  val onAbout: () -> Unit = {},
)

/**
 * Settings (spec F-22, design 9.25 and 10.2 P-12, renders `22-settings-display` and `22-settings-full`): the top bar
 * and the lede, the three groups of choices on the page itself (Display, Playback and captions, Learning; each a
 * kicker with its icon and rule), then the five sub-page rows with their status lines. Every choice applies at once.
 */
@Composable
internal fun SettingsScreen(state: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
  var mixOpen by rememberSaveable { mutableStateOf(false) }
  SettingsPage(
    title = stringResource(R.string.settings_title),
    lede = stringResource(R.string.settings_lede),
    onBack = actions.onBack,
    listTag = SettingsTags.PAGE,
    modifier = modifier,
  ) {
    display(state, actions)
    playback(state, actions, onMix = { mixOpen = true })
    learning(state, actions)
    subPages(state, actions)
  }
  if (mixOpen) MixSheet(state.clips, onInMix = actions.onInMix, onDismiss = { mixOpen = false })
}

private fun LazyListScope.display(state: SettingsUiState, actions: SettingsActions) {
  val settings = state.settings
  item(key = "display") { Kicker(stringResource(R.string.settings_group_display), icon = PhonoIcons.Colors) }
  item(key = "appearance") {
    Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
      SettingLabel(stringResource(R.string.settings_appearance))
      Choice(
        options = listOf(
          SegmentedOption(stringResource(R.string.settings_appearance_system), PhonoIcons.AppearanceSystem),
          SegmentedOption(stringResource(R.string.settings_appearance_light), PhonoIcons.AppearanceLight),
          SegmentedOption(stringResource(R.string.settings_appearance_dark), PhonoIcons.AppearanceDark),
        ),
        values = Appearance.entries,
        selected = settings.appearance,
        onSelect = actions.onAppearance,
        tag = SettingsTags.APPEARANCE,
      )
    }
  }
  item(key = "colors") {
    Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
      SettingLabel(stringResource(R.string.settings_colors))
      Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
        ColorsCard(ColorChoice.STANDARD, settings.colors, settings.patterns, actions.onColors, SettingsTags.COLORS_STANDARD)
        ColorsCard(ColorChoice.COLOR_VISION_FRIENDLY, settings.colors, settings.patterns, actions.onColors, SettingsTags.COLORS_CVD)
      }
    }
  }
  item(key = "patterns") {
    Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
      SettingLabel(stringResource(R.string.settings_patterns), body = stringResource(R.string.settings_patterns_body))
      Choice(
        options = listOf(
          SegmentedOption(stringResource(R.string.settings_patterns_follow)),
          SegmentedOption(stringResource(R.string.settings_patterns_on)),
          SegmentedOption(stringResource(R.string.settings_patterns_off)),
        ),
        values = PatternChoice.entries,
        selected = settings.patterns,
        onSelect = actions.onPatterns,
        tag = SettingsTags.PATTERNS,
      )
    }
  }
  item(key = "motion") {
    Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
      SettingLabel(stringResource(R.string.settings_motion), body = stringResource(R.string.settings_motion_body))
      Choice(
        options = listOf(
          SegmentedOption(stringResource(R.string.settings_motion_follow)),
          SegmentedOption(stringResource(R.string.settings_motion_reduce)),
        ),
        values = MotionChoice.entries,
        selected = settings.motion,
        onSelect = actions.onMotion,
        tag = SettingsTags.MOTION,
      )
    }
  }
  item(key = "text-size") { TextSizeNote() }
}

private fun LazyListScope.playback(state: SettingsUiState, actions: SettingsActions, onMix: () -> Unit) {
  val settings = state.settings
  item(key = "playback") {
    Kicker(stringResource(R.string.settings_group_playback), icon = PhonoIcons.Captions, modifier = Modifier.padding(top = PhonoSpacing.s12))
  }
  item(key = "speed") {
    Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
      SettingLabel(stringResource(R.string.settings_speed))
      Choice(
        options = listOf(
          SegmentedOption(stringResource(R.string.settings_speed_half)),
          SegmentedOption(stringResource(R.string.settings_speed_three_quarters)),
          SegmentedOption(stringResource(R.string.settings_speed_normal)),
        ),
        values = SPEEDS,
        selected = SPEEDS.minBy { kotlin.math.abs(it - settings.defaultSpeed) },
        onSelect = actions.onSpeed,
        tag = SettingsTags.SPEED,
      )
    }
  }
  item(key = "play-each") {
    PhonoSwitch(
      label = stringResource(R.string.settings_play_each),
      checked = settings.playEachShort,
      onCheckedChange = actions.onPlayEach,
      modifier = Modifier.testTag(SettingsTags.PLAY_EACH),
    )
  }
  item(key = "end-of-short") { EndOfShortRow(settings.endOfShort, actions.onEndOfShort) }
  item(key = "captions") {
    Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
      SettingLabel(stringResource(R.string.settings_captions))
      Choice(
        options = listOf(
          SegmentedOption(stringResource(R.string.settings_captions_marks)),
          SegmentedOption(stringResource(R.string.settings_captions_plain)),
          SegmentedOption(stringResource(R.string.settings_captions_ipa)),
          SegmentedOption(stringResource(R.string.settings_captions_off)),
        ),
        values = CaptionMode.entries,
        selected = settings.captions,
        onSelect = actions.onCaptions,
        tag = SettingsTags.CAPTIONS,
      )
    }
  }
  item(key = "listen-first") {
    PhonoSwitch(
      label = stringResource(R.string.settings_listen_first),
      description = stringResource(R.string.settings_listen_first_body),
      checked = settings.listenFirst,
      onCheckedChange = actions.onListenFirst,
      modifier = Modifier.testTag(SettingsTags.LISTEN_FIRST),
    )
  }
  item(key = "track") {
    Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
      SettingLabel(stringResource(R.string.settings_track), body = stringResource(R.string.settings_track_body))
      Choice(
        options = listOf(
          SegmentedOption(stringResource(R.string.settings_track_mix)),
          SegmentedOption(stringResource(R.string.settings_track_dialogue)),
        ),
        values = listOf(Track.MIX, Track.DIALOGUE),
        selected = settings.track,
        onSelect = actions.onTrack,
        tag = SettingsTags.TRACK,
      )
    }
  }
  item(key = "mix") {
    val status = if (state.leftOut == 0) {
      stringResource(R.string.settings_mix_none)
    } else {
      pluralStringResource(R.plurals.settings_mix_count, state.leftOut, state.leftOut)
    }
    SettingsRow(
      icon = PhonoIcons.LeaveOut,
      title = stringResource(R.string.settings_mix),
      status = status,
      onClick = onMix,
      modifier = Modifier.testTag(SettingsTags.MIX),
    )
  }
}

private fun LazyListScope.learning(state: SettingsUiState, actions: SettingsActions) {
  val settings = state.settings
  item(key = "learning") {
    Kicker(stringResource(R.string.settings_group_learning), icon = PhonoIcons.TabLearn, modifier = Modifier.padding(top = PhonoSpacing.s12))
  }
  item(key = "questions") {
    PhonoSwitch(
      label = stringResource(R.string.settings_questions),
      description = stringResource(R.string.settings_questions_body),
      checked = settings.questionsAfterSet,
      onCheckedChange = actions.onQuestions,
      modifier = Modifier.testTag(SettingsTags.QUESTIONS),
    )
  }
  item(key = "guidance") {
    Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
      SettingLabel(stringResource(R.string.settings_guidance), body = stringResource(R.string.settings_guidance_body))
      Choice(
        options = listOf(
          SegmentedOption(stringResource(R.string.settings_guidance_full)),
          SegmentedOption(stringResource(R.string.settings_guidance_compact)),
        ),
        values = LessonGuidance.entries,
        selected = settings.lessonGuidance,
        onSelect = actions.onGuidance,
        tag = SettingsTags.GUIDANCE,
      )
    }
  }
}

private fun LazyListScope.subPages(state: SettingsUiState, actions: SettingsActions) {
  item(key = "rule") { RowRule(Modifier.padding(top = PhonoSpacing.s12)) }
  item(key = "computer") {
    val resources = LocalResources.current
    SettingsRow(
      icon = PhonoIcons.ConnConnected,
      title = stringResource(R.string.settings_link_computer),
      status = ConnectionStatusCardCopy.title(state.computer, state.sync, resources),
      onClick = actions.onComputer,
      modifier = Modifier.testTag(SettingsTags.LINK_COMPUTER),
    )
  }
  item(key = "storage") {
    SettingsRow(
      icon = PhonoIcons.ConnLowSpace,
      title = stringResource(R.string.settings_link_storage),
      status = state.storage?.let { storageLine(it) },
      onClick = actions.onStorage,
      modifier = Modifier.testTag(SettingsTags.LINK_STORAGE),
    )
  }
  item(key = "notifications") {
    val off = state.notifications == NotificationPermission.DENIED || state.notifications == NotificationPermission.NOT_ASKED
    SettingsRow(
      icon = if (off) PhonoIcons.NotificationsOff else PhonoIcons.Notifications,
      title = stringResource(R.string.settings_link_notifications),
      status = stringResource(if (off) R.string.settings_notifications_line_off else R.string.settings_notifications_line_on),
      onClick = actions.onNotifications,
      modifier = Modifier.testTag(SettingsTags.LINK_NOTIFICATIONS),
    )
  }
  item(key = "help") {
    SettingsRow(
      icon = PhonoIcons.Help,
      title = stringResource(R.string.settings_link_help),
      onClick = actions.onHelp,
      modifier = Modifier.testTag(SettingsTags.LINK_HELP),
    )
  }
  item(key = "about") {
    SettingsRow(
      icon = PhonoIcons.Licences,
      title = stringResource(R.string.settings_link_about),
      onClick = actions.onAbout,
      modifier = Modifier.testTag(SettingsTags.LINK_ABOUT),
    )
  }
}

/** "1.4 GB of 2 GB", or "1.4 GB used" without a budget (design 9.20). */
@Composable
internal fun storageLine(line: StorageLine): String {
  val resources = LocalResources.current
  val used = ClipCardCopy.bytes(line.usedBytes, resources)
  return when (val budget = line.budget) {
    is StorageBudget.Limit -> stringResource(R.string.settings_storage_line, used, ClipCardCopy.bytes(budget.bytes, resources))
    StorageBudget.FreeSpaceOnly -> stringResource(R.string.settings_storage_line_free, used)
  }
}

/** A segmented control over [values], with its test tag. */
@Composable
private fun <T> Choice(options: List<SegmentedOption>, values: List<T>, selected: T, onSelect: (T) -> Unit, tag: String) {
  SegmentedControl(
    options = options,
    selectedIndex = values.indexOf(selected).coerceAtLeast(0),
    onSelect = { onSelect(values[it]) },
    modifier = Modifier.fillMaxWidth().testTag(tag),
  )
}

/**
 * A palette as a radio card with its live preview (design 9.25): the four families' icons over "abc" underlined in
 * each family's line style, drawn in that palette whatever the current one is. The preview is decorative: the card's
 * words say what the palette is.
 */
@Composable
private fun ColorsCard(choice: ColorChoice, current: ColorChoice, patterns: PatternChoice, onSelect: (ColorChoice) -> Unit, tag: String) {
  val standard = choice == ColorChoice.STANDARD
  RadioCard(
    title = stringResource(if (standard) R.string.settings_colors_standard else R.string.settings_colors_cvd),
    body = stringResource(if (standard) R.string.settings_colors_standard_body else R.string.settings_colors_cvd_body),
    selected = choice == current,
    onClick = { onSelect(choice) },
    modifier = Modifier.testTag(tag),
    preview = { PalettePreview(choice, patterns) },
  )
}

@Composable
private fun PalettePreview(choice: ColorChoice, patterns: PatternChoice) {
  val dark = PhonoTheme.colors.isDark
  val palette = PhonoTheme.palette
  CompositionLocalProvider(
    LocalPhonoColors provides phonoColors(dark, choice),
    LocalPhonoPalette provides palette.copy(colors = choice, patterns = patterns),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = PhonoSpacing.s4).clearAndSetSemantics {},
      horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
      PREVIEW_FAMILIES.forEach { family ->
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s2)) {
          Icon(
            painterResource(MarkLines.iconOf(family)),
            null,
            tint = PhonoTheme.colors.family.of(family),
            modifier = Modifier.size(PhonoSizes.iconBar),
          )
          // The sample word only: the icon above already shows the family, so no second icon follows the word.
          MarkedWordSpan(
            stringResource(R.string.settings_colors_sample),
            family,
            style = MaterialTheme.typography.titleMedium,
            patterns = false,
          )
        }
      }
    }
  }
}

private val PREVIEW_FAMILIES = listOf(Family.REDUCTION, Family.TD, Family.ASSIMILATION, Family.BOUNDARY)

/** "At the end of a short" with its choice and a menu of the three (render `22-settings-full`). */
@Composable
private fun EndOfShortRow(selected: EndOfShort, onSelect: (EndOfShort) -> Unit) {
  var open by rememberSaveable { mutableStateOf(false) }
  val label = endOfShortLabel(selected)
  Box {
    SettingsRow(
      icon = PhonoIcons.Replay,
      title = stringResource(R.string.settings_end_of_short),
      status = label,
      trailing = PhonoIcons.ChevronDown,
      onClick = { open = true },
      modifier = Modifier.testTag(SettingsTags.END_OF_SHORT),
    )
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      EndOfShort.entries.forEach { choice ->
        DropdownMenuItem(
          text = { PhonoText(endOfShortLabel(choice), style = MaterialTheme.typography.bodyLarge, color = PhonoTheme.colors.ink) },
          onClick = {
            open = false
            onSelect(choice)
          },
          leadingIcon = if (choice == selected) {
            { Icon(painterResource(PhonoIcons.StepDone), null, tint = PhonoTheme.colors.ink) }
          } else {
            null
          },
          modifier = Modifier.testTag(SettingsTags.endOfShort(choice)).semantics { this.selected = choice == selected },
        )
      }
    }
  }
}

@Composable
private fun endOfShortLabel(choice: EndOfShort): String = stringResource(
  when (choice) {
    EndOfShort.REPLAY_ONCE -> R.string.settings_end_replay
    EndOfShort.STOP -> R.string.settings_end_stop
    EndOfShort.NEXT -> R.string.settings_end_next
  },
)

/** The text-size note (spec D-22): the system font size is the only size control. */
@Composable
private fun TextSizeNote() {
  val colors = PhonoTheme.colors
  Row(
    modifier = Modifier.fillMaxWidth().testTag(SettingsTags.TEXT_SIZE),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
  ) {
    Icon(painterResource(PhonoIcons.Info), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconBar))
    PhonoText(stringResource(R.string.settings_text_size), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
  }
}
