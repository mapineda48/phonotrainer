import com.mapineda48.phonotrainer.buildlogic.libs
import com.mapineda48.phonotrainer.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * `phonotrainer.android.hilt` (architecture 2.3): Hilt with its compiler on KSP. Each module keeps its own Hilt module
 * (architecture 2.7); applied after `phonotrainer.android.application` or `phonotrainer.android.library`.
 */
class AndroidHiltConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      check(pluginManager.hasPlugin("com.android.application") || pluginManager.hasPlugin("com.android.library")) {
        "$path: apply phonotrainer.android.application or phonotrainer.android.library before phonotrainer.android.hilt"
      }
      pluginManager.apply("com.google.devtools.ksp")
      pluginManager.apply("com.google.dagger.hilt.android")

      dependencies {
        "implementation"(libs.library("hilt-android"))
        "ksp"(libs.library("hilt-compiler"))
      }
    }
  }
}
