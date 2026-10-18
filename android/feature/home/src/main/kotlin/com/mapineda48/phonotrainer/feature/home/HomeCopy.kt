package com.mapineda48.phonotrainer.feature.home

import android.content.res.Resources
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The words of Home that depend on numbers and lists (spec F-03, F-08, D-45), in the app's own English, never the
 * system locale (CLAUDE.md: the app is English only).
 */
internal object HomeCopy {
  private val dateline: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.US)

  /** "Sunday, 4 October · Home" (design 9.16 block 1). */
  fun dateline(now: Instant, zone: ZoneId, resources: Resources): String =
    resources.getString(R.string.home_dateline, dateline.format(now.atZone(zone)))

  /**
   * The set's sentence (F-03-AC10): "Today's set: 8 shorts with flapping, linking, h-dropping and 2 more changes; 2
   * saved shorts back; then 5 questions." The saved and question parts appear only when there are some.
   */
  fun todaySentence(summary: TodaySummary, resources: Resources): String {
    val shorts = resources.getQuantityString(R.plurals.home_today_shorts, summary.shorts, summary.shorts)
    val changes = changeList(summary, resources)
    val head = if (changes == null) shorts else resources.getString(R.string.home_today_sentence_with, shorts, changes)
    val parts = buildList {
      add(head)
      if (summary.saved > 0) add(resources.getQuantityString(R.plurals.home_today_saved, summary.saved, summary.saved))
      if (summary.questions > 0) {
        add(resources.getQuantityString(R.plurals.home_today_sentence_questions, summary.questions, summary.questions))
      }
    }
    return resources.getString(R.string.home_today_sentence, parts.joinToString("; ")) + "."
  }

  /** "flapping, linking, h-dropping and 2 more changes"; "flapping and linking"; null when no short has a spotlight. */
  fun changeList(summary: TodaySummary, resources: Resources): String? {
    val items = summary.names + if (summary.moreChanges > 0) {
      listOf(resources.getQuantityString(R.plurals.home_today_more_changes, summary.moreChanges, summary.moreChanges))
    } else {
      emptyList()
    }
    return when (items.size) {
      0 -> null
      1 -> items.single()
      else -> {
        val leading = items.dropLast(1).reduce { acc, next -> resources.getString(R.string.home_today_list_comma, acc, next) }
        resources.getString(R.string.home_today_list_pair, leading, items.last())
      }
    }
  }

  /** "8 shorts, 5 questions · 2 saved shorts back" (design 9.16 block 3). */
  fun todayCounts(summary: TodaySummary, resources: Resources): String {
    val shorts = resources.getQuantityString(R.plurals.home_today_shorts, summary.shorts, summary.shorts)
    val base = if (summary.questions > 0) {
      resources.getString(
        R.string.home_today_counts,
        shorts,
        resources.getQuantityString(R.plurals.home_today_questions, summary.questions, summary.questions),
      )
    } else {
      shorts
    }
    return if (summary.saved > 0) {
      base + resources.getString(R.string.home_today_separator) +
        resources.getQuantityString(R.plurals.home_today_saved, summary.saved, summary.saved)
    } else {
      base
    }
  }

  /** The card's headline: "Today · about 5 minutes", "Today · 3 of 8 done" or "Done for today". */
  fun todayHeadline(card: TodayCard.Ready, resources: Resources): String = when (card.phase) {
    TodayPhase.FRESH -> resources.getQuantityString(R.plurals.home_today_minutes, card.minutes, card.minutes)
    TodayPhase.IN_PROGRESS ->
      resources.getQuantityString(R.plurals.home_today_progress, card.summary.shorts, card.done, card.summary.shorts)
    TodayPhase.DONE -> resources.getString(R.string.home_today_done)
  }

  /** "What you met: flapping ×3, linking ×2". */
  fun met(card: TodayCard.Ready, resources: Resources): String? = card.met.takeIf { it.isNotEmpty() }?.let { met ->
    resources.getString(
      R.string.home_today_met,
      met.joinToString(", ") { (name, count) -> resources.getString(R.string.home_today_met_item, name, count) },
    )
  }

  /**
   * The whole Today's set card as TalkBack reads it: the headline (its " · " read as a comma), the set's sentence, what
   * was met once done, and the short-set note.
   */
  fun todayCardSentence(card: TodayCard.Ready, resources: Resources): String {
    val parts = listOfNotNull(
      todayHeadline(card, resources).replace(resources.getString(R.string.home_today_separator), ", "),
      todaySentence(card.summary, resources).trimEnd('.'),
      if (card.phase == TodayPhase.DONE) met(card, resources) else null,
      shortSet(card.summary, resources),
    )
    return parts.joinToString(". ") { it.trimEnd('.') } + "."
  }

  /** "Only 4 shorts today: add a clip for more." when the set is short of the usual 8 (F-08 States). */
  fun shortSet(summary: TodaySummary, resources: Resources): String? =
    if (summary.shorts in 1 until FULL_SET) {
      resources.getQuantityString(R.plurals.home_today_short_set, summary.shorts, summary.shorts)
    } else {
      null
    }

  /** A full set has 8 shorts (F-08 item 1). */
  const val FULL_SET = 8
}
