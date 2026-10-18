package com.mapineda48.phonotrainer.feature.learn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.OccurrenceRepository
import com.mapineda48.phonotrainer.core.domain.api.ReferenceRepository
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.ui.extension.InsightsAvailability
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * Learn (T3, spec F-15 item 1): the changes by family from the reference snapshot (shown at once, before pairing and
 * offline), each card with its short count from the phone's own index (D-48: the number the Home chip and the mode
 * picker show), the line that says the counts are this phone's when some clips are not here, and the Insights entry
 * when `:feature:insights` provides one.
 */
@HiltViewModel
internal class LearnViewModel @Inject constructor(
  reference: ReferenceRepository,
  occurrences: OccurrenceRepository,
  clips: ClipRepository,
  insights: Optional<InsightsAvailability>,
) : ViewModel() {
  private val sections: Flow<List<FamilySectionUi>> = reference.reference.map(LearnLogic::sections).distinctUntilChanged()

  private val counts: Flow<CountsUi> = countsOf(occurrences)

  private val partial: Flow<Boolean> = partialOf(clips)

  private val insightsEntry: Flow<InsightsEntryUi?> = insights.orElse(null)
    ?.teaser?.map<String?, InsightsEntryUi?> { InsightsEntryUi(it) }?.onStart { emit(InsightsEntryUi(null)) }
    ?.catch { emit(InsightsEntryUi(null)) }
    ?: flowOf(null)

  val state: StateFlow<LearnUiState> = combine(sections, counts, partial, insightsEntry) { s, c, p, i ->
    LearnUiState(sections = s, counts = c, partial = p, insights = i)
  }.stateIn(
    viewModelScope,
    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
    LearnUiState(LearnLogic.sections(reference.reference.value), CountsUi.Loading, partial = false, insights = null),
  )

  companion object {
    private const val STOP_TIMEOUT_MS = 5_000L
  }
}

/** The change counts of the local index, "Loading" until it is read and "Failed" when it cannot be (F-15 States). */
internal fun countsOf(occurrences: OccurrenceRepository): Flow<CountsUi> =
  occurrences.changeCounts()
    .map<_, CountsUi> { CountsUi.Ready(it) }
    .onStart { emit(CountsUi.Loading) }
    .catch { emit(CountsUi.Failed) }

/** True while some clip of the catalogue is not on this phone, so the counts are this phone's only (F-15 item 3). */
internal fun partialOf(clips: ClipRepository): Flow<Boolean> =
  clips.clips(ClipQuery())
    .map { list -> list.any { it.availability != ClipAvailability.OnThisPhone } }
    .distinctUntilChanged()
    .onStart { emit(false) }
    .catch { emit(false) }
