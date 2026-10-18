package com.mapineda48.phonotrainer.feature.learn

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
import com.mapineda48.phonotrainer.core.domain.model.GlossaryEntry
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.Reference
import com.mapineda48.phonotrainer.core.navigation.PhenomenonKey
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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A phenomenon page (P-08, spec F-15 item 2): the change's text from the reference and the bundled Learn content
 * (available offline and before pairing), its occurrence count (D-48: "12 times in your clips", eligible occurrences in
 * playable clips) and up to 8 examples from the phone's own index, clearest first and one per word (architecture 9.4).
 * The examples are read again whenever the index's count for this change moves (a clip indexed or removed), and they
 * play through the one span player; "Open in its lesson" follows [ExampleLessons].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = PhenomenonViewModel.Factory::class)
internal class PhenomenonViewModel @AssistedInject constructor(
  @Assisted private val key: PhenomenonKey,
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
    fun create(key: PhenomenonKey): PhenomenonViewModel
  }

  private val label: ChangeLabel = key.label

  /** WP-29's drawing of the mouth when its module binds it (work-plan 4.4); null in v1-core: the sounds in words. */
  val mouth: MouthRenderer? = mouthRenderer.orElse(null)

  private val player = ExamplePlayer(viewModelScope, spanPlayer) { settings.settings.value.track }
  private val lessons = ExampleLessons(content, clips, messages)
  private val exitChannel = Channel<NavKey>(Channel.BUFFERED)

  /** Where an example's "Open in its lesson" leads, once each. */
  val exits: Flow<NavKey> = exitChannel.receiveAsFlow()

  private val counts: Flow<CountsUi> = countsOf(occurrences)

  private val examples: Flow<ExamplesUi> = occurrences.changeCounts()
    .map<Map<ChangeLabel, ChangeCount>, ChangeCount?> { it[label] }
    .catch { emit(null) }
    .distinctUntilChanged()
    .mapLatest { loadExamples() }
    .onStart { emit(Loaded.Loading) }
    .combine(player.playing) { loaded, playing ->
      when (loaded) {
        Loaded.Loading -> ExamplesUi.Loading
        Loaded.Failed -> ExamplesUi.Failed
        is Loaded.Ready -> ExamplesUi.Ready(rows(loaded.examples, playing))
      }
    }

  val state: StateFlow<PhenomenonUiState> = combine(
    reference.reference,
    counts,
    partialOf(clips),
    examples,
  ) { ref, c, p, e -> build(ref, c, p, e) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PhenomenonUiState.Loading)

  private suspend fun loadExamples(): Loaded = try {
    Loaded.Ready(occurrences.examples(label, EXAMPLES))
  } catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
  } catch (unreadable: Exception) {
    Loaded.Failed
  }

  private fun build(reference: Reference, counts: CountsUi, partial: Boolean, examples: ExamplesUi): PhenomenonUiState {
    if (!LearnLogic.isKnown(reference, label)) return PhenomenonUiState.Unknown
    val lesson = didactic.lesson(label)
    return PhenomenonUiState.Ready(
      card = LearnLogic.card(reference, label),
      familyName = LearnLogic.familyName(reference, label),
      lesson = lesson,
      demo = LearnLogic.demo(lesson?.demo, didactic),
      counts = counts,
      partial = partial,
      examples = examples,
    )
  }

  /** The glossary entry of [key], or null when the content has none (then no "?" is shown, spec F-14). */
  fun glossary(key: String): GlossaryEntry? = didactic.glossary(key)

  /** Plays [occurrence], or stops it when it is the one playing. */
  fun play(occurrence: Occurrence) = player.toggle(occurrence)

  /** "Open in its lesson" (spec F-10 item 1, 5.4). */
  fun openLesson(occurrence: Occurrence) {
    player.stop()
    viewModelScope.launch { exitChannel.send(lessons.target(occurrence.word)) }
  }

  /** Leaving the page stops its example. */
  fun stopSound() = player.stop()

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

    /** Spec F-15 item 2: up to 8 examples. */
    const val EXAMPLES = 8
  }
}
