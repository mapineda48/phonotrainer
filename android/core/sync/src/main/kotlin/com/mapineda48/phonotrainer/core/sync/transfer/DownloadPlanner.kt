package com.mapineda48.phonotrainer.core.sync.transfer

import com.mapineda48.phonotrainer.core.database.library.entity.ArtifactEntity
import com.mapineda48.phonotrainer.core.database.library.entity.ClipEntity
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.sync.catalogue.ArtifactNames
import com.mapineda48.phonotrainer.core.sync.catalogue.ClipStatuses
import com.mapineda48.phonotrainer.core.sync.catalogue.LocalStates
import com.mapineda48.phonotrainer.core.sync.catalogue.Origins
import com.mapineda48.phonotrainer.core.sync.catalogue.Pauses
import com.mapineda48.phonotrainer.core.sync.catalogue.RemoteStates
import com.mapineda48.phonotrainer.core.sync.catalogue.Roles
import java.time.Instant

/** What an artifact is for, in download order (architecture 7.4). */
internal enum class WantKind {
  /** Clip data and the audio of the learner's track: the clip is "On this phone" once both are verified. */
  PACK,

  /** The other audio track, after every study pack. */
  SECOND_TRACK,

  /** The video, last, within the budget. */
  VIDEO,
}

/** One file to download: [artifact] of [clip], [bytes] long. */
internal data class Want(val clip: ClipEntity, val artifact: ArtifactEntity, val kind: WantKind) {
  val jobId: String get() = clip.jobId
  val name: String get() = artifact.name
  val bytes: Long get() = artifact.remoteBytes ?: 0
}

/** What the planner needs to know besides the rows. */
internal data class PlanContext(
  /** The paired computer; null when none is paired (nothing is downloaded). */
  val serverId: String?,
  /** This phone's device id: its own requests' clips come first. */
  val deviceId: String?,
  val track: Track,
  val downloadVideos: Boolean,
  /** `DecoderCapabilities.canPlay` for the listing's codec and stored size (both orientations, 1080p or less). */
  val canPlay: (codec: String, width: Int, height: Int) -> Boolean,
  /** When each clip was last watched (`StudyProtection.lastWatched`). */
  val lastWatched: Map<String, Instant>,
)

/** The ordered downloads, and the video clips whose original this phone cannot play (v1-core: they stay audio). */
internal data class DownloadPlan(val wanted: List<Want>, val cannotPlay: Set<String>)

/**
 * Which files to download and in which order (architecture 7.4, spec 7.8), as a pure function of the rows:
 * - study packs first (clip data + the audio of the learner's track; the other track only when the clip has no
 *   dialogue track for the chosen one), clips requested from this phone first, then wanted clips, then newest;
 * - then the second audio track, in the same clip order;
 * - then videos (the `original`, when this phone can decode its codec at its size), kept and recently watched clips
 *   first, then newest; only when "Download videos automatically" is on, the learner asked for the video or the clip.
 *
 * Only finished `ACTIVE` clips of the paired computer that the last listing named take part; nothing is downloaded for
 * a clip removed by the learner, deleted on the computer, or whose file failed verification twice for this revision.
 */
