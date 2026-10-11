import com.android.build.api.dsl.ApplicationExtension
import com.mapineda48.phonotrainer.buildlogic.Sdk
import com.mapineda48.phonotrainer.buildlogic.configureAndroidCommon
import com.mapineda48.phonotrainer.buildlogic.configureAndroidUnitTests
import com.mapineda48.phonotrainer.buildlogic.configureLicenceReport
import com.mapineda48.phonotrainer.buildlogic.configureManagedDevice
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `phonotrainer.android.application` (architecture 2.3): the application module. SDK levels, Java 17, the lint
 * policy with `checkDependencies`, R8 and resource shrinking in release, the `qa` build type of architecture 21.6,
 * the licence report and check (20.6), the managed device and packaging excludes.
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

        val release = buildTypes.getByName("release") {
          isMinifyEnabled = true
          isShrinkResources = true
          proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Architecture 21.6: the release build as it ships (R8, resource shrinking, not debuggable), signed with the
        // per-machine debug key so it installs on emulators only; :qasmoke drives it as a black box. Libraries have
        // no qa build type, so they fall back to their release variant.
        buildTypes.create(QA) {
          initWith(release)
          isDebuggable = false
          signingConfig = signingConfigs.getByName("debug")
          matchingFallbacks += "release"
        }
        packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
      configureLicenceReport()
    }
  }
}

/** The release-like build type of architecture 21.6, targeted by `:qasmoke` and `:baselineprofile`. */
internal const val QA = "qa"
