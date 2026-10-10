// Pure clip derivations (architecture 2.2, 9; work-plan WP-12): the tolerant analysis.json parser, shorts v1, the
// spotlight rule, occurrence extraction, example picking, the lesson's compare rows and link parsing. Plain
// Kotlin/JVM, so every rule is tested in milliseconds without Robolectric.
plugins {
  id("phonotrainer.jvm.library")
  id("phonotrainer.kotlin.serialization")
}

dependencies {
  api(project(":core:domain"))
  api(project(":core:common"))

  testImplementation(project(":core:testing-jvm"))
}

// IpaTokensReferenceTest reads the reference snapshot bundled by :core:content (WP-04), relative to this module's
// directory (the test working directory), to check the tokenizer against the computer's real `ipa_tokens`. Declaring
// the file as an input keeps the tests' up-to-date check and build cache honest when the snapshot changes.
tasks.withType<Test>().configureEach {
  inputs.file(layout.projectDirectory.file("../content/src/main/assets/reference/reference.json"))
    .withPropertyName("referenceSnapshot")
    .withPathSensitivity(PathSensitivity.NONE)
}
