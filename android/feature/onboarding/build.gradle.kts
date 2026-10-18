// :feature:onboarding: the Welcome (spec F-01) and the coach-mark catalogue (architecture 2.2).
plugins {
  id("phonotrainer.android.feature")
}

dependencies {
  // BackHandler: system Back steps back through the Welcome's pages.
  implementation(libs.androidx.activity.compose)
}
