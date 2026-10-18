package com.mapineda48.phonotrainer.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.brand.PhonoMarkImage
import com.mapineda48.phonotrainer.core.designsystem.component.CardVariant
import com.mapineda48.phonotrainer.core.designsystem.component.Illustration
import com.mapineda48.phonotrainer.core.designsystem.component.PaperCard
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoBadge
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.paperDepth
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PerforatedTopShape
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.ui.IpaText
import com.mapineda48.phonotrainer.core.ui.MarkedWordSpan
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.PracticeBadge

/** What the Welcome's controls ask for. */
internal class WelcomeActions(
  /** "Next": the following page. */
  val onNext: () -> Unit = {},
  /** System Back on page 2 or 3: the page before. */
  val onPrevious: () -> Unit = {},
  /** "Skip", on every page. */
  val onSkip: () -> Unit = {},
  /** "Get started" (or "Done" when replayed) on the last page. */
  val onFinish: () -> Unit = {},
  /** System Back on the first page of a first launch: leave the Welcome without going on to Connect. */
  val onLeave: () -> Unit = {},
)

/**
 * The Welcome (spec F-01 behaviour 1, design 10.2 P-13, render `23`): the stylus P and the wordmark with "Skip"; a
 * stacked, crop-marked stage with the page's illustration (page 1 adds a sample slip: a change, its advice, a word
 * and how it was really said); the kicker "1 of 3", the title and one sentence; the page indicator and "Next" /
 * "Get started". Pages change with the buttons and with system Back, never with a horizontal swipe (spec D-14's
 * reasons hold here too: the edge Back gesture, TalkBack and switch access). A page change crossfades, or is instant
 * with reduced motion. [restart]: the tour replayed from Settings > Help, which ends with "Done".
 */
@Composable
internal fun WelcomeScreen(
  page: WelcomePage,
  restart: Boolean,
  busy: Boolean,
  actions: WelcomeActions,
  modifier: Modifier = Modifier,
) {
  BackHandler(enabled = page.previous != null, onBack = actions.onPrevious)
  BackHandler(enabled = page.previous == null && !restart && !busy, onBack = actions.onLeave)
  val pane = stringResource(R.string.welcome_pane)
  Column(
    modifier = modifier
      .fillMaxSize()
      .testTag(OnboardingTags.WELCOME)
      .semantics { paneTitle = pane }
      .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
      .padding(horizontal = PhonoSpacing.gutter),
  ) {
    Header(onSkip = actions.onSkip, enabled = !busy)
    Crossfade(
      targetState = page,
      animationSpec = PhonoTheme.motion.standard(),
      modifier = Modifier.weight(1f),
      label = "welcome page",
    ) { shown ->
      Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = PhonoSpacing.s16),
        verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s16),
      ) {
        Stage(shown)
        PageWords(shown)
      }
    }
    Footer(page = page, restart = restart, busy = busy, actions = actions)
  }
}

/** The stylus P and the wordmark "Phono*Trainer*" (read as the app's name), and "Skip". */
@Composable
private fun Header(onSkip: () -> Unit, enabled: Boolean) {
  val colors = PhonoTheme.colors
  val name = stringResource(R.string.welcome_app_name)
  Row(
    modifier = Modifier.fillMaxWidth().padding(top = PhonoSpacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
  ) {
    PhonoMarkImage(size = MARK_SIZE)
    val phono = stringResource(R.string.welcome_wordmark_phono)
    val trainer = stringResource(R.string.welcome_wordmark_trainer)
    Text(
      text = buildAnnotatedString {
        append(phono)
        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(trainer) }
      },
      style = MaterialTheme.typography.headlineMedium,
      color = colors.ink,
      modifier = Modifier.weight(1f).clearAndSetSemantics { contentDescription = name },
    )
    val skipLabel = stringResource(R.string.welcome_skip_label)
    PhonoButton(
      text = stringResource(R.string.welcome_skip),
      onClick = onSkip,
      kind = PhonoButtonKind.QUIET,
      enabled = enabled,
      modifier = Modifier.testTag(OnboardingTags.SKIP).semantics { contentDescription = skipLabel },
    )
  }
}

