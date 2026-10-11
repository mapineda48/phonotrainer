import com.android.build.api.variant.HasUnitTest

// Theme, tokens, brand resources and base components (architecture 17, design 12.1). WP-03 fills the API that WP-02
// froze at the contract freeze; the resources come from the design track's staging export through
// android/design-src/convert/convert.py, under the frozen names of work-plan 4.1.
plugins {
  id("phonotrainer.android.library")
  id("phonotrainer.android.compose")
}

dependencies {
  api(project(":core:domain"))
  // The BOM travels with the Compose artifacts this module exposes, so consumers without the Compose plugin resolve
  // the same versions.
  api(platform(libs.androidx.compose.bom))
  api(libs.androidx.compose.foundation)
  api(libs.androidx.compose.material3)
  api(libs.androidx.compose.ui)
  // Theme.Phono.Starting extends the splash-screen compat theme.
  api(libs.androidx.core.splashscreen)

  testImplementation(project(":core:testing"))
  // The conversion tests read palette.json, icons.json and bundled.json.
  testImplementation(libs.kotlinx.serialization.json)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// The screenshot tests of design 11 over this module's previews. Their support (PreviewScreenshots) is a test source
// root of both modules of the design system, this one and :core:ui. Goldens are compared by default and recorded with
// -Pphono.screenshots=record.
androidComponents {
  onVariants { variant ->
    (variant as? HasUnitTest)?.unitTest?.sources?.kotlin?.addStaticSourceDirectory("src/testShared/kotlin")
  }
}

tasks.withType<Test>().configureEach {
  val screenshots = providers.gradleProperty("phono.screenshots").orElse("verify")
  inputs.property("screenshotMode", screenshots)
  inputs.files(fileTree("src/test/screenshots")).withPathSensitivity(PathSensitivity.RELATIVE)
    .withPropertyName("screenshotGoldens")
  systemProperty("phono.screenshots", screenshots.get())
}

// The JVM tests prove the conversion against the design track's sources (the 173-file inventory, the palette drift,
// bundled.json, the notices rows) and the glyph rule against every module's strings, reading them relative to this
// module's directory (the test task's working directory). Declared as inputs, so the tests re-run when they change.
tasks.withType<Test>().configureEach {
  val android = rootProject.layout.projectDirectory
  inputs.dir(android.dir("design-src/export")).withPathSensitivity(PathSensitivity.RELATIVE)
    .withPropertyName("designExport")
  inputs.files(android.file("design-src/palette.json"), android.file("design-src/icons.json"))
    .withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("designSources")
  inputs.file(android.file("../THIRD-PARTY-NOTICES.md")).withPathSensitivity(PathSensitivity.NONE)
    .withPropertyName("notices")
  inputs.files(
    android.asFileTree.matching {
      include("**/src/main/res/values*/strings*.xml")
      exclude("**/build/**", ".gradle/**", "**/.kotlin/**")
    },
  ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("allStrings")
}
