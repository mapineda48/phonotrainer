package com.mapineda48.phonotrainer.feature.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.mapineda48.phonotrainer.core.designsystem.component.LinkButton
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoProgressBar
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.illustration.PhonoIllustrations
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.AnswerResult
import com.mapineda48.phonotrainer.core.domain.model.Question
import com.mapineda48.phonotrainer.core.ui.EmptyState
import com.mapineda48.phonotrainer.core.ui.EmptyStateAction
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.component.AnswerFeedback
import com.mapineda48.phonotrainer.core.ui.component.AnswerFeedbackState
import com.mapineda48.phonotrainer.core.ui.component.QuestionCard
import com.mapineda48.phonotrainer.core.ui.component.QuestionCardState

/** What a set question can ask for. */
internal data class QuestionCallbacks(
  val onAnswer: (FeedPage.QuestionPage, String) -> Unit = { _, _ -> },
  val onPlay: (Question, slow: Boolean, context: Boolean) -> Unit = { _, _, _ -> },
  val onLesson: (AnswerResult) -> Unit = {},
  val onLearn: (AnswerResult) -> Unit = {},
  val onWatchMore: (AnswerResult) -> Unit = {},
  val onSave: (AnswerResult) -> Unit = {},
  val onNext: () -> Unit = {},
)

/**
 * A set question in the feed (spec F-08 item 5, design 9.14): WP-21's quiz card, then its feedback with "Open the
 * lesson", "Learn about…", "Watch more like this" and "Save" (nothing is saved unless tapped, F-08-AC6), and "Next
 * question". Swiping past a question is allowed and not counted.
 */
@Composable
internal fun QuestionFeedPage(
  page: FeedPage.QuestionPage,
  savedWords: Boolean,
  callbacks: QuestionCallbacks,
  modifier: Modifier = Modifier,
) {
  val colors = PhonoTheme.colors
  Column(
    modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(PhonoSpacing.feedGutter)
      .testTag(WatchTags.QUESTION),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
  ) {
    QuestionCard(
      state = QuestionCardState(page.question, chosen = page.choice, number = page.number, of = page.of),
      onAnswer = { callbacks.onAnswer(page, it) },
      onPlay = { callbacks.onPlay(page.question, false, false) },
      onSlow = { callbacks.onPlay(page.question, true, false) },
      onContext = { callbacks.onPlay(page.question, false, true) },
    )
    val result = page.result
    if (result != null) {
      AnswerFeedback(
        state = AnswerFeedbackState(result, saved = savedWords),
        onLesson = { callbacks.onLesson(result) },
        onLearn = { callbacks.onLearn(result) },
        onWatchMore = { callbacks.onWatchMore(result) },
        onSave = { callbacks.onSave(result) },
      )
      PhonoButton(
        text = stringResource(R.string.watch_next_question),
        onClick = callbacks.onNext,
        trailingIcon = PhonoIcons.ArrowRight,
        fillWidth = true,
        modifier = Modifier.testTag(WatchTags.NEXT_QUESTION),
      )
    } else {
      PhonoText(stringResource(R.string.watch_question_skip_note), style = MaterialTheme.typography.bodySmall, color = colors.ink2)
    }
  }
}

/** Where an empty Watch can send the learner. */
internal data class EmptyCallbacks(
  val onConnect: () -> Unit = {},
  val onAddClip: () -> Unit = {},
  val onLearn: () -> Unit = {},
  val onWatchMix: () -> Unit = {},
)

/** The empty states of Watch (spec F-06 States, design 9.8 and 9.21; illustration `ill_feed_empty`). */
@Composable
internal fun EmptyFeed(empty: FeedEmpty, callbacks: EmptyCallbacks, modifier: Modifier = Modifier) {
  Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(WatchTags.EMPTY), contentAlignment = Alignment.TopCenter) {
    when (empty) {
      FeedEmpty.FirstRun -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(
          illustration = PhonoIllustrations.FeedEmpty,
          title = stringResource(R.string.watch_empty_first_run_title),
          body = stringResource(R.string.watch_empty_first_run_body),
          actions = listOf(
            EmptyStateAction(stringResource(R.string.watch_empty_connect), callbacks.onConnect),
            EmptyStateAction(stringResource(R.string.watch_empty_add_clip), callbacks.onAddClip, PhonoButtonKind.SECONDARY),
          ),
        )
        LinkButton(
          text = stringResource(R.string.watch_empty_learn),
          onClick = callbacks.onLearn,
          trailingArrow = true,
          modifier = Modifier.testTag(WatchTags.EMPTY_LEARN),
        )
      }
      is FeedEmpty.ClipsComing -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(
          illustration = PhonoIllustrations.FeedEmpty,
          title = stringResource(R.string.watch_empty_coming_title),
          body = stringResource(R.string.watch_empty_coming_body),
        )
        empty.percent?.let { percent ->
          PhonoProgressBar(
            progress = percent / 100f,
            label = stringResource(R.string.watch_empty_coming_progress, percent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = PhonoSpacing.gutter),
          )
        }
      }
      is FeedEmpty.NoShortsWithChange -> EmptyState(
        illustration = PhonoIllustrations.NoExamples,
        title = stringResource(R.string.watch_empty_change_title, empty.name),
        body = stringResource(R.string.watch_empty_change_body),
        actions = listOf(EmptyStateAction(stringResource(R.string.watch_empty_watch_mix), callbacks.onWatchMix)),
      )
      FeedEmpty.NothingSaved -> EmptyState(
        illustration = PhonoIllustrations.SavedEmpty,
        title = stringResource(R.string.watch_empty_saved_title),
        body = stringResource(R.string.watch_empty_saved_body),
        actions = listOf(EmptyStateAction(stringResource(R.string.watch_empty_watch_mix), callbacks.onWatchMix)),
      )
      FeedEmpty.ClipNotHere -> EmptyState(
        illustration = PhonoIllustrations.FeedEmpty,
        title = stringResource(R.string.watch_empty_clip_title),
        body = stringResource(R.string.watch_empty_clip_body),
        actions = listOf(EmptyStateAction(stringResource(R.string.watch_empty_watch_mix), callbacks.onWatchMix)),
      )
    }
  }
}
