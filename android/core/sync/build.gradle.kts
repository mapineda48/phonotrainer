// Sync orchestrator, catalogue reconcile, downloads, storage budget and eviction, deletion, workers (architecture 7);
// WP-08. Connectivity, corpus, content, study and requests reach it only through :core:domain interfaces and SPIs.
// At the contract freeze: working stubs (idle, nothing on the phone).
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

  testImplementation(project(":core:testing-jvm"))
}
