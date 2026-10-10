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
    // Hilt's test application plus the WorkManager test initialization (architecture 21.5).
    testInstrumentationRunner = "com.mapineda48.phonotrainer.e2e.HiltTestRunner"
  }
}

// :app depends on every module (architecture 2.2, 2.7), so a module that disappears fails the build. The two day-0
// modules of other work packages join as soon as their owners create them (WP-04 :core:content, WP-12
// :core:analysis); settings include a declared module only once its build file exists.
val everyModule = listOf(
  ":core:domain",
  ":core:common",
  ":core:network-api",
  ":core:navigation",
  ":core:designsystem",
  ":core:ui",
  ":core:database",
  ":core:datastore",
  ":core:network",
  ":core:notifications",
  ":core:connectivity",
  ":core:corpus",
  ":core:sync",
  ":core:requests",
  ":core:player",
  ":core:study",
  ":core:articulator",
  ":core:recorder",
  ":feature:onboarding",
  ":feature:connect",
  ":feature:home",
  ":feature:library",
  ":feature:watch",
  ":feature:lesson",
  ":feature:learn",
  ":feature:practice",
  ":feature:requests",
  ":feature:share",
  ":feature:settings",
  ":feature:insights",
)
val dayZeroModules = listOf(":core:content", ":core:analysis")

dependencies {
  everyModule.forEach { implementation(project(it)) }
  dayZeroModules.mapNotNull { rootProject.findProject(it) }.forEach { implementation(it) }

  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.core.splashscreen)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.hilt.work)
  ksp(libs.androidx.hilt.compiler)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.profileinstaller)
  implementation(libs.kotlinx.coroutines.android)

  testImplementation(project(":core:testing"))
  testImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  androidTestImplementation(project(":core:testing-jvm"))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.work.testing)
  androidTestImplementation(libs.androidx.room.runtime)
  androidTestImplementation(libs.androidx.datastore.preferences)
  androidTestImplementation(libs.hilt.android.testing)
  androidTestImplementation(libs.truth)
  kspAndroidTest(libs.hilt.compiler)
}
