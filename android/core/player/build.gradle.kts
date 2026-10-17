// Media3 player pool, short and span playback, audio arbitration, shadowing runner, decoder capability (architecture
// 11); WP-11. The clip's media and its phrase bounds come through :core:domain's ClipContentRepository (WP-10), never
// through :core:corpus. Instrumented tests run on the managed device with real codecs and SyntheticMedia
// (:core:testing synthetic/, WP-11): `./gradlew :core:player:pixel9Api36DebugAndroidTest`, always under the GMD lock.
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
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.ui.compose)
  implementation(libs.kotlinx.coroutines.android)

  testImplementation(project(":core:testing-jvm"))

  // SyntheticMedia, the settable fakes and the Compose test rule. Robolectric is left out: these tests run on the
  // device, and its jars would only bloat the test APK.
  androidTestImplementation(project(":core:testing")) {
    exclude(group = "org.robolectric")
  }
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.compose.ui.test.manifest)
}
