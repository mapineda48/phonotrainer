// v1-complete: microphone takes, A/B playback, the /compare client (architecture 14); WP-30. Untouched in v1-core: it
// exists so :app depends on it from wave 1, and it binds no extension point (work-plan 4.4).
plugins {
  id("phonotrainer.android.library")
}

dependencies {
  implementation(project(":core:network-api"))
  implementation(project(":core:player"))
}