internal object DownloadPlanner {
  fun plan(clips: List<ClipEntity>, artifacts: List<ArtifactEntity>, context: PlanContext): DownloadPlan {
    val server = context.serverId ?: return DownloadPlan(emptyList(), emptySet())
    val byClip = artifacts.groupBy { it.jobId }
    val eligible = clips.filter { it.eligibleOn(server) }
    val packOrder = eligible.sortedWith(packComparator(context.deviceId))
    val packs = ArrayList<Want>()
    val seconds = ArrayList<Want>()
    for (clip in packOrder) {
      if (clip.removedByLearner) continue
      val rows = byClip[clip.jobId].orEmpty().associateBy { it.name }
      val (chosen, other) = audioNames(rows, context.track)
      rows[ArtifactNames.ANALYSIS]?.takeIf { it.needsDownload() }?.let { packs += Want(clip, it, WantKind.PACK) }
      rows[chosen]?.takeIf { it.needsDownload() }?.let { packs += Want(clip, it, WantKind.PACK) }
      other?.let(rows::get)?.takeIf { it.needsDownload() }?.let { seconds += Want(clip, it, WantKind.SECOND_TRACK) }
    }
    val videos = ArrayList<Want>()
    val cannotPlay = HashSet<String>()
    for (clip in eligible.sortedWith(videoComparator(context.lastWatched))) {
      if (clip.removedByLearner) continue
      val original = byClip[clip.jobId].orEmpty().firstOrNull { it.name == ArtifactNames.ORIGINAL } ?: continue
      if (original.remoteStatus != RemoteStates.READY) continue
      if (!clip.decodable(context.canPlay)) {
        cannotPlay += clip.jobId
        continue
      }
      val asked = context.downloadVideos || clip.videoWanted || clip.wanted
      if (!asked || original.pauseReason == Pauses.REMOVED_BY_LEARNER || !original.needsDownload()) continue
      videos += Want(clip, original, WantKind.VIDEO)
    }
    return DownloadPlan(packs + seconds + videos, cannotPlay)
  }

  /** The audio of the learner's track and the other one: the dialogue only when the clip has a dialogue track. */
  fun audioNames(rows: Map<String, ArtifactEntity>, track: Track): Pair<String, String?> {
    val dialogue = ArtifactNames.AUDIO_DIALOGUE.takeIf { it in rows }
    return if (track == Track.DIALOGUE && dialogue != null) dialogue to ArtifactNames.AUDIO_MIX
    else ArtifactNames.AUDIO_MIX to dialogue
  }

  /**
   * Whether [this] row needs a download: the computer has it ready with its size and checksum, this phone has no
   * verified copy of that revision, and it has not failed verification twice for it.
   */
  fun ArtifactEntity.needsDownload(): Boolean {
    if (remoteStatus != RemoteStates.READY || remoteUrl == null || remoteSha256 == null) return false
    if ((remoteBytes ?: 0) <= 0) return false
    if (localState == LocalStates.READY && localFile != null && localSha256.equals(remoteSha256, ignoreCase = true)) {
      return false
    }
    return verifyFailures < FAILURES_TO_GIVE_UP
  }

  /** Whether the study pack of [clip] is verified on this phone for [track] (SC-11). */
  fun packOnPhone(rows: List<ArtifactEntity>, track: Track): Boolean {
    val byName = rows.associateBy { it.name }
    val (chosen, _) = audioNames(byName, track)
    return byName[ArtifactNames.ANALYSIS].verified() && byName[chosen].verified()
  }

  private fun ArtifactEntity?.verified(): Boolean =
    this != null && localState == LocalStates.READY && localFile != null && localSha256 != null

  private fun ClipEntity.eligibleOn(server: String): Boolean =
    origin == Origins.ACTIVE && status == ClipStatuses.DONE && serverId == server && missingListings == 0 &&
      !deletedOnComputer

  private fun ClipEntity.decodable(canPlay: (String, Int, Int) -> Boolean): Boolean {
    val codec = videoCodec ?: return false
    val width = videoWidth ?: return false
    val height = videoHeight ?: return false
    return canPlay(codec, width, height)
  }

  private val newest: Comparator<ClipEntity> =
    compareByDescending<ClipEntity> { it.createdAt ?: 0L }.thenByDescending { it.jobId }

  private fun packComparator(deviceId: String?): Comparator<ClipEntity> =
    compareByDescending<ClipEntity> { deviceId != null && it.submittedByDevice == deviceId }
      .thenByDescending { it.wanted }
      .then(newest)

  /** Kept first, then the most recently watched, then the newest; never-watched clips after watched ones. */
  fun videoComparator(lastWatched: Map<String, Instant>): Comparator<ClipEntity> =
    compareByDescending<ClipEntity> { it.kept }
      .thenByDescending { lastWatched[it.jobId] ?: Instant.MIN }
      .then(newest)

  /** Verification failures after which a file waits for "Try again" or a new revision (architecture 7.4). */
  const val FAILURES_TO_GIVE_UP = 2

  /** Roles whose files the planner treats as the study pack's (never evicted automatically). */
  val PACK_ROLES: Set<String> = Roles.PACK
}
