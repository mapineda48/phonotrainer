import com.android.build.api.dsl.TestExtension
import com.mapineda48.phonotrainer.buildlogic.Sdk
import com.mapineda48.phonotrainer.buildlogic.configureAndroidCommon
import com.mapineda48.phonotrainer.buildlogic.configureManagedDevice
import com.mapineda48.phonotrainer.buildlogic.libs
import com.mapineda48.phonotrainer.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * `phonotrainer.android.test` (architecture 2.3, 21.6): a `com.android.test` module that drives the application as a
 * black box (`:qasmoke`, `:baselineprofile`) with UiAutomator on the managed device. The module chooses the build type
 * it targets (`qa`, added with that build type by the integrator).
 */
class AndroidTestConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("com.android.test")

      extensions.configure<TestExtension> {
        configureAndroidCommon(this)
        configureManagedDevice(this)
        defaultConfig.targetSdk = Sdk.TARGET
        defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetProjectPath = ":app"
      }

      dependencies {
        "implementation"(libs.library("androidx-test-ext-junit"))
        "implementation"(libs.library("androidx-test-runner"))
        "implementation"(libs.library("androidx-test-uiautomator"))
        "implementation"(libs.library("junit"))
        "implementation"(libs.library("truth"))
      }
    }
  }
}
