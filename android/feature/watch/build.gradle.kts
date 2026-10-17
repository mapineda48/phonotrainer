// :feature:watch: the feed and the short card (architecture 2.2). It depends on :core:player for the composable
// surface only (architecture 11.6), and on :core:common for the dispatcher qualifiers (architecture 3.3).
plugins {
  id("phonotrainer.android.feature")
}

dependencies {
  implementation(project(":core:player"))
  implementation(project(":core:common"))
}
