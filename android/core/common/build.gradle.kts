// Pure utilities shared by core modules and features (architecture 2.2): RetrySchedule, streaming SHA-256, UUID
// helpers, the PhonoLog facade, AppError helpers; `ipa/` and `shadowing/` belong to WP-12.
plugins {
  id("phonotrainer.jvm.library")
}

dependencies {
  api(project(":core:domain"))
}
