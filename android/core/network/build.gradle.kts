// Implementation of :core:network-api (architecture 2.2, 5); WP-05b. Until then it holds the dependency-shape spike of
// delivery review D-18: its tests use :core:testing-jvm, whose fakes implement :core:network-api, with no cycle.
plugins {
  id("phonotrainer.android.library")
}

dependencies {
  implementation(project(":core:domain"))
  implementation(project(":core:network-api"))

  testImplementation(project(":core:testing-jvm"))
}
