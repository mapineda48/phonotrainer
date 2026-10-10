// :feature:watch: the feed and the short card (architecture 2.2). It depends on :core:player for the composable
// surface only (architecture 11.6). At the contract freeze it holds a stub destination.
plugins {
  id("phonotrainer.android.feature")
}

dependencies {
  implementation(project(":core:player"))
}
