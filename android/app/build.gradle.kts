plugins {
  id("phonotrainer.android.application")
  id("phonotrainer.android.compose")
  id("phonotrainer.android.hilt")
}

android {
  namespace = "com.mapineda48.phonotrainer"

  defaultConfig {
    applicationId = "com.mapineda48.phonotrainer"
    versionCode = 1
    versionName = "0.1.0"
  }
}

// :app depends on every module (architecture 2.2); the contract freeze (M2) adds each module as it is created.
dependencies {
  implementation(project(":core:domain"))
  implementation(project(":core:common"))
  implementation(project(":core:network-api"))
  implementation(project(":core:network"))

  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.core.ktx)
}
