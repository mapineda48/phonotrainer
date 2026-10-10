// Test-only Android helpers (architecture 2.2, 18, 21): A11yAudit, the Compose test helpers and the fakes of
// Android-facing interfaces, over :core:testing-jvm. Only ever a test dependency; never on an implementation module.
plugins {
  id("phonotrainer.android.library")
  id("phonotrainer.android.compose")
}

dependencies {
  api(project(":core:testing-jvm"))
  api(project(":core:domain"))
  api(project(":core:designsystem"))
  api(libs.androidx.compose.ui.test.junit4)
  api(libs.robolectric)
  api(libs.androidx.test.core)
  api(libs.truth)

  debugImplementation(libs.androidx.compose.ui.test.manifest)
}
