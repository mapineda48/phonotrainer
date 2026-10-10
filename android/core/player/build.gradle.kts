// Media3 player pool, short and span playback, audio arbitration, shadowing runner, decoder capability (architecture
// 11); WP-11. At the contract freeze: working stubs (nothing plays) and the composable surface's signature. WP-11 adds
// Media3 from the catalog.
plugins {
  id("phonotrainer.android.library.instrumented")
  id("phonotrainer.android.compose")
  id("phonotrainer.android.hilt")
}

dependencies {
  api(project(":core:domain"))
  implementation(project(":core:common"))
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.ui)

  testImplementation(project(":core:testing-jvm"))
}
