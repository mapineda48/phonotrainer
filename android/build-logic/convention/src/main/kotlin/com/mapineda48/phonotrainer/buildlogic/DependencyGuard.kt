package com.mapineda48.phonotrainer.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register

/**
 * The dependency guard of architecture 2.2 (rules 1 to 5), registered by every convention plugin as
 * `checkModuleDependencies`, run by `check` and before the module compiles, so a forbidden dependency fails the build:
 * 1. a feature depends only on `:core:domain`, `:core:common`, `:core:navigation`, `:core:designsystem`, `:core:ui`,
 *    `:core:content` and `:core:player` (its tests also on the two testing modules): never on another feature or a
 *    core implementation module;
 * 2. no core module depends on a feature or on `:app`; only `:app` depends on `:core:network` (everything else uses
 *    the computer through `:core:network-api`); `:core:sync` reaches connectivity, corpus, content, study and requests
 *    only through `:core:domain` interfaces and SPIs (work-plan 4.2). A cycle between modules cannot build at all:
 *    Gradle refuses the circular task graph before any task runs, so the modules always form a DAG;
 * 3. `:core:testing` and `:core:testing-jvm` are only test dependencies, and depend only on `:core:domain`,
 *    `:core:network-api`, `:core:common`, `:core:designsystem` (and each other);
 * 4. `:core:domain`, `:core:common`, `:core:analysis`, `:core:network-api` and `:core:testing-jvm` stay JVM modules;
 * 5. a JVM module gains no Android dependency: no Android module, no AAR and no Android-only group on its classpaths.
 */
internal object ModuleRules {
  val JVM_MODULES = setOf(":core:domain", ":core:common", ":core:analysis", ":core:network-api", ":core:testing-jvm")
  val TESTING_MODULES = setOf(":core:testing", ":core:testing-jvm")
  val FEATURE_MAY_USE = setOf(
    ":core:domain", ":core:common", ":core:navigation", ":core:designsystem", ":core:ui", ":core:content",
    ":core:player",
  )
  val TESTING_MAY_USE = setOf(":core:domain", ":core:network-api", ":core:common", ":core:designsystem") +
    TESTING_MODULES
  val SYNC_THROUGH_SPI_ONLY = setOf(
    ":core:connectivity", ":core:corpus", ":core:content", ":core:study", ":core:requests",
  )

  /** `com.android.test` modules: every configuration of theirs is test code. */
  val BLACK_BOX = setOf(":qasmoke", ":baselineprofile")
  const val NETWORK = ":core:network"
  const val APP = ":app"
  private val ANDROID_GROUPS = listOf("com.android.", "com.google.android.", "org.robolectric", "androidx.test")

  /** `testImplementation`, `androidTestDebugImplementation`, `kspTest`, `testFixturesApi`… */
  fun isTestConfiguration(name: String): Boolean {
    val bare = name.removePrefix("ksp").replaceFirstChar { it.lowercase() }
    return bare.startsWith("test") || bare.startsWith("androidTest") || "UnitTest" in name || "AndroidTest" in name
  }

