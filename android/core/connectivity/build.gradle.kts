// Network monitor, UDP discovery, the probe state machine, pairing and background restrictions (architecture 6;
// WP-07). Reaches the computer only through :core:network-api; the clips and requests a forgotten computer leaves
// are updated in :core:database's library database.
plugins {
  id("phonotrainer.android.library")
  id("phonotrainer.android.hilt")
}

dependencies {
  implementation(project(":core:domain"))
  implementation(project(":core:common"))
  implementation(project(":core:network-api"))
  implementation(project(":core:datastore"))
  implementation(project(":core:database"))

  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)

  testImplementation(project(":core:testing-jvm"))
  testImplementation(libs.turbine)
  testImplementation(libs.androidx.work.testing)
}
