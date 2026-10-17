package com.mapineda48.phonotrainer.feature.watch

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.LinkButton
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoBadge
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.theme.inCharis
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.CaptionMode
import com.mapineda48.phonotrainer.core.domain.model.Contour
import com.mapineda48.phonotrainer.core.domain.model.UnitType
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.ui.ExplainButton
import com.mapineda48.phonotrainer.core.ui.MarkedWordSpan
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.WordMarkState
import com.mapineda48.phonotrainer.core.ui.a11y.LocalReducedMotion

/**
 * The captions of the short on screen (spec F-07 items 4 and 5, design 9.4): the previous phrase dimmed, the current
 * phrase with every word a 48 dp button wearing its mark, the next phrase dimmed, and the intonation badge closing the
 * current phrase. When the current phrase does not fit (a long phrase, large text, IPA), the dimmed phrases go first
 * ([compact]) and the area scrolls, following the word being said line by line (a jump with reduced motion); paused,
 * the learner scrolls it. Nothing is cut mid-word (A-06, F-07-AC9).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Captions(
  content: ShortContent,
  playhead: State<Long>,
  mode: CaptionMode,
  listenFirst: Boolean,
  revealed: Set<Int>,
  compact: Boolean,
  onWord: (WordRef) -> Unit,
  onLoopPhrase: (Int) -> Unit,
  onShowWords: () -> Unit,
  onShowCaptions: () -> Unit,
  onExplain: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = PhonoTheme.colors
  if (mode == CaptionMode.OFF) {
    Row(
      modifier = modifier.fillMaxWidth().testTag(WatchTags.CAPTIONS_OFF),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
    ) {
      Icon(painterResource(PhonoIcons.Captions), null, tint = colors.ink2)
      PhonoText(stringResource(R.string.watch_captions_off), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
      LinkButton(
        text = stringResource(R.string.watch_captions_show),
        onClick = onShowCaptions,
        modifier = Modifier.testTag(WatchTags.CAPTIONS_SHOW),
      )
    }
    return
  }
  val current by remember(content) { derivedStateOf { content.phraseAt(playhead.value) } }
  val phrase = current ?: return
  val index = content.phrases.indexOf(phrase)
  val previous = content.phrases.getOrNull(index - 1)
  val next = content.phrases.getOrNull(index + 1)
  val hidden = listenFirst && phrase.segment !in revealed
  val scroll = rememberScrollState()
  val wordTops = remember(phrase) { mutableStateMapOf<Int, Int>() }
  val viewportTop = remember { mutableFloatStateOf(0f) }
  val lineHeight = with(LocalDensity.current) { PhonoSizes.minTarget.roundToPx() }
  val playing by remember(phrase) { derivedStateOf { FeedLogic.playingWord(phrase, playhead.value) } }
  val reduced = LocalReducedMotion.current
  // Follow the word being said line by line (design 9.4): when it leaves the visible lines, scroll its line to the top.
  // Its top is read here, so the effect runs again once the word has been laid out.
  val target = playing?.let { wordTops[it] }
  LaunchedEffect(target, phrase) {
    val top = target ?: return@LaunchedEffect
    val viewport = scroll.viewportSize
    if (viewport <= 0) return@LaunchedEffect
    if (top < scroll.value || top + lineHeight > scroll.value + viewport) {
      if (reduced) scroll.scrollTo(top) else scroll.animateScrollTo(top)
    }
  }
  Column(
    modifier = modifier
      .fillMaxWidth()
      .onGloballyPositioned { viewportTop.floatValue = it.positionInRoot().y }
      .verticalScroll(scroll)
      .testTag(WatchTags.CAPTIONS),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
  ) {
    if (!compact && previous != null) {
      DimPhrase(previous, Modifier.testTag(WatchTags.CAPTION_PREVIOUS))
    }
    if (hidden) {
      ListenFirstLine(onShowWords)
    } else {
      FlowRow(
        modifier = Modifier
          .fillMaxWidth()
          .testTag(WatchTags.CAPTION_CURRENT),
        verticalArrangement = Arrangement.Center,
      ) {
        phrase.words.forEachIndexed { i, word ->
          CaptionWordButton(
            word = word,
            mode = mode,
            playing = playing == i,
            onClick = { onWord(word.ref) },
            onLoop = { onLoopPhrase(phrase.segment) },
            modifier = Modifier.onGloballyPositioned {
              wordTops[i] = (it.positionInRoot().y - viewportTop.floatValue).toInt() + scroll.value
            },
          )
        }
        phrase.badge?.let { IntonationBadgeChip(it, onExplain) }
      }
    }
    if (!compact && next != null) {
      DimPhrase(next, Modifier.testTag(WatchTags.CAPTION_NEXT))
    }
  }
}

@Composable
private fun DimPhrase(phrase: CaptionPhrase, modifier: Modifier = Modifier) {
  PhonoText(
    phrase.words.joinToString(" ") { it.text },
    style = PhonoTheme.type.captionDim,
    color = PhonoTheme.colors.inkMuted,
    modifier = modifier,
  )
}

/** "Listen first: the words appear after it plays" + "Show the words": one line, never one slot per word (F-07-AC1). */
@Composable
private fun ListenFirstLine(onShowWords: () -> Unit) {
  val colors = PhonoTheme.colors
  Column(
    modifier = Modifier.fillMaxWidth().testTag(WatchTags.LISTEN_FIRST),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
      Icon(painterResource(PhonoIcons.ListenFirst), null, tint = colors.ink)
      PhonoText(
        stringResource(R.string.watch_listen_first_line),
        style = PhonoTheme.type.captionCurrent,
        color = colors.ink,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
      )
    }
    PhonoButton(
      text = stringResource(R.string.watch_show_words),
      onClick = onShowWords,
      kind = PhonoButtonKind.QUIET,
      modifier = Modifier.testTag(WatchTags.SHOW_WORDS),
    )
  }
}

