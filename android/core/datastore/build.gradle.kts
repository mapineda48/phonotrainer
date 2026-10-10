// The settings store, the coach-mark store and the pairing store (architecture 4.5, 20.3); WP-02.
plugins {
  id("phonotrainer.android.library")
  id("phonotrainer.android.hilt")
}

dependencies {
  api(project(":core:domain"))
  implementation(project(":core:common"))
  api(libs.androidx.datastore.preferences)
  implementation(libs.kotlinx.coroutines.android)

  testImplementation(project(":core:testing-jvm"))
}
