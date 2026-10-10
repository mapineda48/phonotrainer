// Channels, Notifier, notification builders, permission state (architecture 13); WP-02 (M3). At the contract freeze:
// a stub Notifier that posts nothing.
plugins {
  id("phonotrainer.android.library")
  id("phonotrainer.android.hilt")
}

dependencies {
  api(project(":core:domain"))
  implementation(project(":core:navigation"))
  implementation(project(":core:ui"))
  implementation(libs.androidx.core.ktx)

  testImplementation(project(":core:testing-jvm"))
}
