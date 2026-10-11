import com.android.build.api.dsl.Lint
import com.mapineda48.phonotrainer.buildlogic.Sdk
import com.mapineda48.phonotrainer.buildlogic.configureDependencyGuard
import com.mapineda48.phonotrainer.buildlogic.configureLintPolicy
import com.mapineda48.phonotrainer.buildlogic.libs
import com.mapineda48.phonotrainer.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmExtension

/**
 * `phonotrainer.jvm.library` (architecture 2.3): a plain Kotlin/JVM module with no Android dependency (`:core:domain`,
 * `:core:common`, `:core:analysis`, `:core:network-api`, `:core:testing-jvm`), on Kotlin 2.4.20 and the Java 17
 * toolchain, tested with JUnit and Truth in milliseconds (architecture 21.1), and linted with the lint policy of
 * architecture 2.3.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("java-library")
      pluginManager.apply("org.jetbrains.kotlin.jvm")
      // Lint analyses JVM modules too: with `checkDependencies` on :app their sources are linted with the app's, under
      // the same policy (otherwise lint treats them as external jars).
      pluginManager.apply("com.android.lint")
      configureDependencyGuard(jvm = true, compileTask = "compileKotlin")

      extensions.configure<KotlinJvmExtension> {
        jvmToolchain(Sdk.JAVA)
      }
      extensions.configure<Lint> {
        configureLintPolicy(this)
      }

      dependencies {
        "testImplementation"(libs.library("junit"))
        "testImplementation"(libs.library("truth"))
      }
    }
  }
}
