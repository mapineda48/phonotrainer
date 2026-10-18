package com.mapineda48.phonotrainer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.designsystem.brand.PhonoMarkImage
import com.mapineda48.phonotrainer.core.designsystem.component.Kicker
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.ui.PhonoCard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** About PhonoTrainer (spec F-24, F-22 "About and licences"). */
@Immutable
data class AboutUiState(
  val version: String? = null,
  /** The API version of the paired computer when it was last seen; null when no computer is paired. */
  val computerApi: Int? = null,
  /** The app's licence sheet, while open. */
  val licence: LicenceTextUi? = null,
)

/**
 * About (spec F-24): the app's version, the computer's API version last seen (the last probe's, else the pairing's),
 * the licence line with its text, the source location, the privacy statement and the way to every open-source licence.
 */
@HiltViewModel
internal class AboutViewModel @Inject constructor(
  computer: ComputerStatusRepository,
  source: LicenceSource,
  private val version: AppVersion,
) : ViewModel() {
  private val reader = LicenceReader(source)
  private val licence = MutableStateFlow<LicenceTextUi?>(null)
  private var reading: Job? = null

  val state: StateFlow<AboutUiState> = combine(computer.paired, computer.diagnostics, licence) { paired, diagnostics, open ->
    AboutUiState(version.name, paired?.let { diagnostics.apiVersion ?: it.apiVersion }, open)
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AboutUiState(version.name))

  /** "Read the licence": the app's GPL-3.0-or-later text. */
  fun openLicence() {
    val entry = Licences.app(version.name)
    reading?.cancel()
    licence.value = LicenceTextUi(entry)
    reading = viewModelScope.launch {
      val sections = try {
        reader.sections(entry)
      } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        entry.licences.map { LicenceSection(it, null) }
      }
      licence.value = LicenceTextUi(entry, sections)
    }
  }

  fun closeLicence() {
    reading?.cancel()
    licence.value = null
  }

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}

/** What About's controls ask for. */
internal class AboutActions(
  val onBack: () -> Unit = {},
  val onReadLicence: () -> Unit = {},
  val onCloseLicence: () -> Unit = {},
  /** The source location, opened in the browser only on this explicit tap. */
  val onSource: () -> Unit = {},
  val onLicences: () -> Unit = {},
)

/**
 * About PhonoTrainer (spec F-24, design 10.2 P-12): the mark and the version, the computer's API version last seen,
 * "PhonoTrainer is free software under the GNU GPL-3.0-or-later" with "Read the licence", the source location, the
 * privacy statement of F-22, and "Open-source licences".
 */
@Composable
internal fun AboutScreen(state: AboutUiState, actions: AboutActions, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  SettingsPage(
    title = stringResource(R.string.about_title),
    lede = stringResource(R.string.about_lede),
    onBack = actions.onBack,
    listTag = SettingsTags.ABOUT_LIST,
    modifier = modifier,
  ) {
    item(key = "facts") {
      PhonoCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s16)) {
          PhonoMarkImage(size = MARK)
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
            FactRow(
              stringResource(R.string.about_version_label),
              state.version?.let { stringResource(R.string.about_version, it) } ?: Licences.APP_NAME,
              modifier = Modifier.testTag(SettingsTags.ABOUT_VERSION),
            )
            FactRow(
              stringResource(R.string.about_api_label),
              state.computerApi?.let { stringResource(R.string.about_api, it) } ?: stringResource(R.string.about_api_none),
              modifier = Modifier.testTag(SettingsTags.ABOUT_API),
            )
          }
        }
      }
    }
    item(key = "licence") {
      Column(Modifier.fillMaxWidth().testTag(SettingsTags.ABOUT_LICENCE), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
        Kicker(stringResource(R.string.licences_app), icon = PhonoIcons.Licences, modifier = Modifier.padding(top = PhonoSpacing.s8))
        PhonoText(stringResource(R.string.about_licence), style = MaterialTheme.typography.titleMedium, color = colors.ink)
        PhonoText(stringResource(R.string.about_licence_body), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
      }
    }
    item(key = "read-licence") {
      SettingsRow(
        icon = PhonoIcons.Details,
        title = stringResource(R.string.about_read_licence),
        status = Licences.APP_LICENCE,
        onClick = actions.onReadLicence,
        modifier = Modifier.testTag(SettingsTags.ABOUT_READ_LICENCE),
      )
    }
    item(key = "source") {
      SettingsRow(
        icon = PhonoIcons.External,
        title = stringResource(R.string.about_source),
        status = Licences.SOURCE_URL,
        onClick = actions.onSource,
        trailing = PhonoIcons.External,
        modifier = Modifier.testTag(SettingsTags.ABOUT_SOURCE),
      )
    }
    item(key = "licences") {
      SettingsRow(
        icon = PhonoIcons.Licences,
        title = stringResource(R.string.about_licences),
        status = stringResource(R.string.about_licences_body),
        onClick = actions.onLicences,
        modifier = Modifier.testTag(SettingsTags.ABOUT_LICENCES),
      )
    }
    item(key = "privacy") {
      Column(Modifier.fillMaxWidth().testTag(SettingsTags.ABOUT_PRIVACY), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
        Kicker(stringResource(R.string.about_privacy), icon = PhonoIcons.AvailOnPhone, modifier = Modifier.padding(top = PhonoSpacing.s8))
        PhonoText(
          stringResource(R.string.about_privacy_body),
          style = MaterialTheme.typography.bodyLarge,
          color = colors.ink,
          modifier = Modifier.padding(bottom = PhonoSpacing.s8),
        )
      }
    }
  }
  state.licence?.let { LicenceSheet(it, onDismiss = actions.onCloseLicence) }
}

private val MARK = 56.dp
