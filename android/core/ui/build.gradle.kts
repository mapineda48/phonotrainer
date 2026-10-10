// Domain-aware shared composables over :core:designsystem (architecture 17): marks, chips, status lines, the
// "What's this?" control, IPA text, the loading gate, empty states, coach marks, the accessibility helpers of
// architecture 18, the extension points of work-plan 4.4 (ui/extension/, integrator) and the shared components of
// work-plan 4.5 (ui/component/, one owner each). WP-02 provides the frozen API with placeholder visuals; WP-03 owns
// the primitives after the contract freeze.
plugins {
  id("phonotrainer.android.library")
  id("phonotrainer.android.compose")
  id("phonotrainer.android.hilt")
}

dependencies {
  api(project(":core:designsystem"))
  api(project(":core:domain"))
  api(project(":core:navigation"))

  testImplementation(project(":core:testing"))
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}
