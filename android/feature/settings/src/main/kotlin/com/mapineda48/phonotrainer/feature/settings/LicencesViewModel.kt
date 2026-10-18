package com.mapineda48.phonotrainer.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One licence's text as the sheet shows it: its paragraphs, or null when the text is not bundled. */
@Immutable
data class LicenceSection(val licence: LicenceRef, val paragraphs: List<String>?)

/** The licence sheet of [entry]: [sections] null while the texts are read. */
@Immutable
data class LicenceTextUi(val entry: LicenceEntry, val sections: List<LicenceSection>? = null)

/** How the catalogue stands. */
sealed interface LicencesRead {
  data object Loading : LicencesRead

  data object Failed : LicencesRead

  data class Ready(val catalogue: LicenceCatalogue) : LicencesRead
}

/** Open-source licences (spec F-24). */
@Immutable
data class LicencesUiState(val read: LicencesRead = LicencesRead.Loading, val open: LicenceTextUi? = null)

/** Reads entries' licence texts from a [LicenceSource]: the report's own text when it has one, else the asset. */
internal class LicenceReader(private val source: LicenceSource) {
  suspend fun sections(entry: LicenceEntry): List<LicenceSection> = entry.licences.map { licence ->
    val text = licence.text ?: licence.asset?.let { source.text(it) }
    LicenceSection(licence, text?.let(Licences::paragraphs)?.takeIf { it.isNotEmpty() })
  }
}

/** Builds the catalogue of [Licences] from a [LicenceSource]: the app, `bundled.json`'s entries and the report's. */
internal suspend fun loadCatalogue(source: LicenceSource, version: AppVersion): LicenceCatalogue {
  val bundled = source.bundled()?.let(Licences::bundled).orEmpty()
  val libraries = source.report()?.let(Licences::libraries).orEmpty()
  return LicenceCatalogue(Licences.app(version.name), bundled, libraries)
}

/**
 * Open-source licences (spec F-24-AC1, AC2; architecture 20.6): every runtime library of this build's report, the three
 * font families and the icon set of `bundled.json`, and the app itself, each with its licence text, all read from the
 * APK (nothing needs a network).
 */
@HiltViewModel
internal class LicencesViewModel @Inject constructor(
  private val source: LicenceSource,
  private val version: AppVersion,
) : ViewModel() {
  private val mutable = MutableStateFlow(LicencesUiState())
  private val reader = LicenceReader(source)
  private var opening: Job? = null

  val state: StateFlow<LicencesUiState> = mutable.asStateFlow()

  init {
    load()
  }

  /** "Try again" after the catalogue could not be read. */
  fun retry() {
    if (mutable.value.read is LicencesRead.Failed) load()
  }

  private fun load() {
    mutable.update { it.copy(read = LicencesRead.Loading) }
    viewModelScope.launch {
      val read = try {
        LicencesRead.Ready(loadCatalogue(source, version))
      } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        LicencesRead.Failed
      }
      mutable.update { it.copy(read = read) }
    }
  }

  /** Opens the licence sheet of [entry] and reads its texts. */
  fun open(entry: LicenceEntry) {
    opening?.cancel()
    mutable.update { it.copy(open = LicenceTextUi(entry)) }
    opening = viewModelScope.launch {
      val sections = try {
        reader.sections(entry)
      } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        entry.licences.map { LicenceSection(it, null) }
      }
      mutable.update { current -> if (current.open?.entry == entry) current.copy(open = LicenceTextUi(entry, sections)) else current }
    }
  }

  fun close() {
    opening?.cancel()
    mutable.update { it.copy(open = null) }
  }
}
