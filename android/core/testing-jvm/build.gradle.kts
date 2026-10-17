// Test-only JVM fixtures (architecture 2.2, 21.3; work-plan 4.6): settable fakes of every :core:domain and
// :core:network-api interface, the fake computer (WP-05a), AnalysisFixtures (WP-12), TestClock, MainDispatcherRule.
// Only ever a test dependency, and never dependent on an implementation module (architecture 2.2 rule 3).
plugins {
  id("phonotrainer.jvm.library")
  id("phonotrainer.kotlin.serialization")
}

dependencies {
  api(project(":core:domain"))
  api(project(":core:network-api"))
  api(project(":core:common"))

  api(libs.junit)
  api(libs.kotlinx.coroutines.test)
  api(libs.okhttp)
  api(libs.okhttp.mockwebserver3)
}

// ReferenceBodyTest (WP-05a) reads the reference snapshot bundled by :core:content (WP-04), relative to this module's
// directory (the test working directory), to keep the fake computer's default /reference body equal to it. Declaring
// the file as an input keeps the tests' up-to-date check and build cache honest when the snapshot is re-exported.
tasks.withType<Test>().configureEach {
  inputs.file(layout.projectDirectory.file("../content/src/main/assets/reference/reference.json"))
    .withPropertyName("referenceSnapshot")
    .withPathSensitivity(PathSensitivity.NONE)
}
