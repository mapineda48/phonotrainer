package com.mapineda48.phonotrainer.feature.learn

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import com.mapineda48.phonotrainer.core.domain.api.ClipContentRepository
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.DidacticContent
import com.mapineda48.phonotrainer.core.domain.api.OccurrenceRepository
import com.mapineda48.phonotrainer.core.domain.api.ReferenceRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.api.SpanPlayer
import com.mapineda48.phonotrainer.core.domain.api.UserMessages
import com.mapineda48.phonotrainer.core.domain.model.ChangeCount
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.navigation.IpaChartKey
import com.mapineda48.phonotrainer.core.ui.extension.MouthRenderer
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/**
 * The IPA chart (P-09, spec F-16): the layout and the articulation table from the bundled content (offline, before
 * pairing), and for the selected symbol its sheet with up to 6 examples from the phone's own index whose heard or
 * dictionary form holds it (architecture 9.4). The selection is in the entry's [SavedStateHandle], so the sheet comes
 * back after a process death; the key's symbol (from a lesson's "Open in the IPA chart") is the first selection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = IpaChartViewModel.Factory::class)
internal class IpaChartViewModel @AssistedInject constructor(
  @Assisted key: IpaChartKey,
  private val savedState: SavedStateHandle,
  reference: ReferenceRepository,
  private val didactic: DidacticContent,
  private val occurrences: OccurrenceRepository,
  content: ClipContentRepository,
  clips: ClipRepository,
  spanPlayer: SpanPlayer,
  private val settings: SettingsRepository,
  messages: UserMessages,
  mouthRenderer: Optional<MouthRenderer>,
) : ViewModel() {
  @AssistedFactory
  interface Factory {
    fun create(key: IpaChartKey): IpaChartViewModel
  }

  /** WP-29's drawing of the mouth when its module binds it (work-plan 4.4); null in v1-core: the sentence only. */
  val mouth: MouthRenderer? = mouthRenderer.orElse(null)

  private val player = ExamplePlayer(viewModelScope, spanPlayer) { settings.settings.value.track }
  private val lessons = ExampleLessons(content, clips, messages)
  private val exitChannel = Channel<NavKey>(Channel.BUFFERED)

  /** Where an example's "Open in its lesson" leads, once each. */
  val exits: Flow<NavKey> = exitChannel.receiveAsFlow()

  // The stored selection wins over the key's (null once the learner closed the sheet).
  private val selected: StateFlow<String?> =
    savedState.getStateFlow(KEY_SELECTED, LearnLogic.selectable(key.symbol, didactic))

  private val layout = didactic.ipaLayout()
  private val weak = layout.reducedVowels.toSet()
  private val names = LearnLogic.names(layout, didactic)

  /** A tick each time the index's counts move (a clip indexed or removed), so the sheet's examples are read again. */
  private val indexChanges: Flow<Map<ChangeLabel, ChangeCount>?> = occurrences.changeCounts()
    .map<Map<ChangeLabel, ChangeCount>, Map<ChangeLabel, ChangeCount>?> { it }
    .catch { emit(null) }
    .distinctUntilChanged()

  private val examples: Flow<Pair<String?, Loaded>> = combine(selected, indexChanges) { symbol, _ -> symbol }
    .transformLatest { symbol ->
      if (symbol == null) {
        emit(null to Loaded.Loading)
      } else {
        emit(symbol to Loaded.Loading)
        emit(symbol to loadExamples(symbol))
      }
    }

  val state: StateFlow<IpaChartUiState> = combine(selected, examples, player.playing, reference.reference) { symbol, loaded, playing, ref ->
    val detail = symbol?.let {
      val shown = if (loaded.first == symbol) loaded.second else Loaded.Loading
      val ui = when (shown) {
        Loaded.Loading -> ExamplesUi.Loading
        Loaded.Failed -> ExamplesUi.Failed
        is Loaded.Ready -> ExamplesUi.Ready(rows(shown.examples, playing))
      }
      LearnLogic.symbolDetail(it, didactic, ref, ui)
    }
    IpaChartUiState(layout = layout, weak = weak, names = names, selected = symbol, detail = detail)
  }.stateIn(
    viewModelScope,
    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
    IpaChartUiState(layout, weak, names, selected.value, selected.value?.let {
      LearnLogic.symbolDetail(it, didactic, reference.reference.value, ExamplesUi.Loading)
    }),
  )

  private suspend fun loadExamples(symbol: String): Loaded = try {
    Loaded.Ready(occurrences.examplesWithSymbol(symbol, EXAMPLES))
  } catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
  } catch (unreadable: Exception) {
    Loaded.Failed
  }

  /** Selects [symbol] (its sheet opens), or closes the sheet with null; the sound of the last one stops. */
  fun select(symbol: String?) {
    if (symbol == selected.value) return
    player.stop()
    savedState[KEY_SELECTED] = symbol
  }

  /** Plays [occurrence], or stops it when it is the one playing. */
  fun play(occurrence: Occurrence) = player.toggle(occurrence)

  /** "Open in its lesson" (spec F-10 item 1, 5.4). */
  fun openLesson(occurrence: Occurrence) {
    player.stop()
    viewModelScope.launch { exitChannel.send(lessons.target(occurrence.word)) }
  }

  override fun onCleared() {
    player.stop()
  }

  private sealed interface Loaded {
    data object Loading : Loaded

    data object Failed : Loaded

    class Ready(val examples: List<Occurrence>) : Loaded
  }

  companion object {
    private const val STOP_TIMEOUT_MS = 5_000L
    internal const val KEY_SELECTED = "ipa.selected"

    /** Spec F-16: "In your clips", up to 6 examples. */
    const val EXAMPLES = 6
  }
}
