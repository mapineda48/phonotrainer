import com.android.build.api.dsl.LibraryExtension
import com.mapineda48.phonotrainer.buildlogic.configureManagedDevice
import com.mapineda48.phonotrainer.buildlogic.libs
import com.mapineda48.phonotrainer.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * `phonotrainer.android.library.instrumented` (architecture 2.3, delivery review D-11): adds the managed device and the
 * instrumented test stack to a library. Applied only by `:core:player`, `:core:requests` and `:core:sync`, whose real
 * codecs, `MediaMuxer` and WorkManager scheduling cannot run on Robolectric:
 * `./gradlew :core:<module>:pixel9Api36DebugAndroidTest`, always under the GMD lock.
 */
class AndroidLibraryInstrumentedConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("phonotrainer.android.library")

      extensions.configure<LibraryExtension> {
        defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        configureManagedDevice(this)
      }

      dependencies {
        "androidTestImplementation"(libs.library("androidx-test-core"))
        "androidTestImplementation"(libs.library("androidx-test-ext-junit"))
        "androidTestImplementation"(libs.library("androidx-test-runner"))
        "androidTestImplementation"(libs.library("junit"))
        "androidTestImplementation"(libs.library("kotlinx-coroutines-test"))
        "androidTestImplementation"(libs.library("truth"))
      }
    }
  }
}
