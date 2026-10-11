// Bundled didactic content and the reference snapshot (architecture 15; work-plan WP-04): DidacticContent and
// ReferenceRepository over the two assets that tools/didactic/extract.mjs and tools/reference/export.py write, and
// verifyDidacticContent, the drift check of architecture 15.2, which `check` runs.
import javax.inject.Inject
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

plugins {
  id("phonotrainer.android.library")
  id("phonotrainer.android.hilt")
  id("phonotrainer.kotlin.serialization")
}

dependencies {
  api(project(":core:domain"))
  implementation(project(":core:common"))
  implementation(libs.kotlinx.coroutines.android)

  testImplementation(project(":core:testing-jvm"))
  testImplementation(libs.kotlinx.coroutines.test)
  // ContentBindingsTest resolves this module's bindings in a Hilt graph on Robolectric.
  testImplementation(libs.hilt.android.testing)
  kspTest(libs.hilt.compiler)
}

/**
 * Runs `node tools/didactic/extract.mjs --check`, which regenerates didactic.json in memory from `web/src` and fails on
 * any byte difference with the bundled asset, naming the changed keys (architecture 15.2). Its inputs are the web
 * files the extractor reads, the extractor itself and the asset, so it is up to date until one of them changes.
 */
@DisableCachingByDefault(because = "It runs Node over files outside this module; its only output is a marker file")
abstract class VerifyDidacticContent @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sources: ConfigurableFileCollection

  @get:Internal
  abstract val extractor: RegularFileProperty

  /** The repository root, so the extractor names files as its README does (`android/core/content/…`). */
  @get:Internal
  abstract val repositoryRoot: DirectoryProperty

  /** The `PATH` to find `node` on; internal, so moving Node never reruns an up-to-date check. */
  @get:Internal
  abstract val searchPath: Property<String>

  @get:OutputFile
  abstract val marker: RegularFileProperty

  @TaskAction
  fun verify() {
    val node = searchPath.get().split(File.pathSeparator).asSequence()
      .filter { it.isNotEmpty() }
      .flatMap { directory -> NODE_NAMES.asSequence().map { File(directory, it) } }
      .firstOrNull { it.isFile && it.canExecute() }
      ?: throw GradleException(NODE_REQUIRED)
    val result = execOperations.exec {
      commandLine(node.absolutePath, extractor.get().asFile.absolutePath, "--check")
      workingDir(repositoryRoot.get().asFile)
      isIgnoreExitValue = true
    }
    when (result.exitValue) {
      0 -> marker.get().asFile.writeText("didactic.json matches web/src\n")
      STALE -> throw GradleException(
        "didactic.json is out of date with web/src (the changed keys are listed above). From the repository root run " +
          "`node android/tools/didactic/extract.mjs`, review the asset's diff and commit it with the web change.",
      )
      else -> throw GradleException("The didactic extractor stopped with exit status ${result.exitValue} (see above).")
    }
  }

  private companion object {
    const val NODE_REQUIRED =
      "Node 22.18 or newer is required to verify the didactic content (the web build already needs it)"
    const val STALE = 1
    val NODE_NAMES = listOf("node", "node.exe")
  }
}

val repository: Directory = rootProject.layout.projectDirectory.dir("..")

val verifyDidacticContent = tasks.register<VerifyDidacticContent>("verifyDidacticContent") {
  group = "verification"
  description = "Checks that the bundled didactic.json matches what tools/didactic/extract.mjs builds from web/src."
  // The inputs of the extractor (tools/didactic/README.md, "Inputs"): any change to one, a comment included, changes
  // its hash in the asset's `sources`, so the check must run again.
  sources.from(
    repository.dir("web/src/didactic/glossary").asFileTree.matching { exclude("**/*.test.ts") },
    repository.file("web/src/features/learn/content.ts"),
    repository.file("web/src/features/learn/ipaLayout.ts"),
    repository.file("web/src/articulation/phones.ts"),
    repository.file("web/src/articulation/pose.ts"),
    repository.file("web/src/paths.ts"),
    rootProject.layout.projectDirectory.dir("tools/didactic").asFileTree.matching { include("*.mjs") },
    layout.projectDirectory.file("src/main/assets/didactic/didactic.json"),
  )
  extractor.set(rootProject.layout.projectDirectory.file("tools/didactic/extract.mjs"))
  repositoryRoot.set(repository)
  searchPath.set(providers.environmentVariable("PATH").orElse(""))
  marker.set(layout.buildDirectory.file("verifyDidacticContent/checked"))
}

tasks.named("check") {
  dependsOn(verifyDidacticContent)
}
