import com.android.build.api.variant.HasUnitTest

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
  // IpaText reads IPA with the one tokenizer of the project (architecture 2.2: why ipa/ lives in :core:common).
  implementation(project(":core:common"))

  testImplementation(project(":core:testing"))
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// The screenshot tests of design 11 over this module's previews, with the support shared with :core:designsystem
// (PreviewScreenshots): goldens compared by default, recorded with -Pphono.screenshots=record.
androidComponents {
  onVariants { variant ->
    val shared = "../designsystem/src/testShared/kotlin"
    (variant as? HasUnitTest)?.unitTest?.sources?.kotlin?.addStaticSourceDirectory(shared)
  }
}

tasks.withType<Test>().configureEach {
  val screenshots = providers.gradleProperty("phono.screenshots").orElse("verify")
  inputs.property("screenshotMode", screenshots)
  inputs.files(fileTree("src/test/screenshots")).withPathSensitivity(PathSensitivity.RELATIVE)
    .withPropertyName("screenshotGoldens")
  systemProperty("phono.screenshots", screenshots.get())
}
