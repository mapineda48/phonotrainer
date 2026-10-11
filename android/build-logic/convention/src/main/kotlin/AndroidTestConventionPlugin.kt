import com.android.build.api.dsl.TestExtension
import com.android.build.api.variant.TestAndroidComponentsExtension
import com.mapineda48.phonotrainer.buildlogic.Sdk
import com.mapineda48.phonotrainer.buildlogic.configureAndroidCommon
import com.mapineda48.phonotrainer.buildlogic.configureDependencyGuard
import com.mapineda48.phonotrainer.buildlogic.configureManagedDevice
import com.mapineda48.phonotrainer.buildlogic.libs
import com.mapineda48.phonotrainer.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * `phonotrainer.android.test` (architecture 2.3, 21.6): a `com.android.test` module that drives the application as a
 * black box (`:qasmoke`, `:baselineprofile`) with UiAutomator on the managed device, against `:app`'s **unmodified**
 * `qa` APK (R8 and resource shrinking on, not debuggable). The test APK instruments itself in its own process
 * (self-instrumenting), so none of its classes ever load into the minified app; it reaches the app only through the
 * screen, as a learner would. Only the `qa` variant exists: `./gradlew :qasmoke:pixel9Api36QaAndroidTest`.
 */
class AndroidTestConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("com.android.test")
      configureDependencyGuard(jvm = false, compileTask = "preBuild")

      extensions.configure<TestExtension> {
        configureAndroidCommon(this)
        configureManagedDevice(this)
        defaultConfig.targetSdk = Sdk.TARGET
        defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetProjectPath = ":app"
        // The variant named qa targets :app's qa variant; debuggable only so the test APK itself can be instrumented.
        buildTypes.create(QA) {
          isDebuggable = true
          signingConfig = signingConfigs.getByName("debug")
          matchingFallbacks += "release"
        }
        experimentalProperties["android.experimental.self-instrumenting"] = true
      }
      extensions.configure<TestAndroidComponentsExtension> {
        beforeVariants { variant -> variant.enable = variant.buildType == QA }
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
