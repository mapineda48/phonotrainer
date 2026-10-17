package com.mapineda48.phonotrainer.feature.lesson

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.LinkButton
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.NoticeKind
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoSwitch
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.SegmentedControl
import com.mapineda48.phonotrainer.core.designsystem.component.SegmentedOption
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoColors
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.LinkType
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.WordClass
import com.mapineda48.phonotrainer.core.ui.ExplainButton
import com.mapineda48.phonotrainer.core.ui.IpaText
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.PhonoCard
import com.mapineda48.phonotrainer.core.ui.PhonoSheet
import com.mapineda48.phonotrainer.core.ui.PracticeBadge
import com.mapineda48.phonotrainer.core.ui.component.ShadowingOptions
import com.mapineda48.phonotrainer.core.ui.component.ShadowingPanel
import com.mapineda48.phonotrainer.core.ui.extension.MouthRenderer
import com.mapineda48.phonotrainer.core.ui.marks.MarkLines
import java.util.Locale

/** The five numbered steps (spec F-10 item 3, design 9.9). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LessonSteps(
  state: LessonUiState.Ready,
  actions: LessonActions,
  requesters: Map<LessonStep, BringIntoViewRequester>,
  explainer: Explainer,
  mouth: MouthRenderer?,
  playheadMs: () -> Long?,
  record: RecordSlot?,
) {
  @Composable
  fun step(step: LessonStep, status: (@Composable () -> Unit)? = null, body: @Composable () -> Unit) {
    StepSection(
      step = step,
      open = step in state.open,
      onToggle = { actions.onToggleStep(step) },
      requester = requesters.getValue(step),
      status = status,
      body = body,
    )
  }
  step(LessonStep.LISTEN, status = { ListenStatus(state.listen) }) { ListenStep(state.listen, state.header.text, actions) }
  step(LessonStep.COMPARE) { CompareStep(state.compare, state.listen.hasAudio, actions, explainer) }
  step(LessonStep.WHY) { WhyStep(state.why, actions, explainer) }
  step(LessonStep.SEE) { SeeStep(state.see, actions, mouth, playheadMs) }
  step(LessonStep.PRACTICE) { PracticeStep(state.practice, actions, record) }
}

/** A numbered disclosure: a 56 dp row with the numeral in its ring (an ink disc when open), the title and a status. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StepSection(
  step: LessonStep,
  open: Boolean,
  onToggle: () -> Unit,
  requester: BringIntoViewRequester,
  status: (@Composable () -> Unit)?,
  body: @Composable () -> Unit,
) {
  val colors = PhonoTheme.colors
  val title = stringResource(stepTitle(step))
  val number = stringResource(R.string.lesson_step_number, step.number)
  val stateWords = stringResource(if (open) R.string.lesson_step_open else R.string.lesson_step_closed)
  Column(Modifier.fillMaxWidth().bringIntoViewRequester(requester)) {
    Box(Modifier.fillMaxWidth().height(PhonoSizes.hairline).background(colors.hairline))
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = PhonoSizes.row)
        .clickable(role = Role.Button, onClick = onToggle)
        .semantics(mergeDescendants = true) {
          heading()
          stateDescription = stateWords
        }
        .testTag(LessonTags.step(step))
        .padding(horizontal = PhonoSpacing.gutter, vertical = PhonoSpacing.s8),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
    ) {
      Box(
        modifier = Modifier
          .size(StepRing)
          .clip(CircleShape)
          .background(if (open) colors.ink else colors.sheet)
          .border(PhonoSizes.ring, colors.ink, CircleShape)
          .clearAndSetSemantics { contentDescription = number },
        contentAlignment = Alignment.Center,
      ) {
        PhonoText("${step.number}", style = PhonoTheme.type.accentItalic, color = if (open) colors.onInk else colors.ink)
      }
      PhonoText(title, style = MaterialTheme.typography.titleLarge, color = colors.ink, modifier = Modifier.weight(1f))
      if (!open) status?.invoke()
      Icon(
        painterResource(if (open) PhonoIcons.ChevronDown else PhonoIcons.ChevronRight),
        null,
        tint = colors.ink,
        modifier = Modifier.size(PhonoSizes.iconBar),
      )
    }
    if (open) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(start = PhonoSpacing.gutter, end = PhonoSpacing.gutter, bottom = PhonoSpacing.s20)
          .testTag(LessonTags.stepBody(step)),
        verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
      ) { body() }
    }
  }
}

private val StepRing = 30.dp

private fun stepTitle(step: LessonStep): Int = when (step) {
  LessonStep.LISTEN -> R.string.lesson_step_listen
  LessonStep.COMPARE -> R.string.lesson_step_compare
  LessonStep.WHY -> R.string.lesson_step_why
  LessonStep.SEE -> R.string.lesson_step_see
  LessonStep.PRACTICE -> R.string.lesson_step_practice
}

// --- step 1 --------------------------------------------------------------------------------------------------------

/** "✓ played 2×" on the closed Listen step; before any play, "Play with “you”" across a boundary. */
@Composable
private fun ListenStatus(listen: ListenUi) {
  val colors = PhonoTheme.colors
  when {
    listen.played > 0 -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
      Icon(painterResource(PhonoIcons.StepDone), null, tint = colors.ink2, modifier = Modifier.size(PhonoSizes.iconInline))
      PhonoText(stringResource(R.string.lesson_played, listen.played), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
    }
    listen.nextText != null ->
      PhonoText(stringResource(R.string.lesson_play_with, listen.nextText), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
  }
}

private val ListenSpeeds = listOf(0.5f, 0.75f, 1f)

/** Step 1 (design 9.11): the word, the word with the next one across a boundary, the phrase; speed, loop, track. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListenStep(listen: ListenUi, word: String, actions: LessonActions) {
  val colors = PhonoTheme.colors
  if (!listen.hasAudio) {
    Notice(stringResource(R.string.lesson_no_audio), kind = NoticeKind.CAUTION, modifier = Modifier.testTag(LessonTags.NO_AUDIO))
  }
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
  ) {
    PhonoButton(
      text = stringResource(R.string.lesson_play_word),
      onClick = { actions.onPlay(ListenTarget.WORD) },
      kind = PhonoButtonKind.PRIMARY,
      enabled = listen.hasAudio,
      leadingIcon = PhonoIcons.Play,
      busy = listen.playing == ListenTarget.WORD,
      modifier = Modifier.testTag(LessonTags.PLAY_WORD),
    )
    listen.nextText?.let { next ->
      PhonoButton(
        text = stringResource(R.string.lesson_play_with, next),
        onClick = { actions.onPlay(ListenTarget.WITH_NEXT) },
        kind = PhonoButtonKind.SECONDARY,
        enabled = listen.hasAudio,
        leadingIcon = PhonoIcons.Play,
        busy = listen.playing == ListenTarget.WITH_NEXT,
        modifier = Modifier.testTag(LessonTags.PLAY_WITH_NEXT),
      )
    }
    PhonoButton(
      text = stringResource(R.string.lesson_play_phrase),
      onClick = { actions.onPlay(ListenTarget.PHRASE) },
      kind = PhonoButtonKind.SECONDARY,
      enabled = listen.hasAudio,
      leadingIcon = PhonoIcons.Play,
      busy = listen.playing == ListenTarget.PHRASE,
      modifier = Modifier.testTag(LessonTags.PLAY_PHRASE),
    )
  }
  listen.nextText?.let { next ->
    PhonoText(
      stringResource(R.string.lesson_crossing, word, next),
      style = MaterialTheme.typography.bodyMedium,
      color = colors.ink2,
      modifier = Modifier.testTag(LessonTags.CROSSING),
    )
  }
  Labelled(stringResource(R.string.lesson_speed)) {
    SegmentedControl(
      options = ListenSpeeds.map { SegmentedOption(stringResource(R.string.lesson_speed_option, speedWords(it))) },
      selectedIndex = ListenSpeeds.indexOf(listen.speed).coerceAtLeast(0),
      onSelect = { actions.onSpeed(ListenSpeeds[it]) },
      enabled = listen.hasAudio,
      modifier = Modifier.fillMaxWidth().testTag(LessonTags.SPEED),
    )
  }
  PhonoSwitch(
    label = stringResource(R.string.lesson_loop),
    checked = listen.loop,
    onCheckedChange = actions.onLoop,
    enabled = listen.hasAudio,
    modifier = Modifier.testTag(LessonTags.LOOP),
  )
  if (listen.dialogue) {
    Labelled(stringResource(R.string.lesson_track)) {
      SegmentedControl(
        options = listOf(
          SegmentedOption(stringResource(R.string.lesson_track_mix), PhonoIcons.Track),
          SegmentedOption(stringResource(R.string.lesson_track_dialogue)),
        ),
        selectedIndex = if (listen.track == Track.DIALOGUE) 1 else 0,
        onSelect = { actions.onTrack(if (it == 1) Track.DIALOGUE else Track.MIX) },
        enabled = listen.hasAudio,
        modifier = Modifier.fillMaxWidth().testTag(LessonTags.TRACK),
      )
    }
  }
}

/** A control under its label (a segmented control's purpose, read before its options). */
@Composable
internal fun Labelled(label: String, content: @Composable () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s6)) {
    PhonoText(label, style = MaterialTheme.typography.labelLarge, color = PhonoTheme.colors.ink2)
    content()
  }
}

