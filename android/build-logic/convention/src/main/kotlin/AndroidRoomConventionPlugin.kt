import androidx.room.gradle.RoomExtension
import com.android.build.api.variant.HasUnitTest
import com.mapineda48.phonotrainer.buildlogic.androidComponents
import com.mapineda48.phonotrainer.buildlogic.libs
import com.mapineda48.phonotrainer.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * `phonotrainer.android.room` (architecture 2.3, 4.4): Room on KSP with its schemas exported to `schemas/` and added to
 * the unit-test assets, so `MigrationTestHelper` runs on Robolectric against every exported version. Applied after
 * `phonotrainer.android.library`.
 */
class AndroidRoomConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      check(pluginManager.hasPlugin("com.android.library")) {
        "$path: apply phonotrainer.android.library before phonotrainer.android.room"
      }
      pluginManager.apply("androidx.room")
      pluginManager.apply("com.google.devtools.ksp")

      val schemas = layout.projectDirectory.dir("schemas")
      extensions.configure<RoomExtension> {
        schemaDirectory(schemas.asFile.path)
      }
      androidComponents.onVariants { variant ->
        (variant as? HasUnitTest)?.unitTest?.sources?.assets?.addStaticSourceDirectory(schemas.asFile.path)
      }

      dependencies {
        "implementation"(libs.library("androidx-room-runtime"))
        "implementation"(libs.library("androidx-room-ktx"))
        "ksp"(libs.library("androidx-room-compiler"))
        "testImplementation"(libs.library("androidx-room-testing"))
      }
    }
  }
}
