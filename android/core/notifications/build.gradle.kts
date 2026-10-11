// Channels, Notifier, notification builders, permission state and the in-context permission question (architecture
// 13); WP-02. The "Needs attention" line comes from :core:ui's RequestFailureCopy (architecture 19).
plugins {
  id("phonotrainer.android.library")
  id("phonotrainer.android.hilt")
}

dependencies {
  api(project(":core:domain"))
  implementation(project(":core:common"))
  implementation(project(":core:navigation"))
  implementation(project(":core:ui"))
  implementation(libs.androidx.core.ktx)
  implementation(libs.kotlinx.coroutines.android)

  testImplementation(project(":core:testing-jvm"))
}