/** 0.75 → "0.75", 1 → "1". */
internal fun speedWords(speed: Float): String = java.math.BigDecimal(speed.toString()).stripTrailingZeros().toPlainString()

// --- step 3 --------------------------------------------------------------------------------------------------------

/** Step 3 (design 9.11): notices first, one card per change (or why there is none), then the footer facts. */
@Composable
private fun WhyStep(why: WhyUi, actions: LessonActions, explainer: Explainer) {
  val colors = PhonoTheme.colors
  when (why.reliability) {
    Reliability.NOTHING_HEARD -> Notice(
      stringResource(R.string.lesson_nothing_heard_body),
      kind = NoticeKind.CAUTION,
      lead = stringResource(R.string.lesson_nothing_heard_title),
      modifier = Modifier.testTag(LessonTags.NOTICE_NOTHING_HEARD),
    )
    Reliability.LOW_CONFIDENCE -> Notice(
      stringResource(R.string.lesson_low_confidence_body),
      kind = NoticeKind.CAUTION,
      lead = stringResource(R.string.lesson_low_confidence),
      modifier = Modifier.testTag(LessonTags.NOTICE_LOW_CONFIDENCE),
      action = if (explainer.hasTerm(LOW_CONFIDENCE_KEY)) {
        { TermButton(LOW_CONFIDENCE_KEY, stringResource(R.string.lesson_low_confidence), explainer) }
      } else {
        null
      },
    )
    Reliability.OK -> Unit
  }
  if (why.cards.isEmpty()) {
    val words = when (why.noChange) {
      NoChange.NO_DICTIONARY -> R.string.lesson_no_change_dictionary
      NoChange.UNLABELED -> R.string.lesson_no_change_unlabeled
      NoChange.SAME, null -> R.string.lesson_no_change_same
    }
    PhonoText(stringResource(words), style = MaterialTheme.typography.bodyLarge, color = colors.ink, modifier = Modifier.testTag(LessonTags.NO_CHANGE))
  }
  why.cards.forEach { card ->
    ChangeCard(card, if (why.formScoringOnCards && card.fromScoring) why.formScoring else null, actions, explainer)
  }
  if (!why.formScoringOnCards) why.formScoring?.let { FormScoringLine(it) }
  WhyFacts(why, explainer)
}

