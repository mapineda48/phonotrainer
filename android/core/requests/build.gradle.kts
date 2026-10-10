// Device queue, file intake, sender, uploads, outbox, re-runs (architecture 8); WP-09. At the contract freeze:
// working stubs (an empty queue). WP-09 adds :core:analysis (link parsing) when it exists.
plugins {
  id("phonotrainer.android.library.instrumented")
  id("phonotrainer.android.hilt")
}

dependencies {
  implementation(project(":core:domain"))
  implementation(project(":core:common"))
  implementation(project(":core:network-api"))
  implementation(project(":core:database"))
  implementation(project(":core:notifications"))
  implementation(project(":core:datastore"))

  testImplementation(project(":core:testing-jvm"))
}
