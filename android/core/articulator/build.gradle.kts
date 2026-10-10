// v1-complete: the 2D mouth model and its Canvas renderer (architecture 2.2); WP-29. Untouched in v1-core: it exists
// so :app depends on it from wave 1, and it binds no extension point (work-plan 4.4). WP-29 adds :core:content.
plugins {
  id("phonotrainer.android.library")
  id("phonotrainer.android.compose")
}

dependencies {
  implementation(project(":core:designsystem"))
}