private const val LOW_CONFIDENCE_KEY = "low-confidence"

/** A "?" for a glossary term, which takes the focus back when its sheet closes (F-14-AC1). */
@Composable
internal fun TermButton(key: String, term: String, explainer: Explainer, modifier: Modifier = Modifier) {
  val id = explainer.termKey(key)
  ExplainButton(term = term, onClick = { explainer.open(id) }, modifier = modifier.focusRequester(explainer.requester(id)))
}

@Composable
private fun ChangeCard(card: ChangeCardUi, scoring: FormScoringUi?, actions: LessonActions, explainer: Explainer) {
  val colors = PhonoTheme.colors
  val change = card.change
  PhonoCard(modifier = Modifier.fillMaxWidth().testTag(LessonTags.card(change.label.value))) {
    Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s10)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
        Icon(
          painterResource(MarkLines.iconOf(change.family)),
          null,
          tint = colors.family.of(change.family),
          modifier = Modifier.size(PhonoSizes.iconBar),
        )
        PhonoText(
          change.name,
          style = MaterialTheme.typography.titleMedium,
          color = colors.ink,
          modifier = Modifier.weight(1f, fill = false).semantics { heading() },
        )
        if (card.description != null) {
          val id = explainer.changeKey(change.label)
          ExplainButton(term = change.name, onClick = { explainer.open(id) }, modifier = Modifier.focusRequester(explainer.requester(id)))
        }
      }
      card.description?.let { IpaText(it, style = MaterialTheme.typography.bodyMedium) }
      card.linkType?.let { type ->
        linkWords(type)?.let { words ->
          PhonoText(stringResource(R.string.lesson_link_kind, words), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
        }
      }
      card.lexicalForm?.let { form ->
        val text = card.lexicalExpansion?.let { stringResource(R.string.lesson_reduced_form_expansion, form, it) }
          ?: stringResource(R.string.lesson_reduced_form, form)
        PhonoText(text, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
      }
      change.advice?.let { advice ->
        PracticeBadge(advice.advice, register = registerWord(advice.register))
        if (advice.why.isNotBlank()) {
          PhonoText(advice.why, style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
        }
      }
      if (card.fromScoring) {
        Row(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
          Icon(painterResource(PhonoIcons.Info), null, tint = colors.ink, modifier = Modifier.padding(top = 2.dp).size(PhonoSizes.iconInline))
          PhonoText(stringResource(R.string.lesson_from_scoring), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
        }
        scoring?.let { FormScoringLine(it) }
      }
      PhonoButton(
        text = stringResource(R.string.lesson_watch_more),
        onClick = { actions.onWatchMore(change.label) },
        kind = PhonoButtonKind.SECONDARY,
        leadingIcon = PhonoIcons.TabWatch,
        modifier = Modifier.testTag(LessonTags.watchMore(change.label.value)),
      )
      LinkButton(
        text = stringResource(R.string.lesson_learn_about, change.name.replaceFirstChar { it.lowercase(Locale.ROOT) }),
        onClick = { actions.onOpenChange(change.label) },
        trailingArrow = true,
        modifier = Modifier.testTag(LessonTags.learnAbout(change.label.value)),
      )
    }
  }
}

@Composable
private fun linkWords(type: LinkType): String? = when (type) {
  LinkType.CONSONANT -> stringResource(R.string.lesson_link_consonant)
  LinkType.R -> stringResource(R.string.lesson_link_r)
  LinkType.GLIDE_W -> stringResource(R.string.lesson_link_glide_w)
  LinkType.GLIDE_J -> stringResource(R.string.lesson_link_glide_j)
  LinkType.UNKNOWN -> null
}

/** espeak's form scoring (web `FormScoring`): which form scored best, by how much, and the dropped /h/. */
@Composable
private fun FormScoringLine(form: FormScoringUi) {
  val kind = stringResource(if (form.weak) R.string.lesson_form_weak else R.string.lesson_form_strong)
  val base = form.weakMargin?.let { margin ->
    stringResource(
      R.string.lesson_form_scoring_margin,
      kind,
      form.ipa,
      stringResource(if (margin >= 0) R.string.lesson_form_ahead else R.string.lesson_form_behind),
      String.format(Locale.ROOT, "%.1f", kotlin.math.abs(margin)),
      form.strongIpa,
    )
  } ?: stringResource(R.string.lesson_form_scoring, kind, form.ipa)
  val text = if (form.hDropped) "$base · ${stringResource(R.string.lesson_form_h_dropped)}." else "$base."
  IpaText(text, style = MaterialTheme.typography.bodyMedium, color = PhonoTheme.colors.ink2, modifier = Modifier.testTag(LessonTags.FORM_SCORING))
}

/** The footer facts of step 3 (web): prominence, divergence, not in the dictionary, approximate timing, attraction. */
@Composable
private fun WhyFacts(why: WhyUi, explainer: Explainer) {
  val colors = PhonoTheme.colors
  Column(Modifier.fillMaxWidth().testTag(LessonTags.FACTS), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s6)) {
    why.prominence?.let { percent ->
      val classWords = why.wordClass?.let { wordClass ->
        when (wordClass) {
          WordClass.CONTENT -> stringResource(R.string.lesson_content_word)
          WordClass.FUNCTION -> stringResource(R.string.lesson_function_word)
          WordClass.UNKNOWN -> null
        }
      }
      val value = listOfNotNull(stringResource(R.string.lesson_prominence_value, percent), classWords).joinToString(" · ")
      FactRow(stringResource(R.string.lesson_prominence), PROMINENCE_KEY, value, explainer)
      if (why.functionPeak) {
        PhonoText(stringResource(R.string.lesson_function_peak), style = MaterialTheme.typography.bodySmall, color = colors.ink2)
      }
    }
    val divergence = why.divergence?.let { stringResource(R.string.lesson_divergence_value, String.format(Locale.ROOT, "%.2f", it)) }
      ?: stringResource(R.string.lesson_divergence_none)
    FactRow(stringResource(R.string.lesson_divergence), DIVERGENCE_KEY, divergence, explainer)
    if (why.oov) Fact(stringResource(R.string.lesson_oov))
    if (why.alignmentFallback) Fact(stringResource(R.string.lesson_fallback))
    if (why.attracted > 0) Fact(pluralStringResource(R.plurals.lesson_attracted, why.attracted, why.attracted))
  }
}

