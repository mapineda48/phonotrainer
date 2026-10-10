package com.mapineda48.phonotrainer.buildlogic

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.Lint
import com.android.build.api.dsl.ManagedVirtualDevice
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.HasUnitTest
import org.gradle.api.Project
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.registerIfAbsent
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension

/**
 * What every Android module shares, applications and libraries alike: SDK levels, the Java 17 toolchain, the lint
 * policy of architecture 2.3 and the unit-test setup of architecture 21.2.
 */
internal fun Project.configureAndroidCommon(android: CommonExtension) {
  android.compileSdk = Sdk.COMPILE
  android.defaultConfig.minSdk = Sdk.MIN
  android.namespace = android.namespace ?: defaultNamespace()

  android.compileOptions.apply {
    sourceCompatibility = Sdk.JAVA_VERSION
    targetCompatibility = Sdk.JAVA_VERSION
  }
  extensions.configure<KotlinBaseExtension> {
    jvmToolchain(Sdk.JAVA)
  }

  configureLintPolicy(android.lint)
}

/** Architecture 2.3: warnings fail the build, one shared lint.xml, never a baseline. */
internal fun Project.configureLintPolicy(lint: Lint) {
  lint.warningsAsErrors = true
  lint.abortOnError = true
  lint.checkReleaseBuilds = true
  lint.lintConfig = rootProject.file("config/lint/lint.xml")
}

/**
 * Local unit tests of an Android module (architecture 21.2): Android resources on the test classpath, Robolectric on
 * sdk 35 through a generated `robolectric.properties` (no per-class annotation), two forks of 1 GB, and at most one
 * Android unit-test task at a time in the whole build, which keeps one copy of the project near the 6.5 GB budget of
 * architecture 2.4.
 */
internal fun Project.configureAndroidUnitTests(android: CommonExtension) {
  android.testOptions.unitTests.isIncludeAndroidResources = true

  val robolectricConfig = tasks.register<GenerateRobolectricConfig>("generateRobolectricConfig") {
    sdk.set(Sdk.ROBOLECTRIC)
  }
  androidComponents.onVariants { variant ->
    (variant as? HasUnitTest)?.unitTest?.sources?.resources
      ?.addGeneratedSourceDirectory(robolectricConfig, GenerateRobolectricConfig::outputDirectory)
  }

  val slots = gradle.sharedServices.registerIfAbsent("androidUnitTestSlots", AndroidUnitTestSlots::class) {
    maxParallelUsages.set(1)
  }
  tasks.withType<Test>().configureEach {
    maxParallelForks = 2
    maxHeapSize = "1g"
    usesService(slots)
    // A module with no unit test yet still hands this task its main classes when a bytecode transform runs (Hilt's,
    // for local tests), which Gradle 9 reads as "test sources but no tests discovered". JVM modules keep the check.
    failOnNoDiscoveredTests.set(false)
  }
}

/** Shared by every Android unit-test task of the build to bound how many run at once. */
abstract class AndroidUnitTestSlots : BuildService<BuildServiceParameters.None>

/** The `androidComponents` extension of an application, library or test module (the variant API). */
internal val Project.androidComponents: AndroidComponentsExtension<*, *, *>
  get() = extensions.getByType(AndroidComponentsExtension::class.java)

/** The Gradle Managed Device every instrumented suite runs on (architecture 21.1). */
internal fun configureManagedDevice(android: CommonExtension) {
  android.testOptions.apply {
    animationsDisabled = true
    managedDevices.allDevices.create(Sdk.MANAGED_DEVICE, ManagedVirtualDevice::class.java) {
      device = "Pixel 9"
      sdkVersion = Sdk.MANAGED_DEVICE_API
      // Automated Test Device images drop the apps and services that tests do not need.
      systemImageSource = "aosp-atd"
      // Pinned: newer plugins default to arm64-v8a, which the x86_64 images of this computer cannot run.
      testedAbi = "x86_64"
    }
  }
}

/** `:core:network` → `com.mapineda48.phonotrainer.core.network` (architecture 2.6). */
internal fun Project.defaultNamespace(): String =
  Sdk.NAMESPACE_ROOT + path.split(':').filter { it.isNotEmpty() }
    .joinToString(separator = ".", prefix = ".") { it.replace("-", "") }
