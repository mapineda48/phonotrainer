package com.mapineda48.phonotrainer.feature.library

import android.content.res.Resources
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Times in the app's own English words ("today at 14:05", "added 2 days ago"), never the system's locale: the app is
 * English only (CLAUDE.md), so a phone set to another language still reads one language.
 */
internal object LibraryTime {
  private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
  private val shortDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.US)
  private val longDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)

  /** "just now", "today at 14:05", "yesterday at 09:12", "on 4 Oct" (the year only when it is not this one). */
  fun lastSynced(at: Instant, now: Instant, zone: ZoneId, resources: Resources): String {
    if (Duration.between(at, now).abs() < Duration.ofMinutes(1)) return resources.getString(R.string.library_time_just_now)
    val day = at.atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    return when (day.toLocalDate()) {
      today -> resources.getString(R.string.library_time_today, clock.format(day))
      today.minusDays(1) -> resources.getString(R.string.library_time_yesterday, clock.format(day))
      else -> resources.getString(R.string.library_time_date, date(day.toLocalDate(), today))
    }
  }

  /** "added today", "added yesterday", "added 3 days ago" (under a month), else "added on 4 Oct". */
  fun added(at: Instant, now: Instant, zone: ZoneId, resources: Resources): String {
    val day = at.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(day, today)
    return when {
      days <= 0 -> resources.getString(R.string.clip_page_added_today)
      days == 1L -> resources.getString(R.string.clip_page_added_yesterday)
      days < MONTH_DAYS -> resources.getQuantityString(R.plurals.clip_page_added_days, days.toInt(), days.toInt())
      else -> resources.getString(R.string.clip_page_added_on, date(day, today))
    }
  }

  private fun date(day: LocalDate, today: LocalDate): String =
    (if (day.year == today.year) shortDate else longDate).format(day)

  private const val MONTH_DAYS = 30L
}
