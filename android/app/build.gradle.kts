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
// modules (WP-04 :core:content, WP-12 :core:analysis) exist since their merges and are listed with the others.
val everyModule = listOf(
  ":core:domain",
  ":core:common",
  ":core:analysis",
  ":core:network-api",
  ":core:navigation",
  ":core:designsystem",
  ":core:ui",
  ":core:content",
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

dependencies {
  everyModule.forEach { implementation(project(it)) }

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
  // The R8 of this AGP, for StartupOrderR8Test (decisions I-15): the builder jar alone, which holds R8 unshaded under
  // com.android.tools.r8 and nothing else on the test classpath's packages.
  testImplementation(libs.android.builder) { isTransitive = false }
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
  // `@HiltWorker` test workers (e2e/smoke/GlobalTestSetTest) need the AndroidX Hilt processor too.
  kspAndroidTest(libs.androidx.hilt.compiler)

  constraints {
    // The app's runtime classpath decides the version of every library the test APK shares with it, and the
    // end-to-end fake computer (OkHttp's MockWebServer) runs in the app's process: okio must be the version OkHttp
    // needs, not the older one DataStore brings (decisions I-07).
    implementation(libs.okio)
  }
}

// StartupOrderR8Test runs R8 with the release build type's own configuration files (decisions I-15). AGP extracts its
// default file into build/ with `extractProguardFiles`.
val releaseR8Rules: List<File> = android.buildTypes.getByName("release").proguardFiles.toList()
tasks.withType<Test>().configureEach {
  dependsOn("extractProguardFiles")
  inputs.files(releaseR8Rules).withPropertyName("releaseR8Rules").withPathSensitivity(PathSensitivity.NONE)
  systemProperty("phonotrainer.r8.releaseRules", releaseR8Rules.joinToString(File.pathSeparator) { it.absolutePath })
}
