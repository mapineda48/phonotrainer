// :feature:settings: Settings and its sub-pages, Storage, About and the open-source licences (spec F-21 UI, F-22,
// F-23's Settings > Notifications, F-24; architecture 2.2).
plugins {
  id("phonotrainer.android.feature")
}

dependencies {
  // rememberLauncherForActivityResult: the notification permission prompt of "Turn on notifications" (spec F-23).
  implementation(libs.androidx.activity.compose)
}

// LicencesTest reads, relative to this module's directory, :core:designsystem's bundled.json, the allowlist of the
// licence check and the repository's LICENSE (the app's licence text must equal it). Declared as inputs, so the tests
// re-run when they change.
tasks.withType<Test>().configureEach {
  val android = rootProject.layout.projectDirectory
  inputs.files(
    android.file("core/designsystem/src/main/assets/licenses/bundled.json"),
    android.file("build-logic/convention/src/main/kotlin/com/mapineda48/phonotrainer/buildlogic/licences/LicenceCheck.kt"),
    android.file("../LICENSE"),
  ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("licenceSources")
}
