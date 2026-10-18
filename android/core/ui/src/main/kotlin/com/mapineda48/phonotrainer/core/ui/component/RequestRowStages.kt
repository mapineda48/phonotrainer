package com.mapineda48.phonotrainer.core.ui.component

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.mapineda48.phonotrainer.core.designsystem.component.StepState
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import com.mapineda48.phonotrainer.core.ui.R

/** One stage of the analysis as a request shows it (spec F-20 item 2; design 9.19): its state and its detail. */
@Immutable
data class RequestStageState(val stage: Stage, val state: StepState, val detail: String? = null)

/**
 * The analysis stages of a request on the computer (spec F-20 item 2, the web's stepper `web/src/jobs/stages.ts`;
 * design 9.19): Download (links only), Extract the audio, Separate the dialogue, Transcribe the words, Hear the phones,
 * Compare and save, each with its short name under the stepper's node, its teaching line and its status word. The
 * row's stepper and the request detail's stage rows read them from here, so both say the same.
 */
object RequestRowStages {
  /** The stages a request of [kind] goes through: a file has no download. */
  fun stagesOf(kind: RequestKind): List<Stage> =
    if (kind == RequestKind.LINK) LINK_STAGES else LINK_STAGES.drop(1)

  /**
   * Each stage of [request] with its state: all next while it is sent or waiting in line (and while analyzing before
   * the computer named a stage), done up to the current one while analyzing, all done once ready. "phrase 3 of 10" is
   * the current Hear-the-phones stage's detail, the download's percent the Download stage's.
   */
  fun of(request: Request, resources: Resources): List<RequestStageState> {
    val stages = stagesOf(request.kind)
    val finished = request.state == RequestState.READY_ON_COMPUTER || request.state == RequestState.GETTING_ON_PHONE ||
      request.state == RequestState.ON_PHONE
    if (finished) return stages.map { RequestStageState(it, StepState.DONE) }
    val current = currentIndex(request)
    return stages.mapIndexed { index, stage ->
      when {
        current == null || index > current -> RequestStageState(stage, StepState.NEXT)
        index < current -> RequestStageState(stage, StepState.DONE)
        else -> RequestStageState(stage, StepState.CURRENT, detailOf(request, stage, resources))
      }
    }
  }

  /** The index of the stage the computer is working on, or null while nothing has started (or it is not analyzing). */
  fun currentIndex(request: Request): Int? {
    val analyzing = request.state == RequestState.ANALYZING ||
      (request.state == RequestState.CANCEL_WAITING && request.progress?.stage != null)
    if (!analyzing) return null
    val stage = request.progress?.stage ?: return null
    return stagesOf(request.kind).indexOf(stage).takeIf { it >= 0 }
  }

  /** The stage being worked on, or null. */
  fun current(request: Request): Stage? = currentIndex(request)?.let { stagesOf(request.kind)[it] }

  private fun detailOf(request: Request, stage: Stage, resources: Resources): String? {
    val progress = request.progress ?: return null
    return when (stage) {
      Stage.PHONES -> {
        val segment = progress.segment
        val segments = progress.segments
        if (segment != null && segments != null && segments > 0) {
          resources.getString(R.string.request_phrase_of, segment, segments)
        } else {
          null
        }
      }
      Stage.DOWNLOAD -> progress.downloadPercent?.let { "${it.coerceIn(0, PERCENT)} %" }
      else -> null
    }
  }

  /** The full name: "Hear the phones". */
  @StringRes
  fun name(stage: Stage): Int = when (stage) {
    Stage.DOWNLOAD -> R.string.request_stage_download
    Stage.EXTRACT -> R.string.request_stage_extract
    Stage.SEPARATE -> R.string.request_stage_separate
    Stage.TRANSCRIBE -> R.string.request_stage_transcribe
    Stage.PHONES -> R.string.request_stage_phones
    Stage.SAVE, Stage.UNKNOWN -> R.string.request_stage_save
  }

  /** The name under the stepper's node: "Phones". */
  @StringRes
  fun shortName(stage: Stage): Int = when (stage) {
    Stage.DOWNLOAD -> R.string.request_stage_short_download
    Stage.EXTRACT -> R.string.request_stage_short_extract
    Stage.SEPARATE -> R.string.request_stage_short_separate
    Stage.TRANSCRIBE -> R.string.request_stage_short_transcribe
    Stage.PHONES -> R.string.request_stage_short_phones
    Stage.SAVE, Stage.UNKNOWN -> R.string.request_stage_short_save
  }

  /** What happens in the stage, for someone who has never seen a pipeline (the web's `TEACHES`). */
  @StringRes
  fun teaching(stage: Stage): Int = when (stage) {
    Stage.DOWNLOAD -> R.string.request_teach_download
    Stage.EXTRACT -> R.string.request_teach_extract
    Stage.SEPARATE -> R.string.request_teach_separate
    Stage.TRANSCRIBE -> R.string.request_teach_transcribe
    Stage.PHONES -> R.string.request_teach_phones
    Stage.SAVE, Stage.UNKNOWN -> R.string.request_teach_save
  }

  /** "done", "in progress", "next", "skipped", "failed", "cancelled". */
  @StringRes
  fun statusWord(state: StepState): Int = when (state) {
    StepState.DONE -> R.string.request_stage_status_done
    StepState.CURRENT -> R.string.request_stage_status_current
    StepState.NEXT -> R.string.request_stage_status_next
    StepState.SKIPPED -> R.string.request_stage_status_skipped
    StepState.FAILED -> R.string.request_stage_status_failed
    StepState.CANCELLED -> R.string.request_stage_status_cancelled
  }

  /** The stepper as TalkBack reads it: "Step 5 of 6: Hear the phones, in progress.", or not started, or all done. */
  fun description(stages: List<RequestStageState>, resources: Resources): String {
    val index = stages.indexOfFirst { it.state == StepState.CURRENT }
    return when {
      index >= 0 -> resources.getString(
        R.string.request_stepper_current,
        index + 1,
        stages.size,
        resources.getString(name(stages[index].stage)),
      )
      stages.isNotEmpty() && stages.all { it.state == StepState.DONE } ->
        resources.getQuantityString(R.plurals.request_stepper_done, stages.size, stages.size)
      else -> resources.getQuantityString(R.plurals.request_stepper_waiting, stages.size, stages.size)
    }
  }

  /** The lead of the teaching notice: "Step 5 of 6 · phrase 3 of 10." (design 9.19); null when nothing runs. */
  fun teachingLead(stages: List<RequestStageState>, resources: Resources): String? {
    val index = stages.indexOfFirst { it.state == StepState.CURRENT }
    if (index < 0) return null
    val step = resources.getString(R.string.request_step_of, index + 1, stages.size)
    val detail = stages[index].detail?.takeIf { stages[index].stage == Stage.PHONES }
    return if (detail == null) "$step." else "$step · $detail."
  }

  /** The polite announcement of a stage change (spec F-20 item 5): "Now: Transcribe the words." */
  fun announcement(stage: Stage, resources: Resources): String =
    resources.getString(R.string.request_stage_announcement, resources.getString(name(stage)))

  private const val PERCENT = 100

  private val LINK_STAGES = listOf(Stage.DOWNLOAD, Stage.EXTRACT, Stage.SEPARATE, Stage.TRANSCRIBE, Stage.PHONES, Stage.SAVE)
}
