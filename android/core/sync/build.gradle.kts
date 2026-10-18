// Sync orchestrator, catalogue reconcile, downloads, storage budget and eviction, deletion, workers (architecture 7);
// WP-08. Connectivity, corpus, content, study and requests reach it only through :core:domain interfaces and SPIs;
// the computer only through :core:network-api. Its workers are Hilt workers; the core instrumented suite (the
// user-visible download worker on the managed device) runs under the GMD lock.
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

  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.hilt.work)
  ksp(libs.androidx.hilt.compiler)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.core.ktx)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(project(":core:testing-jvm"))
  testImplementation(libs.androidx.work.testing)
  testImplementation(libs.turbine)

  androidTestImplementation(project(":core:testing-jvm"))
  androidTestImplementation(libs.androidx.work.testing)
}
