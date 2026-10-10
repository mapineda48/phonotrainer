@file:UseSerializers(JobIdSerializer::class, RequestIdSerializer::class, ChangeLabelSerializer::class)

package com.mapineda48.phonotrainer.core.navigation

import androidx.navigation3.runtime.NavKey
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

// Every destination of spec 5.1 as a navigation key (architecture 16.1). All keys are registered in
// PhonoNavSerialization, which every saved back stack uses.

/** T1, the dashboard; the start destination. */
@Serializable
data object HomeKey : NavKey

/** T2, the feed; [start] null opens the last mode and position (spec 5.1). */
@Serializable
data class WatchKey(val start: FeedStart? = null) : NavKey

/** T3. */
@Serializable
data object LearnKey : NavKey

/** T4; [focus] is a change label key ("flapping"), as the web's `?focus=`. */
@Serializable
data class PracticeKey(val focus: String? = null) : NavKey

/** P-01, all clips. */
@Serializable
data object LibraryKey : NavKey

/** P-02. */
@Serializable
data class ClipKey(val jobId: JobId, val tab: ClipTab = ClipTab.SHORTS) : NavKey

enum class ClipTab { SHORTS, TRANSCRIPT, SUMMARY }

/** P-03. */
@Serializable
data object RequestsKey : NavKey

/** P-04. */
@Serializable
data class RequestDetailKey(val requestId: RequestId) : NavKey

/** P-05. */
@Serializable
data class AddClipKey(val start: AddClipStart = AddClipStart.CHOOSER) : NavKey

enum class AddClipStart { CHOOSER, PASTE, FILES }

/** P-06. */
@Serializable
data class ConnectKey(val mode: ConnectMode = ConnectMode.FROM_HOME) : NavKey

enum class ConnectMode { FIRST_RUN, FROM_HOME, CONNECT_AGAIN, NEW_ADDRESS }

/** P-07. */
@Serializable
data object ConnectionDetailsKey : NavKey

/** P-08. */
@Serializable
data class PhenomenonKey(val label: ChangeLabel) : NavKey

/** P-09; [symbol] null opens the chart with nothing selected. */
@Serializable
data class IpaChartKey(val symbol: String? = null) : NavKey

/** P-10 (v1-complete; its v1-core installer is the stub of :feature:insights). */
@Serializable
data object InsightsKey : NavKey

/** P-11. */
@Serializable
data object SavedKey : NavKey

/** P-12, Settings and its sub-pages. */
@Serializable
data object SettingsKey : NavKey

@Serializable
data object StorageKey : NavKey

@Serializable
data object YourComputerKey : NavKey

@Serializable
data object NotificationSettingsKey : NavKey

@Serializable
data object AboutKey : NavKey

@Serializable
data object LicencesKey : NavKey

@Serializable
data object HelpKey : NavKey

/** P-13; [restart] is "Restart the tour" from Settings > Help. */
@Serializable
data class WelcomeKey(val restart: Boolean = false) : NavKey

/** F-14, "Words we use". */
@Serializable
data object GlossaryIndexKey : NavKey

/**
 * F-10, the word lesson. An overlay: with [LessonPresentation.SHEET] it renders through the overlay scene strategy
 * over the feed (its entry carries [NavOverlay.sheet]); with [LessonPresentation.FULL] it is a normal destination.
 */
@Serializable
data class LessonKey(
  val jobId: JobId,
  val segment: Int,
  val word: Int,
  val origin: LessonOrigin,
  val presentation: LessonPresentation,
) : NavKey

/** Where a lesson was opened from: the feed opens it as a sheet, the others full (design 9.24). */
enum class LessonOrigin { FEED, CLIP_PAGE, LEARN, PRACTICE, SAVED }

enum class LessonPresentation { SHEET, FULL }

/** F-11, phrase details (overlay, v1-complete; its v1-core installer is the stub under `lesson/phrase/`). */
@Serializable
data class PhraseDetailsKey(val jobId: JobId, val segment: Int) : NavKey
