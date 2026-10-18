package com.mapineda48.phonotrainer.feature.home

import com.mapineda48.phonotrainer.core.domain.model.ChangeAccuracy
import com.mapineda48.phonotrainer.core.domain.model.ChangeCount
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.Reference
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.SavedItem
import com.mapineda48.phonotrainer.core.domain.model.SavedKind
import com.mapineda48.phonotrainer.core.domain.model.StudySet
import com.mapineda48.phonotrainer.core.domain.model.StudySetItem
import com.mapineda48.phonotrainer.core.domain.model.familyFor
import java.time.Instant
import java.util.Locale

/** How Home carries out a request action offered inline on the status card. */
internal enum class RequestRoute { PERFORM, DETAIL, CONNECT_AGAIN }

/** The pure rules of Home (spec F-03, F-08, D-45, D-48): what each block shows, decided off the UI. */
internal object HomeRules {
  /** The most clips the shelf shows (F-03 item 7). */
  const val SHELF_SIZE = 10

  /** At most this many changes are named in the set's sentence (F-03-AC10). */
  const val NAMED_CHANGES = 3

  /** The most "New on this phone" cards at once. */
  const val NEW_CARDS = 3

  /** About how long a question takes, for "Today · about 5 minutes". */
  private const val QUESTION_MS = 20_000L
  private const val MINUTE_MS = 60_000L

  /**
   * The blocks Home shows for [state], in the order of F-03 (F-25 list item 3): the status card; the first-run teaching
   * block or the empty state; Today's set; Keep watching; Add a clip; New on this phone; the shelf (or the catalogue
   * error in its place); Watch by change; Practice; the Insights teaser when Insights is bound and has one.
   */
  fun blocks(state: HomeUiState): List<HomeBlock> = buildList {
    add(HomeBlock.STATUS)
    when {
      state.firstRun -> add(HomeBlock.TEACHING)
      state.empty -> add(HomeBlock.EMPTY)
    }
    add(HomeBlock.TODAY)
    if (state.keepWatching != null) add(HomeBlock.KEEP_WATCHING)
    add(HomeBlock.ADD)
    if (state.newOnPhone.isNotEmpty()) add(HomeBlock.NEW)
    add(if (state.catalogue == HomeCatalogue.Failed) HomeBlock.ERROR else HomeBlock.SHELF)
    add(HomeBlock.CHANGES)
    add(HomeBlock.PRACTICE)
    if (state.insights != null) add(HomeBlock.INSIGHTS)
  }

  /** Today's set card from the set (or its absence) and what the phone holds (F-03 item 3, F-08 States). */
  fun todayCard(set: StudySet?, catalogue: HomeCatalogue, preparing: Boolean, reference: Reference): TodayCard = when {
    set != null && set.items.any { it.isLive() } -> readyCard(set, reference)
    catalogue == HomeCatalogue.Loading -> TodayCard.Preparing
    catalogue !is HomeCatalogue.Ready || catalogue.onPhone == 0 -> TodayCard.NoClips
    preparing -> TodayCard.Preparing
    else -> TodayCard.NothingNew
  }

  private fun StudySetItem.isLive(): Boolean = this !is StudySetItem.Short || !dropped

  private fun readyCard(set: StudySet, reference: Reference): TodayCard.Ready {
    val shorts = set.items.filterIsInstance<StudySetItem.Short>().filterNot { it.dropped }
    val questions = set.items.filterIsInstance<StudySetItem.QuestionItem>()
    val done = shorts.count { it.done }
    val phase = when {
      set.completedAt != null -> TodayPhase.DONE
      done > 0 || questions.any { it.choice != null } -> TodayPhase.IN_PROGRESS
      else -> TodayPhase.FRESH
    }
    return TodayCard.Ready(
      summary = summary(set, reference),
      minutes = minutes(set),
      stations = set.items.mapNotNull { item ->
        when (item) {
          is StudySetItem.Short -> if (item.dropped) null else {
            Station.Short(item.spotlight?.let { reference.familyFor(it.change) }, item.savedTag, item.done)
          }
          is StudySetItem.QuestionItem -> Station.Question(item.choice != null)
        }
      },
      phase = phase,
      done = done,
      met = met(shorts, reference),
    )
  }

