package com.mapineda48.phonotrainer.core.corpus

import com.mapineda48.phonotrainer.core.database.library.dao.MediaFacts
import com.mapineda48.phonotrainer.core.database.library.dao.MediaFile
import com.mapineda48.phonotrainer.core.domain.model.ClipMedia
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import java.io.File

/**
 * A clip's local media from its `artifact` and `clip` rows (architecture 4.2, 7.4, 11.1): pure, so every case is
 * tested without files or Room. A file counts only when its row is verified (`local_state` READY) and it is really on
 * the phone (`resolve` answers it).
 *
 * - Audio: the WAV the analysis read, else its Opus rendition (v1-complete); both play the same timeline.
 * - Video: the phone video when it is on the phone (v1-complete: no rotation flag, offset 0), else the original with
 *   the listing's facts (rotation and offset 0 when unknown).
 * - The clip is media only when at least one audio track is on the phone; with neither, [resolve] answers null.
 */
internal object MediaResolver {
  const val READY: String = "READY"
  private const val DOWNLOADING = "DOWNLOADING"
  private const val PREPARING = "PREPARING"

  const val AUDIO_MIX: String = "AUDIO_MIX"
  const val AUDIO_DIALOGUE: String = "AUDIO_DIALOGUE"
  const val VIDEO: String = "VIDEO"
  const val POSTER: String = "POSTER"

  /** Preference among the verified files of one role (artifact names, server-contract 6.4.1). */
  private val MIX_NAMES = listOf("audio_mix", "audio_mix_opus")
  private val DIALOGUE_NAMES = listOf("audio_dialogue", "audio_dialogue_opus")
  private const val PHONE_VIDEO = "video_phone"
  private const val ORIGINAL = "original"
  private val VIDEO_NAMES = listOf(PHONE_VIDEO, ORIGINAL)
  private val POSTER_NAMES = listOf("poster")

  private const val MEDIA_AUDIO = "AUDIO"
  private const val MEDIA_NONE = "NONE"
  private const val MEDIA_VIDEO = "VIDEO"
  private const val PERCENT = 100

  /**
   * The media of [facts]' clip from its artifact [rows], each verified file located by [resolve] (null: not on the
   * phone). [ClipMedia.audioMix] is never null in the frozen type, so when only the dialogue track is on the phone it
   * holds the dialogue file too: `audioMix == audioDialogue` then means "the mix is not on this phone yet", which the
   * player words as `TrackFallback.MIX_NOT_ON_PHONE` (architecture 11.1).
   */
  fun resolve(facts: MediaFacts, rows: List<MediaFile>, resolve: (String) -> File?): ClipMedia? {
    val onPhone = HashMap<String, File>()
    for (row in rows) {
      if (row.localState != READY) continue
      val file = row.localFile?.let(resolve) ?: continue
      onPhone[row.name] = file
    }
    val mix = MIX_NAMES.firstNotNullOfOrNull(onPhone::get)
    val dialogue = DIALOGUE_NAMES.firstNotNullOfOrNull(onPhone::get)
    val audio = mix ?: dialogue ?: return null
    val videoName = VIDEO_NAMES.firstOrNull { it in onPhone }
    val phoneVideo = videoName == PHONE_VIDEO
    return ClipMedia(
      jobId = JobId(facts.jobId),
      audioMix = audio,
      audioDialogue = dialogue,
      videoFile = videoName?.let(onPhone::get),
      videoCodec = if (phoneVideo) null else facts.videoCodec,
      videoWidth = if (phoneVideo) null else facts.videoWidth,
      videoHeight = if (phoneVideo) null else facts.videoHeight,
      videoRotation = if (phoneVideo) 0 else facts.videoRotation ?: 0,
      videoOffsetMs = if (phoneVideo) 0 else facts.videoOffsetMs ?: 0,
      poster = POSTER_NAMES.firstNotNullOfOrNull(onPhone::get),
      videoAvailability = videoAvailability(facts, rows, videoOnPhone = videoName != null),
    )
  }

  /**
   * Spec 7.11's video words for a clip, from what the listing said and what the phone holds:
   * - an audio-only job, or media the computer reports as audio or none → [VideoAvailability.AudioOnlyClip];
   * - a video file on the phone → [VideoAvailability.OnThisPhone];
   * - a video downloading → [VideoAvailability.Downloading] with its percent;
   * - a video ready on the computer but not here → [VideoAvailability.NotOnThisPhone];
   * - a video being prepared → [VideoAvailability.GettingReadyOnComputer];
   * - no video row for a video clip, or one that failed or is unavailable → [VideoAvailability.NotAvailableFromComputer];
   * - no video row and no media facts (an older listing) → [VideoAvailability.AudioOnlyClip].
   *
   * [VideoAvailability.CannotPlayOnThisPhone] is not in the rows: the player sets it at a decode error (11.1).
   */
  fun videoAvailability(facts: MediaFacts, rows: List<MediaFile>, videoOnPhone: Boolean): VideoAvailability {
    if (facts.audioOnlyJob || facts.mediaKind == MEDIA_AUDIO || facts.mediaKind == MEDIA_NONE) {
      return VideoAvailability.AudioOnlyClip
    }
    if (videoOnPhone) return VideoAvailability.OnThisPhone
    val videos = rows.filter { it.role == VIDEO }
    if (videos.isEmpty()) {
      return if (facts.mediaKind == MEDIA_VIDEO) VideoAvailability.NotAvailableFromComputer else VideoAvailability.AudioOnlyClip
    }
    videos.firstOrNull { it.localState == DOWNLOADING }?.let { return VideoAvailability.Downloading(percentOf(it)) }
    return when {
      videos.any { it.remoteStatus == READY } -> VideoAvailability.NotOnThisPhone
      videos.any { it.remoteStatus == PREPARING } -> VideoAvailability.GettingReadyOnComputer
      else -> VideoAvailability.NotAvailableFromComputer
    }
  }

  private fun percentOf(row: MediaFile): Int {
    val total = row.remoteBytes ?: return 0
    if (total <= 0) return 0
    val done = (row.partBytes ?: 0).coerceIn(0, total)
    return (done * PERCENT / total).toInt().coerceIn(0, PERCENT - 1)
  }
}
