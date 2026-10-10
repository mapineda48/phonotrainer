// Network monitor, UDP discovery, probe state machine, pairing, background restrictions (architecture 6); WP-07.
// At the contract freeze: working stubs ("not connected") and the frozen ConnectivitySeamsModule.
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

  testImplementation(project(":core:testing-jvm"))
}