/** The stage (design 10.2 P-13): a stacked, crop-marked sheet with the page's illustration; page 1's sample slip. */
@Composable
private fun Stage(page: WelcomePage) {
  Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
    PaperCard(variant = CardVariant.STACK, modifier = Modifier.fillMaxWidth()) {
      Illustration(page.illustration)
    }
    if (page == WelcomePage.LISTEN) SampleSlip()
  }
}

/**
 * Page 1's sample slip: "Flapping" with its family icon, "Safe to produce", the word in its mark and its IPA. One
 * sentence for TalkBack, since the pieces only make sense together.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SampleSlip() {
  val colors = PhonoTheme.colors
  val shape = PerforatedTopShape()
  val description = stringResource(R.string.welcome_sample_description)
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = PhonoSpacing.s12)
      .testTag(OnboardingTags.SAMPLE)
      .clearAndSetSemantics { contentDescription = description }
      .paperDepth(colors, shape)
      .clip(shape)
      .background(colors.sheet)
      .padding(PhonoSpacing.s14),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
  ) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s6)) {
      PhonoBadge(text = stringResource(R.string.welcome_sample_change), icon = PhonoIcons.FamilyTd)
      PracticeBadge(Advice.PRODUCE)
    }
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
      itemVerticalAlignment = Alignment.CenterVertically,
    ) {
      MarkedWordSpan(
        text = stringResource(R.string.welcome_sample_word),
        family = Family.TD,
        style = PhonoTheme.type.spotlightWord,
      )
      IpaText(stringResource(R.string.welcome_sample_ipa), style = PhonoTheme.type.ipaSlip, color = colors.ink)
    }
  }
}

/** The kicker "1 of 3" and the title, read together as the page's heading (announced when the page changes). */
@Composable
private fun PageWords(page: WelcomePage) {
  val colors = PhonoTheme.colors
  val kicker = stringResource(R.string.welcome_page_of, page.number, WelcomePage.count)
  val title = stringResource(page.title)
  Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
    Column(
      modifier = Modifier
        .testTag(OnboardingTags.PAGE_TITLE)
        .semantics(mergeDescendants = true) {
          heading()
          liveRegion = LiveRegionMode.Polite
        },
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
    ) {
      PhonoText(kicker.uppercase(), style = MaterialTheme.typography.labelSmall, color = colors.ink)
      PhonoText(title, style = MaterialTheme.typography.displayMedium, color = colors.ink)
    }
    PhonoText(
      stringResource(page.body),
      style = MaterialTheme.typography.bodyLarge,
      color = colors.ink2,
      modifier = Modifier.testTag(OnboardingTags.PAGE_BODY),
    )
  }
}

/** The page indicator and the page's button: "Next", then "Get started" (or "Done" when replayed). */
@Composable
private fun Footer(page: WelcomePage, restart: Boolean, busy: Boolean, actions: WelcomeActions) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
      .padding(vertical = PhonoSpacing.s16),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s16),
  ) {
    Indicator(page, Modifier.weight(1f))
    if (page.isLast) {
      PhonoButton(
        text = stringResource(if (restart) R.string.welcome_done else R.string.welcome_get_started),
        onClick = actions.onFinish,
        large = true,
        busy = busy,
        enabled = !busy,
        trailingIcon = PhonoIcons.ArrowRight,
        modifier = Modifier.testTag(OnboardingTags.GET_STARTED),
      )
    } else {
      PhonoButton(
        text = stringResource(R.string.welcome_next),
        onClick = actions.onNext,
        large = true,
        enabled = !busy,
        trailingIcon = PhonoIcons.ArrowRight,
        modifier = Modifier.testTag(OnboardingTags.NEXT),
      )
    }
  }
}

/** Three marks, the current one a 28 dp ink capsule (design 10.2 P-13). Decorative: the kicker says "1 of 3". */
@Composable
private fun Indicator(page: WelcomePage, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  Row(
    modifier = modifier.testTag(OnboardingTags.INDICATOR).clearAndSetSemantics { },
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    WelcomePage.entries.forEach { each ->
      if (each == page) {
        Box(Modifier.width(CURRENT_WIDTH).height(DOT).clip(CircleShape).background(colors.ink))
      } else {
        Box(Modifier.size(DOT).border(PhonoSizes.ring, colors.ink, CircleShape))
      }
    }
  }
}

private val MARK_SIZE = 36.dp
private val CURRENT_WIDTH = 28.dp
private val DOT = 10.dp
