package com.mapineda48.phonotrainer.feature.onboarding

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.mapineda48.phonotrainer.core.designsystem.illustration.PhonoIllustrations

/** The three pages of the Welcome (spec F-01 behaviour 1; design 7.2 for the illustrations), in order. */
internal enum class WelcomePage(@DrawableRes val illustration: Int, @StringRes val title: Int, @StringRes val body: Int) {
  LISTEN(PhonoIllustrations.WelcomeListen, R.string.welcome_title, R.string.welcome_lede),
  CLIPS(PhonoIllustrations.WelcomeClips, R.string.welcome_clips_title, R.string.welcome_clips_lede),
  DAILY(PhonoIllustrations.WelcomeDaily, R.string.welcome_daily_title, R.string.welcome_daily_lede),
  ;

  /** 1-based, as the kicker says it. */
  val number: Int get() = ordinal + 1

  val isLast: Boolean get() = this == entries.last()

  val next: WelcomePage? get() = entries.getOrNull(ordinal + 1)

  val previous: WelcomePage? get() = entries.getOrNull(ordinal - 1)

  companion object {
    val count: Int get() = entries.size

    /** The page at [index], clamped (a saved index from an older build can never crash the Welcome). */
    fun at(index: Int): WelcomePage = entries[index.coerceIn(0, entries.lastIndex)]
  }
}
