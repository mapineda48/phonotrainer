import com.android.build.api.dsl.ApplicationExtension
import com.mapineda48.phonotrainer.buildlogic.Sdk
import com.mapineda48.phonotrainer.buildlogic.configureAndroidCommon
import com.mapineda48.phonotrainer.buildlogic.configureAndroidUnitTests
import com.mapineda48.phonotrainer.buildlogic.configureManagedDevice
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `phonotrainer.android.application` (architecture 2.3): the application module. SDK levels, Java 17, the lint
 * policy with `checkDependencies`, R8 and resource shrinking in release, the managed device and packaging excludes.
 * The `qa` build type of architecture 21.6 is added by the integrator (WP-19).
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("com.android.application")

      extensions.configure<ApplicationExtension> {
        configureAndroidCommon(this)
        configureAndroidUnitTests(this)
        configureManagedDevice(this)
        defaultConfig.targetSdk = Sdk.TARGET
        lint.checkDependencies = true

        buildTypes.getByName("release") {
          isMinifyEnabled = true
          isShrinkResources = true
          proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
  }
}