private const val PROMINENCE_KEY = "prominence"
private const val DIVERGENCE_KEY = "divergence"

@Composable
private fun FactRow(label: String, key: String, value: String, explainer: Explainer) {
  val colors = PhonoTheme.colors
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
    PhonoText(label, style = MaterialTheme.typography.bodySmall, color = colors.ink2)
    if (explainer.hasTerm(key)) TermButton(key, label, explainer)
    PhonoText(value, style = MaterialTheme.typography.bodySmall, color = colors.ink, modifier = Modifier.weight(1f))
  }
}

@Composable
private fun Fact(text: String) {
  PhonoText("• $text", style = MaterialTheme.typography.bodySmall, color = PhonoTheme.colors.ink2)
}

/** A change's "What's this?" (spec F-14, design 9.13): its description, the badge, register and why, two ways on. */
@Composable
internal fun ChangeExplainSheet(card: ChangeCardUi, onDismiss: () -> Unit, onWatchMore: () -> Unit, onLearnMore: () -> Unit) {
  val colors = PhonoTheme.colors
  val change = card.change
  PhonoSheet(onDismiss = onDismiss, title = change.name, modifier = Modifier.testTag(LessonTags.CHANGE_SHEET)) {
    Column(Modifier.padding(bottom = PhonoSpacing.s24), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
      PhonoText(change.name, style = MaterialTheme.typography.headlineLarge, color = colors.ink, modifier = Modifier.semantics { heading() })
      card.description?.let { IpaText(it, style = MaterialTheme.typography.bodyLarge) }
      change.advice?.let { advice ->
        PracticeBadge(advice.advice, register = registerWord(advice.register))
        if (advice.why.isNotBlank()) PhonoText(advice.why, style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
      }
      PhonoButton(
        text = stringResource(R.string.lesson_watch_more),
        onClick = onWatchMore,
        kind = PhonoButtonKind.SECONDARY,
        leadingIcon = PhonoIcons.TabWatch,
        modifier = Modifier.testTag(LessonTags.CHANGE_SHEET_WATCH),
      )
      LinkButton(
        text = stringResource(R.string.lesson_learn_more),
        onClick = onLearnMore,
        trailingArrow = true,
        modifier = Modifier.testTag(LessonTags.CHANGE_SHEET_LEARN),
      )
      PhonoButton(text = stringResource(R.string.lesson_close), onClick = onDismiss, kind = PhonoButtonKind.QUIET)
    }
  }
}

// --- step 4 --------------------------------------------------------------------------------------------------------

/**
 * Step 4 (spec F-10, design 9.11). With WP-29's [mouth] the side view follows the playhead over the phrase, or holds a
 * picked sound, with its live caption and "Follow the playback again". Without it (v1-core, the cut fallback) each
 * sound's button names the sound and says what the mouth does, with no drawing; a sound the articulation table lacks
 * is disabled with "No drawing for this sound yet."
 */
@Composable
private fun SeeStep(see: SeeUi, actions: LessonActions, mouth: MouthRenderer?, playheadMs: () -> Long?) {
  val colors = PhonoTheme.colors
  Labelled(stringResource(R.string.lesson_mouth_for)) {
    SegmentedControl(
      options = listOf(
        SegmentedOption(stringResource(R.string.lesson_mouth_said)),
        SegmentedOption(stringResource(R.string.lesson_mouth_dictionary)),
      ),
      selectedIndex = if (see.stream == MouthStream.SAID) 0 else 1,
      onSelect = { actions.onStream(if (it == 0) MouthStream.SAID else MouthStream.DICTIONARY) },
      modifier = Modifier.fillMaxWidth().testTag(LessonTags.MOUTH_STREAM),
    )
  }
  if (mouth != null) MouthDrawing(see, mouth, playheadMs)
  see.sounds.forEach { sound -> MouthSoundButton(sound, held = see.held == sound.index, onClick = { actions.onHoldSound(sound.index) }) }
  if (mouth != null) {
    if (see.held != null) {
      PhonoButton(
        text = stringResource(R.string.lesson_mouth_follow),
        onClick = actions.onFollow,
        kind = PhonoButtonKind.SECONDARY,
        modifier = Modifier.testTag(LessonTags.MOUTH_FOLLOW),
      )
    }
    PhonoText(stringResource(R.string.lesson_mouth_footer), style = MaterialTheme.typography.bodySmall, color = colors.ink2)
  }
}

/** One 48 dp sound button: "[ɾ] voiced alveolar tap" and what the mouth does; held = an ink ring. */
@Composable
private fun MouthSoundButton(sound: MouthSoundUi, held: Boolean, onClick: () -> Unit) {
  val colors = PhonoTheme.colors
  val shape = RoundedCornerShape(PhonoRadius.medium)
  val title = sound.name?.let { stringResource(R.string.lesson_mouth_sound, sound.symbol, it) }
    ?: stringResource(R.string.lesson_said_form, sound.symbol)
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = PhonoSizes.minTarget)
      .alpha(if (sound.drawable) 1f else PhonoColors.DISABLED_ALPHA)
      .clip(shape)
      .background(colors.sheet)
      .border(if (held) 2.dp else PhonoSizes.ring, if (held) colors.ink else colors.lineStrong, shape)
      .clickable(enabled = sound.drawable, role = Role.Button, onClick = onClick)
      .semantics(mergeDescendants = true) { selected = held }
      .testTag(LessonTags.mouthSound(sound.index))
      .padding(horizontal = PhonoSpacing.s12, vertical = PhonoSpacing.s10),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
  ) {
    IpaText(title, style = MaterialTheme.typography.titleMedium)
    val detail = if (sound.drawable) sound.cue else stringResource(R.string.lesson_mouth_no_drawing)
    detail?.let { PhonoText(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink2) }
  }
}

