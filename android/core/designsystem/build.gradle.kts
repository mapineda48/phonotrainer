// Theme, tokens, brand resources and base components (architecture 17, design 12.1). WP-02 provides the placeholder
// at the contract freeze with the frozen resource names of work-plan 4.1; WP-03 replaces the visuals, never the names.
plugins {
  id("phonotrainer.android.library")
  id("phonotrainer.android.compose")
}

dependencies {
  api(project(":core:domain"))
  api(libs.androidx.compose.foundation)
  api(libs.androidx.compose.material3)
  api(libs.androidx.compose.ui)
  // Theme.Phono.Starting extends the splash-screen compat theme.
  api(libs.androidx.core.splashscreen)
}
