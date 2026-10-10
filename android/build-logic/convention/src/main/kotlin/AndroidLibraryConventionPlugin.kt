import com.android.build.api.dsl.LibraryExtension
import com.mapineda48.phonotrainer.buildlogic.Sdk
import com.mapineda48.phonotrainer.buildlogic.configureAndroidCommon
import com.mapineda48.phonotrainer.buildlogic.configureAndroidUnitTests
import com.mapineda48.phonotrainer.buildlogic.libs
import com.mapineda48.phonotrainer.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * `phonotrainer.android.library` (architecture 2.3): an Android library with the shared SDK, Java and lint settings,
 * the unit-test setup of architecture 21.2 (Robolectric on sdk 35, two forks of 1 GB) and its consumer R8 rules file
 * `consumer-rules.pro`, which every library module keeps (empty when it needs no rule).
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("com.android.library")

      extensions.configure<LibraryExtension> {
        configureAndroidCommon(this)
        configureAndroidUnitTests(this)
        testOptions.targetSdk = Sdk.TARGET
        lint.targetSdk = Sdk.TARGET
        defaultConfig.consumerProguardFiles("consumer-rules.pro")
      }

      dependencies {
        "testImplementation"(libs.library("junit"))
        "testImplementation"(libs.library("truth"))
        "testImplementation"(libs.library("robolectric"))
        // Robolectric tests need ApplicationProvider (architecture 21.2).
        "testImplementation"(libs.library("androidx-test-core"))
      }
    }
  }
}
