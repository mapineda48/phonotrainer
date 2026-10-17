// Device queue, file intake, sender, uploads, outbox, re-runs (architecture 8; WP-09). Its workers (the expedited
// shared-file intake, the picked-file intake, the uploads) are Hilt workers; MediaExtractor and MediaMuxer extract the
// audio track of a local video. The core instrumented suite runs on the managed device under the GMD lock.
plugins {
  id("phonotrainer.android.library.instrumented")
  id("phonotrainer.android.hilt")
}

dependencies {
  implementation(project(":core:domain"))
  implementation(project(":core:common"))
  implementation(project(":core:analysis"))
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

  testImplementation(project(":core:testing-jvm"))
  testImplementation(libs.androidx.work.testing)
  testImplementation(libs.turbine)

  androidTestImplementation(project(":core:testing-jvm"))
  androidTestImplementation(libs.androidx.work.testing)
  androidTestImplementation(libs.androidx.test.uiautomator)
}
