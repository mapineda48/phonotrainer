package com.mapineda48.phonotrainer.buildlogic.licences

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * `verifyLicences` (architecture 20.6, X-AC3; part of `check`): the release runtime libraries of the AboutLibraries
 * report against `THIRD-PARTY-NOTICES.md` 7.2, and the fonts and icon set of `:core:designsystem` (`bundled.json`,
 * `assets/licenses/`, `res/font/`) against 7.3, both ways. It fails with every problem it finds, each saying what to
 * change; a library missing from 7.2 comes with the row to add.
 */
@CacheableTask
abstract class VerifyLicences : DefaultTask() {
  /** The report of the release variant (`prepareLibraryDefinitionsRelease`). */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val report: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val notices: RegularFileProperty

  /** Every file of `:core:designsystem` `assets/licenses/` (licence texts and `bundled.json`); empty before WP-03. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NAME_ONLY)
  abstract val licenceFiles: ConfigurableFileCollection

  /** Every file of `:core:designsystem` `res/font/`. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NAME_ONLY)
  abstract val fontFiles: ConfigurableFileCollection

  /** The list of what was checked, written when the check passes. */
  @get:OutputFile
  abstract val result: RegularFileProperty

  @TaskAction
  fun verify() {
    val reportFile = report.get().asFile
    if (!reportFile.isFile) throw GradleException("The licence report $reportFile was not generated")
    val noticesText = notices.get().asFile.readText()
    val libraries = LicenceCheck.parseReport(reportFile.readText())
    val licenceFiles = licenceFiles.files.filter { it.isFile }
    val bundled = licenceFiles.firstOrNull { it.name == BUNDLED_INDEX }?.let { LicenceCheck.parseBundled(it.readText()) }

    val problems = LicenceCheck.checkLibraries(libraries, noticesText) +
      LicenceCheck.checkBundled(bundled, noticesText, licenceFiles.map { it.name }.toSet(), fontFiles.files.toList())
    if (problems.isNotEmpty()) {
      throw GradleException(
        "The licence check found ${problems.size} problem(s) (architecture 20.6; THIRD-PARTY-NOTICES.md section 7):\n" +
          problems.joinToString("\n") { "  - $it" },
      )
    }
    result.get().asFile.writeText(
      buildString {
        append("Licence check passed: ${libraries.size} runtime libraries (notices 7.2), ")
        appendLine("${bundled?.size ?: 0} bundled components (notices 7.3).")
        libraries.sortedBy { it.coordinates }.forEach { appendLine(LicenceCheck.rowFor(it)) }
      },
    )
  }

  companion object {
    const val BUNDLED_INDEX = "bundled.json"
  }
}
