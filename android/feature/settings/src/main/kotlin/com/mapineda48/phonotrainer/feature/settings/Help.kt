package com.mapineda48.phonotrainer.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.designsystem.component.Kicker
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.api.CoachMarkRepository
import com.mapineda48.phonotrainer.core.ui.MarksLegend
import com.mapineda48.phonotrainer.core.ui.PhonoSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * Settings > Help's one action that changes something: "Restart the tour" re-arms every coach mark (spec F-01 item 4,
 * F-01-AC5), then the route opens the Welcome again. It never re-pairs: the pairing is not touched.
 */
@HiltViewModel
internal class HelpViewModel @Inject constructor(private val coachMarks: CoachMarkRepository) : ViewModel() {
  /** Re-arms the coach marks, then calls [then] (on the main thread), whatever the store answered. */
  fun restartTour(then: () -> Unit) {
    viewModelScope.launch {
      try {
        coachMarks.resetAll()
      } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
      }
      then()
    }
  }
}

/** What Help's controls ask for. */
internal class HelpActions(
  val onBack: () -> Unit = {},
  val onRestartTour: () -> Unit = {},
)

/** One step of "How PhonoTrainer works": the stage's name and its teaching line (spec F-20 item 2, the web's stepper). */
private data class HowStep(@param:StringRes val name: Int, @param:StringRes val body: Int)

private val HOW_STEPS = listOf(
  HowStep(R.string.help_step_download, R.string.help_step_download_body),
  HowStep(R.string.help_step_extract, R.string.help_step_extract_body),
  HowStep(R.string.help_step_separate, R.string.help_step_separate_body),
  HowStep(R.string.help_step_transcribe, R.string.help_step_transcribe_body),
  HowStep(R.string.help_step_phones, R.string.help_step_phones_body),
  HowStep(R.string.help_step_save, R.string.help_step_save_body),
)

/**
 * Settings > Help (spec F-22 "Help", design 10.2 P-12): "Restart the tour", "What do the marks mean?" (the same legend
 * sheet as the short's More sheet, `MarksLegend`), and "How PhonoTrainer works": the six steps of an analysis with the
 * web's teaching lines, the phone engines in plain words (the web's "About the engines") and what happens on the phone.
 */
@Composable
internal fun HelpScreen(actions: HelpActions, modifier: Modifier = Modifier, legendOpen: Boolean = false) {
  var legend by rememberSaveable { mutableStateOf(legendOpen) }
  SettingsPage(
    title = stringResource(R.string.help_title),
    lede = stringResource(R.string.help_lede),
    onBack = actions.onBack,
    listTag = SettingsTags.HELP_LIST,
    modifier = modifier,
  ) {
    item(key = "tour") {
      SettingsRow(
        icon = PhonoIcons.Tour,
        title = stringResource(R.string.help_tour),
        status = stringResource(R.string.help_tour_body),
        onClick = actions.onRestartTour,
        modifier = Modifier.testTag(SettingsTags.HELP_TOUR),
      )
    }
    item(key = "marks") {
      SettingsRow(
        icon = PhonoIcons.Legend,
        title = stringResource(R.string.help_marks),
        status = stringResource(R.string.help_marks_body),
        onClick = { legend = true },
        modifier = Modifier.testTag(SettingsTags.HELP_MARKS),
      )
    }
    item(key = "how") { HowItWorks(Modifier.padding(top = PhonoSpacing.s16)) }
  }
  if (legend) {
    PhonoSheet(onDismiss = { legend = false }, title = stringResource(R.string.help_marks), modifier = Modifier.testTag(SettingsTags.HELP_LEGEND)) {
      LegendContent()
    }
  }
}

/** The legend sheet's content on its own (screen tests): WP-03's `MarksLegend`, scrolling at large text. */
@Composable
internal fun LegendContent(modifier: Modifier = Modifier) {
  Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = PhonoSpacing.s24)) { MarksLegend() }
}

/** "How PhonoTrainer works" (spec F-22 "Help"): plain words, no controls. */
@Composable
private fun HowItWorks(modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  Column(modifier.fillMaxWidth().testTag(SettingsTags.HELP_HOW), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
    Kicker(stringResource(R.string.help_how), icon = PhonoIcons.Tip)
    PhonoText(stringResource(R.string.help_how_intro), style = MaterialTheme.typography.bodyLarge, color = colors.ink)
    HOW_STEPS.forEachIndexed { index, step ->
      val position = stringResource(R.string.help_step_number, index + 1, HOW_STEPS.size)
      Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
      ) {
        PhonoText((index + 1).toString(), style = PhonoTheme.type.accentItalic, color = colors.ink, modifier = Modifier.widthIn(min = 24.dp).clearAndSetSemantics { contentDescription = position })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s2)) {
          PhonoText(stringResource(step.name), style = MaterialTheme.typography.titleMedium, color = colors.ink)
          PhonoText(stringResource(step.body), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
        }
      }
    }
    Heading(R.string.help_engines)
    PhonoText(stringResource(R.string.help_engines_body), style = MaterialTheme.typography.bodyLarge, color = colors.ink)
    Engine(R.string.help_engine_timit, R.string.help_engine_timit_body)
    Engine(R.string.help_engine_espeak, R.string.help_engine_espeak_body)
    Heading(R.string.help_phone)
    PhonoText(stringResource(R.string.help_phone_body), style = MaterialTheme.typography.bodyLarge, color = colors.ink)
  }
}

@Composable
private fun Heading(@StringRes text: Int) {
  PhonoText(
    stringResource(text),
    style = MaterialTheme.typography.headlineSmall,
    color = PhonoTheme.colors.ink,
    modifier = Modifier.padding(top = PhonoSpacing.s8).semantics { heading() },
  )
}

@Composable
private fun Engine(@StringRes name: Int, @StringRes body: Int) {
  val colors = PhonoTheme.colors
  Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s2)) {
    PhonoText(stringResource(name), style = MaterialTheme.typography.titleMedium, color = colors.ink)
    PhonoText(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
  }
}