/** The side view of the mouth (WP-29) with its live caption: the held sound, else the sound under the playhead. */
@Composable
private fun MouthDrawing(see: SeeUi, mouth: MouthRenderer, playheadMs: () -> Long?) {
  val colors = PhonoTheme.colors
  val track = androidx.compose.runtime.remember(see.track) {
    mouth.track(see.track, com.mapineda48.phonotrainer.core.domain.model.MouthMode.FOLLOW_AUDIO)
  }
  // The playhead is read every frame while the drawing is on screen (content animation: not reduced, architecture 18).
  val playhead by produceState<Long?>(initialValue = playheadMs(), playheadMs) {
    while (true) withFrameMillis { value = playheadMs() }
  }
  val held = see.held?.let(see.sounds::getOrNull)
  val at = held?.let { (it.startMs + it.endMs) / 2 } ?: playhead ?: see.sounds.firstOrNull()?.startMs ?: 0L
  val current = held ?: see.sounds.firstOrNull { at in it.startMs..it.endMs }
  val description = current?.name?.let { stringResource(R.string.lesson_mouth_view, current.symbol, it) }
    ?: stringResource(R.string.lesson_mouth_rest)
  Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(MouthHeight)
        .clip(RoundedCornerShape(PhonoRadius.sort))
        .background(colors.tract.air)
        .border(PhonoSizes.hairline, colors.hairline, RoundedCornerShape(PhonoRadius.sort))
        .clearAndSetSemantics { contentDescription = description }
        .testTag(LessonTags.MOUTH_DRAWING),
    ) {
      mouth.Mouth(track.frameAt(at), Modifier.fillMaxWidth().height(MouthHeight))
    }
    current?.let { sound ->
      sound.name?.let { name ->
        IpaText(
          stringResource(R.string.lesson_mouth_caption, sound.symbol, name, sound.example.orEmpty()),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }
  }
}

private val MouthHeight = 200.dp

// --- step 5 --------------------------------------------------------------------------------------------------------

/** Step 5 (spec F-10, F-12): WP-18's shadowing panel, then WP-30's "Record yourself" when its module is present. */
@Composable
private fun PracticeStep(practice: PracticeUi, actions: LessonActions, record: RecordSlot?) {
  val options = ShadowingOptions(
    slowSpeed = practice.slowSpeed,
    target = practice.target,
    wordAndNext = practice.wordAndNext,
    unavailableReason = if (practice.hasAudio) null else stringResource(R.string.lesson_no_audio),
    recognizeOnly = practice.recognizeOnly,
  )
  ShadowingPanel(
    state = practice.shadowing,
    onStart = actions.onStartShadowing,
    onStop = actions.onStopShadowing,
    options = options,
    onOptionsChange = { changed -> actions.onPractice(changed.target ?: practice.target, changed.slowSpeed) },
    modifier = Modifier.testTag(LessonTags.PRACTICE_PANEL),
  )
  if (record != null && practice.hasAudio) {
    record(practice.span, practice.recordTarget, Modifier.fillMaxWidth().testTag(LessonTags.RECORD))
  }
}