/** The words TalkBack reads for a caption word (A-04): "water, flapping, safe to produce, listen for". */
@Composable
internal fun wordDescription(word: CaptionWord): String {
  val parts = buildList {
    add(word.text)
    addAll(word.changes)
    when (word.advice) {
      Advice.PRODUCE -> add(stringResource(R.string.watch_advice_produce))
      Advice.UNDERSTAND -> add(stringResource(R.string.watch_advice_understand))
      null -> Unit
    }
    if (word.spotlit) add(stringResource(R.string.watch_word_spotlight))
    if (word.lowConfidence) add(stringResource(R.string.watch_word_low_confidence))
    if (word.stressed) add(stringResource(R.string.watch_word_stressed))
  }
  return parts.joinToString(", ")
}

/**
 * One caption word: a ≥ 48 dp button (tap: pause and open its lesson; long press: loop its phrase) drawing the word
 * with its family's mark, the playing fill, the low-confidence outline, the stress and, on the studied word, the
 * tether's caret above it (design 9.3, 9.4). IPA mode adds what was said under it and, for a marked word, the
 * dictionary form.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CaptionWordButton(
  word: CaptionWord,
  mode: CaptionMode,
  playing: Boolean,
  onClick: () -> Unit,
  onLoop: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = PhonoTheme.colors
  val description = wordDescription(word)
  val openLabel = stringResource(R.string.watch_open_lesson_label)
  val loopLabel = stringResource(R.string.watch_loop_this_phrase)
  val state = when {
    playing -> WordMarkState.PLAYING
    word.lowConfidence -> WordMarkState.LOW_CONFIDENCE
    else -> WordMarkState.NORMAL
  }
  val caret = colors.ink
  Column(
    modifier = modifier
      .defaultMinSize(minWidth = PhonoSizes.minTarget, minHeight = PhonoSizes.minTarget)
      .combinedClickable(onClick = onClick, onLongClick = onLoop, role = Role.Button)
      .testTag(WatchTags.word(word.ref.segment, word.ref.word))
      .clearAndSetSemantics {
        contentDescription = description
        role = Role.Button
        onClick(label = openLabel) { onClick(); true }
        customActions = listOf(CustomAccessibilityAction(loopLabel) { onLoop(); true })
      }
      .then(
        if (word.spotlit) {
          Modifier.drawBehind {
            val s = 5.dp.toPx()
            val cx = size.width / 2f
            val path = Path().apply {
              moveTo(cx, 0f)
              lineTo(cx + s, s)
              lineTo(cx, 2 * s)
              lineTo(cx - s, s)
              close()
            }
            drawPath(path, caret)
          }.padding(top = 10.dp)
        } else {
          Modifier
        },
      ),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    MarkedWordSpan(
      text = word.text,
      family = if (mode == CaptionMode.PLAIN) null else word.family,
      style = PhonoTheme.type.captionCurrent,
      state = state,
      stressed = word.stressed,
      linksToNext = word.linksToNext,
    )
    if (mode == CaptionMode.IPA) {
      word.realizedIpa?.let {
        PhonoText(stringResource(R.string.watch_ipa_said, it), style = MaterialTheme.typography.bodyMedium.inCharis(), color = colors.ink)
      }
      if (word.family != null) {
        word.dictIpa?.let {
          PhonoText(stringResource(R.string.watch_dictionary, it), style = MaterialTheme.typography.bodySmall.inCharis(), color = colors.ink2)
        }
      }
    }
    if (mode == CaptionMode.MARKS) {
      word.contraction?.let {
        Box(Modifier.padding(top = PhonoSpacing.s2)) {
          PhonoBadge(it, icon = PhonoIcons.FamilyLexical, modifier = Modifier.testTag(WatchTags.CONTRACTION))
        }
      }
    }
  }
}

/** "Question · rises", "usually falls" when the expected contour differs, the uptalk chip with "What's this?". */
@Composable
private fun IntonationBadgeChip(badge: IntonationBadge, onExplain: (String) -> Unit) {
  val type = stringResource(
    when (badge.type) {
      UnitType.STATEMENT -> R.string.watch_intonation_statement
      UnitType.YES_NO_QUESTION, UnitType.WH_QUESTION -> R.string.watch_intonation_question
      UnitType.EXCLAMATION -> R.string.watch_intonation_exclamation
      UnitType.INCOMPLETE -> R.string.watch_intonation_unfinished
      UnitType.UNKNOWN -> R.string.watch_intonation_phrase
    },
  )
  val contour = contourWords(badge.contour)
  val icon = when (badge.contour) {
    Contour.RISING -> PhonoIcons.IntonationRises
    Contour.FALLING -> PhonoIcons.IntonationFalls
    else -> PhonoIcons.IntonationFlat
  }
  Row(
    modifier = Modifier.defaultMinSize(minHeight = PhonoSizes.minTarget).testTag(WatchTags.INTONATION),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
  ) {
    PhonoBadge(stringResource(R.string.watch_intonation_badge, type, contour), icon = icon)
    badge.expected?.let {
      PhonoBadge(stringResource(R.string.watch_intonation_usually, contourWords(it)), dashed = true)
    }
    if (badge.uptalk) {
      val uptalk = stringResource(R.string.watch_intonation_uptalk)
      PhonoBadge(uptalk)
      ExplainButton(term = uptalk, onClick = { onExplain(UPTALK_KEY) })
    }
  }
}

@Composable
private fun contourWords(contour: Contour?): String = stringResource(
  when (contour) {
    Contour.RISING -> R.string.watch_intonation_rises
    Contour.FALLING -> R.string.watch_intonation_falls
    else -> R.string.watch_intonation_flat
  },
)

/** The glossary key of "uptalk" ("What's this?", spec F-14). */
internal const val UPTALK_KEY = "uptalk"
