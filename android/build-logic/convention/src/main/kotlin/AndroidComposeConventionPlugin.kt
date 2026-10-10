import com.android.build.api.dsl.CommonExtension
import com.mapineda48.phonotrainer.buildlogic.libs
import com.mapineda48.phonotrainer.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

/**
 * `phonotrainer.android.compose` (architecture 2.3): the Compose compiler, the Compose BOM for every source set,
 * previews, and the tooling in debug builds only. Applied after `phonotrainer.android.application` or
 * `phonotrainer.android.library`.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      check(pluginManager.hasPlugin("com.android.application") || pluginManager.hasPlugin("com.android.library")) {
        "$path: apply phonotrainer.android.application or phonotrainer.android.library before phonotrainer.android.compose"
      }
      pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
      extensions.getByType<CommonExtension>().buildFeatures.compose = true

      dependencies {
        val bom = platform(libs.library("androidx-compose-bom"))
        "implementation"(bom)
        "testImplementation"(bom)
        "androidTestImplementation"(bom)
        "implementation"(libs.library("androidx-compose-ui-tooling-preview"))
        "debugImplementation"(libs.library("androidx-compose-ui-tooling"))
      }
    }
  }
}
