// Implementation of :core:network-api (architecture 2.2, 5); WP-05b. At the contract freeze it binds working stubs
// ("not connected") and the frozen NetworkPolicyModule; its tests keep the dependency-shape spike of delivery review
// D-18 (they use :core:testing-jvm, whose fakes implement :core:network-api, with no cycle).
plugins {
  id("phonotrainer.android.library")
  id("phonotrainer.android.hilt")
}

dependencies {
  implementation(project(":core:domain"))
  api(project(":core:network-api"))
  implementation(project(":core:common"))
  implementation(project(":core:datastore"))

  testImplementation(project(":core:testing-jvm"))
}
