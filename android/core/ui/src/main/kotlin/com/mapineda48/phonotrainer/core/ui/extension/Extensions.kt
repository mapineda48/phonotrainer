package com.mapineda48.phonotrainer.core.ui.extension

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.MouthMode
import com.mapineda48.phonotrainer.core.domain.model.RecordTarget
import com.mapineda48.phonotrainer.core.domain.model.TimedPhone
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import kotlinx.coroutines.flow.Flow

// Extension points for v1-complete features (work-plan 4.4), injected as `Optional<…>`. v1-core binds none of them
// (delivery review D-12): every slot shows its v1-core fallback until a v1-complete owner adds a binding.

/** One drawn pose of the mouth: the eleven articulators of `Gesture.pose`, each 0..1. */
@Immutable
data class MouthFrame(val pose: Map<String, Double>)

/** A mouth movement over time. */
fun interface MouthTrack {
  fun frameAt(ms: Long): MouthFrame
}

/** WP-29 `:core:articulator`. Absent: the name and the "what the mouth does" sentence per sound. */
interface MouthRenderer {
  @Composable
  fun Mouth(frame: MouthFrame, modifier: Modifier)

  fun track(phones: List<TimedPhone>, mode: MouthMode): MouthTrack
}

/** WP-30 (`lesson/record/`). Absent: shadowing only, and no "Record" menu item. */
interface RecordSection {
  @Composable
  fun Section(span: ClipSpan, target: RecordTarget, modifier: Modifier)
}

/** WP-31 (`lesson/phrase/`); the destination is `PhraseDetailsKey`. Absent: no menu item. */
fun interface PhraseDetailsEntry {
  fun available(): Boolean
}

/** WP-31 (`library/transcript/`). Absent: the Clip page shows the Shorts and Summary tabs only. */
interface ClipTranscriptTab {
  @Composable
  fun Tab(jobId: JobId, onOpenWord: (WordRef) -> Unit, onOpenShort: (Int) -> Unit)
}

/** WP-32 `:feature:insights`. Absent: no Home card and no Learn entry. */
interface InsightsAvailability {
  val teaser: Flow<String?>
}

/** WP-32. Absent: the Clip page Summary does not show the reduction meters. */
interface ClipReductionMeters {
  @Composable
  fun Meters(jobId: JobId, modifier: Modifier)
}
