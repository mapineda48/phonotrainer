import com.mapineda48.phonotrainer.buildlogic.libs
import com.mapineda48.phonotrainer.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** `phonotrainer.kotlin.serialization` (architecture 2.3): the kotlinx-serialization compiler plugin and JSON runtime. */
class KotlinSerializationConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

      dependencies {
        "implementation"(libs.library("kotlinx-serialization-json"))
      }
    }
  }
}
