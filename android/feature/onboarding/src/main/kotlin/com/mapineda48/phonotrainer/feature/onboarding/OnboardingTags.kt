package com.mapineda48.phonotrainer.feature.onboarding

/** Test tags of :feature:onboarding ("onboarding.<screen>.<element>", work-plan 2.6). */
object OnboardingTags {
  /** The Welcome page (P-13), whatever page shows. */
  const val WELCOME = "onboarding.welcome.page"

  /** "Skip", on every page (F-01-AC1). */
  const val SKIP = "onboarding.welcome.skip"

  /** "Next", on pages 1 and 2. */
  const val NEXT = "onboarding.welcome.next"

  /** "Get started" on the last page ("Done" when the tour is replayed from Settings > Help). */
  const val GET_STARTED = "onboarding.welcome.getStarted"

  /** The kicker "1 of 3" and the page's title, read together. */
  const val PAGE_TITLE = "onboarding.welcome.title"

  /** The page's one sentence. */
  const val PAGE_BODY = "onboarding.welcome.body"

  /** The sample slip of page 1. */
  const val SAMPLE = "onboarding.welcome.sample"

  /** The page indicator (decorative: the kicker says the same). */
  const val INDICATOR = "onboarding.welcome.indicator"
}