  /** Every broken rule, one line each; [edges] are `configuration -> :path`. */
  fun violations(module: String, jvm: Boolean, edges: List<String>, androidArtifacts: List<String>): List<String> {
    val found = mutableListOf<String>()
    if (module in JVM_MODULES && !jvm) found += "rule 4: $module must stay a JVM module (phonotrainer.jvm.library)"
    for (edge in edges) {
      val (configuration, target) = edge.split(" -> ", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
      if (target == module || target.isEmpty()) continue
      val test = module in BLACK_BOX || isTestConfiguration(configuration)
      val where = "$module ($configuration) -> $target"
      if (target in TESTING_MODULES && !test && module !in TESTING_MODULES) {
        found += "rule 3: a testing module is only a test dependency: $where"
      }
      if (module in TESTING_MODULES && target !in TESTING_MAY_USE) {
        found += "rule 3: a testing module never depends on an implementation module: $where"
      }
      if (module.startsWith(":feature:")) {
        val allowed = if (test) FEATURE_MAY_USE + TESTING_MODULES else FEATURE_MAY_USE
        if (target !in allowed) found += "rule 1: a feature depends only on ${FEATURE_MAY_USE.joinToString()}: $where"
      }
      if (module.startsWith(":core:")) {
        if (target.startsWith(":feature:") || target == APP) found += "rule 2: a core module never depends on $target: $where"
        if (target == NETWORK) found += "rule 2: only :app depends on $NETWORK; use :core:network-api: $where"
        if (module == ":core:sync" && target in SYNC_THROUGH_SPI_ONLY) {
          found += "rule 2: :core:sync reaches $target only through :core:domain interfaces and SPIs: $where"
        }
      }
      if (jvm && target !in JVM_MODULES) found += "rule 5: a JVM module never depends on an Android module: $where"
    }
    androidArtifacts.forEach { found += "rule 5: a JVM module never depends on Android: $module -> $it" }
    return found.distinct()
  }

  /** The artifact's name when it is Android (an AAR, an Android-only group or an Android module), else null. */
  fun androidArtifact(artifact: ResolvedArtifactResult): String? {
    val id = artifact.id.componentIdentifier
    val aar = artifact.file.extension == "aar"
    return when (id) {
      is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}".takeIf {
        aar || ANDROID_GROUPS.any { prefix -> id.group == prefix.trimEnd('.') || id.group.startsWith(prefix) }
      }
      is ProjectComponentIdentifier -> id.projectPath.takeIf { aar || it !in JVM_MODULES }
      else -> artifact.file.name.takeIf { aar }
    }
  }
}

/** Checks one module against [ModuleRules]; its report lists the dependencies it checked. */
abstract class CheckModuleDependencies : DefaultTask() {
  @get:Input
  abstract val modulePath: Property<String>

  @get:Input
  abstract val jvmModule: Property<Boolean>

  @get:Input
  abstract val projectEdges: ListProperty<String>

  @get:Input
  abstract val androidArtifacts: ListProperty<String>

  @get:OutputFile
  abstract val report: RegularFileProperty

  @TaskAction
  fun check() {
    val found = ModuleRules.violations(
      modulePath.get(), jvmModule.get(), projectEdges.get(), androidArtifacts.get(),
    )
    report.get().asFile.apply {
      parentFile.mkdirs()
      writeText((projectEdges.get() + found.map { "VIOLATION $it" }).joinToString("\n", postfix = "\n"))
    }
    if (found.isNotEmpty()) {
      throw GradleException(
        "Dependency guard (architecture 2.2) failed for ${modulePath.get()}:\n" + found.joinToString("\n") { "  $it" },
      )
    }
  }
}

/** Registers `checkModuleDependencies` and runs it from `check` and before [compileTask]. */
internal fun Project.configureDependencyGuard(jvm: Boolean, compileTask: String) {
  val module = path
  val guard = tasks.register<CheckModuleDependencies>("checkModuleDependencies") {
    group = "verification"
    description = "Checks this module's dependencies against architecture 2.2 rules 1 to 5."
    modulePath.set(module)
    jvmModule.set(jvm)
    projectEdges.set(provider { declaredProjectEdges() })
    androidArtifacts.set(if (jvm) androidArtifactsOnClasspaths() else provider { emptyList() })
    report.set(layout.buildDirectory.file("reports/dependency-guard/dependencies.txt"))
  }
  tasks.matching { it.name == "check" || it.name == compileTask }.configureEach { dependsOn(guard) }
}

/** Every project dependency declared in any configuration of this module, as `configuration -> :path`. */
private fun Project.declaredProjectEdges(): List<String> = configurations.flatMap { configuration ->
  configuration.dependencies.withType(ProjectDependency::class.java).map { "${configuration.name} -> ${it.path}" }
}.distinct().sorted()

/**
 * The Android artifacts on a JVM module's main and test classpaths (rule 5). Lenient: a dependency Gradle cannot even
 * resolve for a JVM module (an Android module's variants) is reported by its declared edge instead of a resolution
 * error.
 */
private fun Project.androidArtifactsOnClasspaths(): Provider<List<String>> =
  listOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath")
    .map { name ->
      configurations.named(name).flatMap { configuration ->
        configuration.incoming.artifactView { lenient(true) }.artifacts.resolvedArtifacts
      }
    }
    .reduce { all, next -> all.zip(next) { a, b -> a + b } }
    .map { artifacts -> artifacts.mapNotNull(ModuleRules::androidArtifact).distinct().sorted() }
