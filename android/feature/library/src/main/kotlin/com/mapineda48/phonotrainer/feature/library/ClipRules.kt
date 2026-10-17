package com.mapineda48.phonotrainer.feature.library

import androidx.compose.runtime.Immutable
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.ClipDetail
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.Reference
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.ArtifactLocalState
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.familyFor
import com.mapineda48.phonotrainer.core.domain.model.remote.ArtifactRole
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import java.util.Locale

/** A notice of the Clip page's header (spec F-05 states), in the order they show. */
enum class ClipNotice {
  /** Not on this phone: "Download to this phone", or offline "Download when connected". */
  NOT_HERE,

  /** "Download when connected" was tapped: it downloads the next time the computer is reachable. */
  WANTED,

  /** Corrupt local clip data: "This clip needs downloading again." */
  NEEDS_AGAIN,

  /** Its download is paused, with the reason. */
  PAUSED,

  /** Imported without audio: readable, not playable. */
  NO_AUDIO,

  VIDEO_NOT_AVAILABLE,
  VIDEO_CANNOT_PLAY,
  OLDER_RULES,
}

/** One row of the Shorts tab (spec F-05 item 2). [words] is the spotlight's text when the clip data is readable. */
@Immutable
internal data class ShortRowState(
  val short: ShortInfo,
  val changeName: String?,
  val family: Family?,
  val words: String?,
  val seen: Boolean,
  val next: Boolean,
)

/** One row of "Changes in this clip" (spec F-05 item 4). */
@Immutable
internal data class ChangeRowState(
  val label: ChangeLabel,
  val name: String,
  val family: Family,
  val advice: Advice,
  val count: Int,
)

/** The Clip page's rules as pure functions (spec F-05, v1-core). */
internal object ClipRules {
  /** The header notices of [detail], in order. */
  fun notices(detail: ClipDetail, wanted: Boolean): List<ClipNotice> = buildList {
    val clip = detail.summary
    when (clip.availability) {
      ClipAvailability.NotOnThisPhone -> add(if (wanted) ClipNotice.WANTED else ClipNotice.NOT_HERE)
      ClipAvailability.NeedsDownloadingAgain -> add(ClipNotice.NEEDS_AGAIN)
      is ClipAvailability.DownloadPaused -> add(ClipNotice.PAUSED)
      else -> Unit
    }
    if (noAudio(detail)) add(ClipNotice.NO_AUDIO)
    when (clip.video) {
      VideoAvailability.NotAvailableFromComputer -> add(ClipNotice.VIDEO_NOT_AVAILABLE)
      VideoAvailability.CannotPlayOnThisPhone -> add(ClipNotice.VIDEO_CANNOT_PLAY)
      else -> Unit
    }
    if (clip.olderRules) add(ClipNotice.OLDER_RULES)
  }

  /**
   * An imported clip that came without its audio (web precedent): its listing names no mix audio among its files, so
   * it can be read but not listened to, and it is left out of the feed.
   */
  fun noAudio(detail: ClipDetail): Boolean = detail.summary.source.kind == SourceKind.IMPORT &&
    detail.artifacts.isNotEmpty() && detail.artifacts.none { it.role == ArtifactRole.AUDIO_MIX }

  /** Whether "Watch" plays: the study pack is on this phone and it has audio. */
  fun playable(detail: ClipDetail): Boolean =
    detail.summary.availability == ClipAvailability.OnThisPhone && !noAudio(detail)

  /** While the study pack comes: the audio's own percent when known, else the clip's ("Getting the audio… 40 %"). */
  fun audioPercent(detail: ClipDetail): Int? {
    val bringing = detail.summary.availability as? ClipAvailability.Bringing ?: return null
    val audio = detail.artifacts.firstOrNull { it.role == ArtifactRole.AUDIO_MIX }
    return when (audio?.state) {
      ArtifactLocalState.DOWNLOADING, ArtifactLocalState.PAUSED -> audio.percent ?: bringing.percent
      else -> bringing.percent
    }
  }

  /** The clip is still being analyzed on the computer: the page shows its request and turns into the clip when ready. */
  fun processing(detail: ClipDetail): Boolean =
    detail.summary.availability == ClipAvailability.GettingReadyOnComputer

  /** The rows of the Shorts tab. */
  fun shortRows(shorts: List<ShortInfo>, data: ClipData?, reference: Reference, seen: Set<ShortId>, resume: ShortId?):
    List<ShortRowState> = shorts.sortedBy { it.ordinal }.map { short ->
    val spotlight = short.spotlight
    ShortRowState(
      short = short,
      changeName = spotlight?.let { changeName(reference, it.change) },
      family = spotlight?.let { reference.familyFor(it.change) },
      words = spotlight?.let { spot ->
        val word = data?.word(spot.word)?.text ?: return@let null
        val next = if (spot.crossesBoundary) data.nextWord(spot.word)?.let { data.word(it)?.text } else null
        listOfNotNull(word, next).joinToString(" ")
      },
      seen = short.id in seen,
      next = short.id == resume,
    )
  }

  /** "Changes in this clip", most frequent first, then by name. */
  fun changeRows(counts: Map<ChangeLabel, Int>, reference: Reference): List<ChangeRowState> = counts
    .filterValues { it > 0 }
    .map { (label, count) ->
      ChangeRowState(
        label = label,
        name = changeName(reference, label),
        family = reference.familyFor(label),
        advice = reference.practice[label]?.advice ?: Advice.UNDERSTAND,
        count = count,
      )
    }
    .sortedWith(compareByDescending<ChangeRowState> { it.count }.thenBy { it.name })

  /** A change's display name: the reference's label with a capital ("Flapping"), or the key in words. */
  fun changeName(reference: Reference, label: ChangeLabel): String {
    val name = reference.labels[label] ?: label.value.replace('_', ' ')
    return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
  }

  /** The web's engine names (`jobInfo.ts` `engineLabel`). */
  fun engineLabel(engine: String?): String? = when (engine) {
    null, "" -> null
    "timit61" -> "TIMIT-61"
    "espeak", "wav2vec2" -> "espeak"
    else -> engine
  }

  /** A source link is opened only when it is a web address (never another scheme from the computer's listing). */
  fun openable(link: String?): Boolean {
    val lower = link?.trim()?.lowercase(Locale.ROOT) ?: return false
    return lower.startsWith("https://") || lower.startsWith("http://")
  }
}
