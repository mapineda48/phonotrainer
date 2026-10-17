// The one HTTP stack of architecture 5 (WP-05b): the network binding, the cleartext guard, PT1 signatures and identity
// proofs, the Retrofit `ComputerApi` with its validated DTOs, artifact transfers and the job channel. Only :app depends
// on this module; everything else reaches the computer through :core:network-api (architecture 2.2).
plugins {
  id("phonotrainer.android.library")
  id("phonotrainer.android.hilt")
  id("phonotrainer.kotlin.serialization")
}

dependencies {
  implementation(project(":core:domain"))
  api(project(":core:network-api"))
  implementation(project(":core:common"))
  implementation(project(":core:datastore"))

  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.okhttp)
  implementation(libs.retrofit)
  implementation(libs.retrofit.converter.kotlinx.serialization)
  // The request log of debug builds only, at HEADERS with Authorization redacted (architecture 20.4, M-7).
  debugImplementation(libs.okhttp.logging.interceptor)

  testImplementation(project(":core:testing-jvm"))
  testImplementation(libs.turbine)
}

// HostComputerSessionTest runs against the host-side fake computer (android/scripts/device-qa/host_computer.py) only
// when its address and a fresh pairing code are given; without them it is skipped (work-plan WP-05b, wave-1 review).
tasks.withType<Test>().configureEach {
  listOf("PHONOTRAINER_HOST_COMPUTER", "PHONOTRAINER_PAIRING_CODE").forEach { name ->
    System.getenv(name)?.let { environment(name, it) }
  }
}
