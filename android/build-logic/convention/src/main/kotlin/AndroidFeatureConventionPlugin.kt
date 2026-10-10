import com.mapineda48.phonotrainer.buildlogic.libs
import com.mapineda48.phonotrainer.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * `phonotrainer.android.feature` (architecture 2.3): a feature module is a Compose library with Hilt, compiled against
 * the modules a feature may use (dependency rule 1 of architecture 2.2) and tested with the screen-test stack of
 * architecture 21.1 on Robolectric.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
  private companion object {
    const val CONTENT = ":core:content"
  }

  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("phonotrainer.android.library")
      pluginManager.apply("phonotrainer.android.compose")
      pluginManager.apply("phonotrainer.android.hilt")

      dependencies {
        "implementation"(project(":core:domain"))
        "implementation"(project(":core:navigation"))
        "implementation"(project(":core:designsystem"))
        "implementation"(project(":core:ui"))
        // :core:content is WP-04's module: its build file arrives after the contract freeze (work-plan WP-04), and
        // settings include a declared module only once that file exists. Features get it as soon as it does.
        if (rootProject.findProject(CONTENT) != null) "implementation"(project(CONTENT))

        "implementation"(libs.library("androidx-compose-foundation"))
        "implementation"(libs.library("androidx-compose-material3"))
        "implementation"(libs.library("androidx-compose-ui"))
        "implementation"(libs.library("androidx-hilt-lifecycle-viewmodel-compose"))
        "implementation"(libs.library("androidx-lifecycle-runtime-compose"))
        "implementation"(libs.library("androidx-lifecycle-viewmodel-compose"))

        "testImplementation"(project(":core:testing"))
        "testImplementation"(libs.library("androidx-compose-ui-test-junit4"))
        "testImplementation"(libs.library("kotlinx-coroutines-test"))
        "testImplementation"(libs.library("turbine"))
        "debugImplementation"(libs.library("androidx-compose-ui-test-manifest"))
      }
    }
  }
}
