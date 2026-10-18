package com.mapineda48.phonotrainer.core.sync.catalogue

import com.mapineda48.phonotrainer.core.domain.model.ArtifactLocalState
import com.mapineda48.phonotrainer.core.domain.model.ClipOrigin
import com.mapineda48.phonotrainer.core.domain.model.PauseReason
import com.mapineda48.phonotrainer.core.domain.model.remote.ArtifactRole
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteArtifactStatus
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteStatus

// The values the `clip` and `artifact` rows store (architecture 4.2): enums by name, artifact names of server-contract
// 6.4.1. Named once here, so every query and mapper of :core:sync agrees on them.

/** The artifact names of server-contract 6.4.1 this build downloads (v1-core) or knows (v1-complete). */
internal object ArtifactNames {
  const val ANALYSIS = "analysis"
  const val AUDIO_MIX = "audio_mix"
  const val AUDIO_DIALOGUE = "audio_dialogue"
  const val ORIGINAL = "original"
  const val ANALYSIS_COMPACT = "analysis_compact"
  const val AUDIO_MIX_OPUS = "audio_mix_opus"
  const val AUDIO_DIALOGUE_OPUS = "audio_dialogue_opus"
  const val POSTER = "poster"
  const val VIDEO_PHONE = "video_phone"

  /**
   * The extension of a verified file, from a closed map keyed by artifact name, never from `content_type`
   * (architecture 4.6, security review M-4). A name outside the map is never stored.
   */
  val EXTENSIONS: Map<String, String> = mapOf(
    ANALYSIS to "json",
    AUDIO_MIX to "wav",
    AUDIO_DIALOGUE to "wav",
    ORIGINAL to "video",
    ANALYSIS_COMPACT to "json.gz",
    AUDIO_MIX_OPUS to "ogg",
    AUDIO_DIALOGUE_OPUS to "ogg",
    POSTER to "jpg",
    VIDEO_PHONE to "mp4",
  )
}

internal object Roles {
  val CLIP_DATA: String = ArtifactRole.CLIP_DATA.name
  val AUDIO_MIX: String = ArtifactRole.AUDIO_MIX.name
  val AUDIO_DIALOGUE: String = ArtifactRole.AUDIO_DIALOGUE.name
  val VIDEO: String = ArtifactRole.VIDEO.name
  val POSTER: String = ArtifactRole.POSTER.name

  /** The roles whose files make or follow the study pack (clip data and audio), never evicted automatically (7.5). */
  val PACK: Set<String> = setOf(CLIP_DATA, AUDIO_MIX, AUDIO_DIALOGUE)
}

internal object LocalStates {
  val ABSENT: String = ArtifactLocalState.ABSENT.name
  val QUEUED: String = ArtifactLocalState.QUEUED.name
  val DOWNLOADING: String = ArtifactLocalState.DOWNLOADING.name
  val PAUSED: String = ArtifactLocalState.PAUSED.name
  val READY: String = ArtifactLocalState.READY.name
  val FAILED: String = ArtifactLocalState.FAILED.name

  fun of(stored: String): ArtifactLocalState =
    ArtifactLocalState.entries.firstOrNull { it.name == stored } ?: ArtifactLocalState.ABSENT
}

internal object RemoteStates {
  val READY: String = RemoteArtifactStatus.READY.name
  val PREPARING: String = RemoteArtifactStatus.PREPARING.name
  val UNAVAILABLE: String = RemoteArtifactStatus.UNAVAILABLE.name
}

internal object ClipStatuses {
  val DONE: String = RemoteStatus.DONE.name

  fun of(stored: String): RemoteStatus = RemoteStatus.entries.firstOrNull { it.name == stored } ?: RemoteStatus.UNKNOWN
}

internal object Origins {
  val ACTIVE: String = ClipOrigin.ACTIVE.name

  fun of(stored: String): ClipOrigin = ClipOrigin.entries.firstOrNull { it.name == stored } ?: ClipOrigin.ACTIVE
}

/**
 * Why an artifact row waits, in its `pause_reason` column: a [PauseReason] name, or [REMOVED_BY_LEARNER] for a video
 * the learner removed ("Remove the video"), which is never downloaded again automatically until "Download the video".
 */
internal object Pauses {
  val FREE_SPACE_FLOOR: String = PauseReason.FREE_SPACE_FLOOR.name
  val BUDGET_REACHED: String = PauseReason.BUDGET_REACHED.name
  const val REMOVED_BY_LEARNER: String = "REMOVED_BY_LEARNER"

  fun of(stored: String?): PauseReason? = when (stored) {
    null -> null
    REMOVED_BY_LEARNER -> PauseReason.NOT_WANTED
    else -> PauseReason.entries.firstOrNull { it.name == stored }
  }
}

/** The `last_error` codes of an artifact row (event-code style, never shown as text). */
internal object ArtifactErrors {
  /** The file failed verification ("Download failed: the file was damaged on the way."). */
  const val DAMAGED = "damaged"

  /** The verified clip data did not parse ("This clip needs downloading again."). */
  const val CORRUPT = "corrupt"

  /** The video's codec or size cannot play on this phone ("This video can't play on this phone."). */
  const val CANNOT_PLAY = "cannot_play"

  /** The verified file is no longer on this phone. */
  const val MISSING = "missing"
}
