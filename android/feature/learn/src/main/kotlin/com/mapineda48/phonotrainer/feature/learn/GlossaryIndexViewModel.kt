package com.mapineda48.phonotrainer.feature.learn

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.domain.api.DidacticContent
import com.mapineda48.phonotrainer.core.domain.model.GlossaryEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * "Words we use" (spec F-14, D-47): the bundled glossary's terms and measures (never the IPA symbols, whose index is the
 * chart), searched as the Library searches (trimmed, ignoring case, in the term and its explanation). The search text
 * is in the entry's [SavedStateHandle], so it survives a process death.
 */
@HiltViewModel
internal class GlossaryIndexViewModel @Inject constructor(
  private val savedState: SavedStateHandle,
  private val didactic: DidacticContent,
) : ViewModel() {
  private val total: Int by lazy { didactic.search("").size }

  val state: StateFlow<GlossaryUiState> = savedState.getStateFlow(KEY_QUERY, "")
    .map(::build)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), build(savedState[KEY_QUERY] ?: ""))

  private fun build(query: String): GlossaryUiState {
    val (terms, measures) = LearnLogic.glossaryGroups(didactic.search(query))
    return GlossaryUiState(query = query, terms = terms, measures = measures, total = total)
  }

  fun search(query: String) {
    savedState[KEY_QUERY] = query
  }

  /** "Clear the search": every row again (F-14-AC4). */
  fun clear() = search("")

  /** The entry a row opens in the "What's this?" sheet. */
  fun entry(key: String): GlossaryEntry? = didactic.glossary(key)

  companion object {
    private const val STOP_TIMEOUT_MS = 5_000L
    internal const val KEY_QUERY = "glossary.query"
  }
}
