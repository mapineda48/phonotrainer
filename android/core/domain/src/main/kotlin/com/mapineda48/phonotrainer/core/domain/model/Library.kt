package com.mapineda48.phonotrainer.core.domain.model

import com.mapineda48.phonotrainer.core.domain.model.remote.ArtifactRole
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteStatus
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import java.time.Instant

/** Spec 7.11 vocabulary for a clip on this phone (architecture 3.4). */
sealed interface ClipAvailability {
  data object OnThisPhone : ClipAvailability

  data class Bringing(val percent: Int) : ClipAvailability

  /** Removed by the learner, or never downloaded. */
  data object NotOnThisPhone : ClipAvailability

  data object GettingReadyOnComputer : ClipAvailability

  data class DownloadPaused(val reason: PauseReason) : ClipAvailability

  data object NeedsDownloadingAgain : ClipAvailability
}

/** The video's availability (architecture 3.4 lists the members). */
sealed interface VideoAvailability {
  data object OnThisPhone : VideoAvailability

  data class Downloading(val percent: Int) : VideoAvailability

  data object NotOnThisPhone : VideoAvailability

  data object GettingReadyOnComputer : VideoAvailability

  data object NotAvailableFromComputer : VideoAvailability

  data object CannotPlayOnThisPhone : VideoAvailability

  data object AudioOnlyClip : VideoAvailability
}

data class ClipSummary(
  val id: JobId,
  val title: String,
  val durationS: Double?,
  val words: Int?,
  val changeCount: Int?,
  val source: ClipSource,
  val availability: ClipAvailability,
  val video: VideoAvailability,
  val kept: Boolean,
  val origin: ClipOrigin,
  val olderRules: Boolean,
  val shortsSeen: Int,
  val shortsTotal: Int,
  val addedAt: Instant,
)

// Work-plan Appendix A.2: library, sync and storage (WP-08, WP-15, WP-16, WP-24).

data class ClipSource(val kind: SourceKind, val site: Site?, val link: String?, val fileName: String?)

enum class ClipOrigin { ACTIVE, FORGOTTEN_COMPUTER, OTHER_LIBRARY }

enum class PauseReason { NOT_CONNECTED, FREE_SPACE_FLOOR, BUDGET_REACHED, NOT_WANTED }

data class ClipQuery(
  val search: String = "",
  val filter: ClipFilter = ClipFilter.ALL,
  val sort: ClipSort = ClipSort.NEWEST,
)

enum class ClipFilter { ALL, ON_THIS_PHONE, NOT_ON_THIS_PHONE, IN_PROGRESS, KEPT, VIDEO, AUDIO_ONLY }

enum class ClipSort { NEWEST, OLDEST, TITLE, LONGEST }

data class ClipDetail(
  val summary: ClipSummary,
  val durationS: Double?,
  val segments: Int?,
  val changeCounts: Map<ChangeLabel, Int>,
  val rulesVersion: Int?,
  val phoneEngine: String?,
  val dialogueSeparation: Boolean?,
  val hasDialogueTrack: Boolean,
  val studyPackBytes: Long?,
  val videoBytes: Long?,
  val lastWatched: Instant?,
  val resume: ShortId?,
  val computerError: RequestError?,
  /** Per-file download state (F-21 "Clip data ✓ · Audio 60 % · Video waiting"). */
  val artifacts: List<ArtifactProgress> = emptyList(),
)

/** [verifyFailures] ≥ 2: "Download failed: the file was damaged on the way." */
data class ArtifactProgress(
  val role: ArtifactRole,
  val state: ArtifactLocalState,
  val percent: Int?,
  val pauseReason: PauseReason?,
  val verifyFailures: Int,
)

/** The `artifact` table's `local_state` (architecture 4.2). */
enum class ArtifactLocalState { ABSENT, QUEUED, DOWNLOADING, PAUSED, READY, FAILED }

data class ComputerJob(
  val id: JobId,
  val title: String?,
  val status: RemoteStatus,
  val stage: Stage?,
  val percent: Int,
  val queuePosition: Int?,
  val source: ClipSource,
  val error: RequestError?,
  val createdAt: Instant,
  val hidden: Boolean,
)

data class MassDeletionPrompt(val count: Int, val ofTotal: Int, val serverName: String)

sealed interface SyncState {
  data object Idle : SyncState

  /** "Syncing · bringing 3 clips · 42 %"; with bytes: "Bringing 2 clips to this phone · 34 MB of 61 MB" (F-03). */
  data class Active(
    val bringing: Int,
    val percent: Int?,
    val sending: Int,
    val doneBytes: Long? = null,
    val totalBytes: Long? = null,
  ) : SyncState

  data class Paused(val reason: PauseReason) : SyncState
}

enum class SyncReason {
  APP_START,
  RESUME,
  CONNECTED,
  REQUEST_CREATED,
  SYNC_NOW,
  PULL_TO_REFRESH,
  PAIRED,
  JOB_CHANGED,
  PERIODIC,
  ON_WIFI,
  SEND_NOW,
  NETWORK_CHANGED,
}

data class StorageUsage(
  val clipsBytes: Long,
  val videosBytes: Long,
  val waitingCopiesBytes: Long,
  val cacheBytes: Long,
  val budget: StorageBudget,
  val freeBytes: Long,
  val floorBytes: Long,
  val budgetReached: Boolean,
  val belowFloor: Boolean,
  val perClip: List<ClipStorage>,
)

data class ClipStorage(
  val id: JobId,
  val title: String,
  val studyPackBytes: Long,
  val videoBytes: Long,
  val kept: Boolean,
)

sealed interface StorageBudget {
  data class Limit(val bytes: Long) : StorageBudget

  data object FreeSpaceOnly : StorageBudget
}

/** What indexing one clip did (`:core:corpus` → `:core:sync`). */
sealed interface IndexOutcome {
  data class Indexed(val shorts: Int, val occurrences: Int) : IndexOutcome

  data object Unchanged : IndexOutcome

  data object Corrupt : IndexOutcome

  data object Missing : IndexOutcome
}

/** A screen's hold on a clip: [close] releases it; removal is deferred while one is open (architecture 7.7). */
interface ClipLease : AutoCloseable {
  val id: JobId
}
