// Navigation keys, internal targets and the navigator API (architecture 16): every key is declared here at the
// contract freeze, and each feature contributes its entries through an EntryInstaller. Frozen at B1.
plugins {
  id("phonotrainer.android.library")
  id("phonotrainer.kotlin.serialization")
}

dependencies {
  api(project(":core:domain"))
  api(libs.androidx.navigation3.runtime)
  api(libs.kotlinx.serialization.json)
}
