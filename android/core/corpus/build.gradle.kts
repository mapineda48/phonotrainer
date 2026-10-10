// Clip indexer, parsed-clip cache, shorts, occurrences and feed queries (architecture 9); WP-10. At the contract
// freeze: working stubs (no clip on the phone). WP-10 adds :core:analysis and :core:content when they exist.
plugins {
  id("phonotrainer.android.library")
  id("phonotrainer.android.hilt")
}

dependencies {
  implementation(project(":core:domain"))
  implementation(project(":core:common"))
  implementation(project(":core:database"))

  testImplementation(project(":core:testing-jvm"))
}