  /**
   * What the set holds, for its sentence (F-03-AC10, D-45): the shorts not dropped, each distinct spotlight change
   * once in set order with at most [NAMED_CHANGES] names and the rest counted, the saved shorts and the questions.
   */
  fun summary(set: StudySet, reference: Reference): TodaySummary {
    val shorts = set.items.filterIsInstance<StudySetItem.Short>().filterNot { it.dropped }
    val changes = shorts.mapNotNull { it.spotlight?.change }.distinct().map { name(reference, it) }
    return TodaySummary(
      shorts = shorts.size,
      names = changes.take(NAMED_CHANGES),
      moreChanges = (changes.size - NAMED_CHANGES).coerceAtLeast(0),
      saved = shorts.count { it.savedTag },
      questions = set.items.count { it is StudySetItem.QuestionItem },
    )
  }

  /** "about N minutes": the shorts' own length plus about 20 s per question, rounded up, at least one minute. */
  fun minutes(set: StudySet): Int {
    val shortsMs = set.items.filterIsInstance<StudySetItem.Short>().filterNot { it.dropped }
      .sumOf { (it.short.playEndMs - it.short.playStartMs).coerceAtLeast(0) }
    val questionsMs = set.items.count { it is StudySetItem.QuestionItem } * QUESTION_MS
    val total = shortsMs + questionsMs
    return ((total + MINUTE_MS - 1) / MINUTE_MS).toInt().coerceAtLeast(1)
  }

  /** "What you met": the set's spotlight changes with how many shorts had each, most first, then in set order. */
  private fun met(shorts: List<StudySetItem.Short>, reference: Reference): List<Pair<String, Int>> {
    val counts = LinkedHashMap<ChangeLabel, Int>()
    shorts.forEach { short -> short.spotlight?.change?.let { counts[it] = (counts[it] ?: 0) + 1 } }
    val order = counts.keys.toList()
    return counts.entries.sortedWith(compareByDescending<Map.Entry<ChangeLabel, Int>> { it.value }.thenBy { order.indexOf(it.key) })
      .map { name(reference, it.key) to it.value }
  }

  /** A change's name as the reference writes it ("flapping", "t/d deletion"), for sentences. */
  fun name(reference: Reference, label: ChangeLabel): String = reference.labels[label] ?: label.value.replace('_', ' ')

  /** A change's name with a capital ("Flapping"), for chips and headings. */
  fun title(reference: Reference, label: ChangeLabel): String =
    name(reference, label).replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

  /**
   * Watch by change (F-03 item 8): every label of the reference, grouped by the family whose mark it wears (the four
   * families, then the lexical contraction, then the other changes), each with its short count (D-48; an absent key
   * is 0).
   */
  fun changeGroups(reference: Reference, counts: Map<ChangeLabel, ChangeCount>): List<ChangeGroup> {
    val chips = reference.labels.keys.map { label ->
      ChangeChipState(label, title(reference, label), reference.familyFor(label), counts[label]?.shorts ?: 0)
    }
    return Family.entries.mapNotNull { family ->
      val inFamily = chips.filter { it.family == family }
      if (inFamily.isEmpty()) return@mapNotNull null
      val order = reference.families.firstOrNull { it.family == family }?.members
      val sorted = if (order == null) inFamily else inFamily.sortedBy { chip -> order.indexOf(chip.label).let { if (it < 0) Int.MAX_VALUE else it } }
      ChangeGroup(family, sorted)
    }
  }

  /** "Worth reviewing": the change with the lowest accuracy among those answered, then the most answered, then name. */
  fun weakSpot(accuracy: List<ChangeAccuracy>, reference: Reference): WeakSpot? = accuracy
    .filter { it.seen > 0 }
    .minWithOrNull(
      compareBy<ChangeAccuracy> { it.correct.toDouble() / it.seen }.thenByDescending { it.seen }.thenBy { it.change.value },
    )
    ?.let { WeakSpot(name(reference, it.change), it.correct.coerceIn(0, it.seen), it.seen) }

  /** "12 saved shorts". */
  fun savedShorts(saved: List<SavedItem>): Int = saved.count { it.kind == SavedKind.SHORT }

  /**
   * The clips the shelf considers: the [SHELF_SIZE] newest and the [SHELF_SIZE] most recently added that were
   * studied (a short seen), so a clip studied lately can come back to the shelf. Their details give the time each
   * was last watched ([shelf] sorts by it).
   */
  fun shelfCandidates(clips: List<ClipSummary>): List<ClipSummary> {
    val newest = clips.sortedWith(NEWEST)
    return (newest.take(SHELF_SIZE) + newest.filter { it.shortsSeen > 0 }.take(SHELF_SIZE)).distinctBy { it.id }
  }

  /** The shelf (F-03 item 7): the [SHELF_SIZE] clips most recently studied or added. */
  fun <T> shelf(candidates: List<Pair<T, Instant>>): List<T> =
    candidates.sortedByDescending { it.second }.take(SHELF_SIZE).map { it.first }

  /** The moment a clip was last studied or added, whichever is later. */
  fun lastActivity(clip: ClipSummary, lastWatched: Instant?): Instant =
    if (lastWatched != null && lastWatched.isAfter(clip.addedAt)) lastWatched else clip.addedAt

  /** "On this phone": the study pack is here (a clip "Bringing to this phone" is not yet). */
  fun onPhone(clips: List<ClipSummary>): Int = clips.count { it.availability == ClipAvailability.OnThisPhone }

  /** Clips the phone means to have: every clip but those the learner removed or that are still on the computer. */
  fun wanted(clips: List<ClipSummary>): Int = clips.count {
    it.availability != ClipAvailability.NotOnThisPhone && it.availability != ClipAvailability.GettingReadyOnComputer
  }

  /** The oldest request that needs attention (its primary action is offered inline on the status card). */
  fun attention(requests: List<Request>): Request? =
    requests.filter { !it.rerunPending && it.state in FAILED }.minByOrNull { it.createdAt }

  /** The progress of the only analyzing request, for "1 analyzing · 40 % · Hear the phones". */
  fun analyzing(requests: List<Request>): Request? = requests.singleOrNull { it.state == RequestState.ANALYZING && !it.rerunPending }

  /** How an inline request action is carried out: here, in the request detail (confirmations), or in Connect. */
  fun route(action: RequestAction): RequestRoute = when (action) {
    RequestAction.SEND_NOW, RequestAction.STOP_SENDING, RequestAction.TRY_AGAIN, RequestAction.TRY_AUDIO_ONLY,
    RequestAction.UNDO_CANCEL, RequestAction.CANCEL_PREPARING -> RequestRoute.PERFORM
    RequestAction.CONNECT_AGAIN -> RequestRoute.CONNECT_AGAIN
    RequestAction.CANCEL, RequestAction.REMOVE, RequestAction.OPEN_LINK, RequestAction.SHOW_DETAILS,
    RequestAction.WHY -> RequestRoute.DETAIL
  }

  /** Newest first; equal seconds by job id, so the order never depends on how a list arrived (F-04-AC4). */
  private val NEWEST: Comparator<ClipSummary> = compareByDescending<ClipSummary> { it.addedAt }.thenByDescending { it.id.value }

  private val FAILED = setOf(RequestState.NEEDS_ATTENTION, RequestState.COPY_FAILED, RequestState.COPY_MISSING)
}
