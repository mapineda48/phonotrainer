package com.mapineda48.phonotrainer.buildlogic

import com.mapineda48.phonotrainer.buildlogic.licences.LicenceCheck
import com.mapineda48.phonotrainer.buildlogic.licences.VerifyLicences
import com.mikepenz.aboutlibraries.plugin.AboutLibrariesExtension
import com.mikepenz.aboutlibraries.plugin.DuplicateMode
import com.mikepenz.aboutlibraries.plugin.StrictMode
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

/**
 * The licence report and the licence check of architecture 20.6 (WP-19), for the application module.
 *
 * The AboutLibraries plugin writes, per variant, the dependency and licence metadata of the runtime classpath to
 * `res/raw/aboutlibraries.json` (the About and licences screens read it offline, F-24). It runs offline (no network,
 * no GitHub lookups) and in strict mode: a licence outside [LicenceCheck.LIBRARY_LICENCES], or a library without one,
 * fails the build. `verifyLicences` then compares the release report and `:core:designsystem`'s bundled fonts and
 * icons with `THIRD-PARTY-NOTICES.md` section 7, and `check` depends on it.
 */
internal fun Project.configureLicenceReport() {
  pluginManager.apply("com.mikepenz.aboutlibraries.plugin.android")

  extensions.configure<AboutLibrariesExtension> {
    offlineMode.set(true)
    collect {
      fetchRemoteLicense.set(false)
      fetchRemoteFunding.set(false)
      includePlatform.set(false)
    }
    license {
      strictMode.set(StrictMode.FAIL)
      mapLicensesToSpdx.set(true)
      allowedLicenses.set(LicenceCheck.LIBRARY_LICENCES)
    }
    library {
      requireLicense.set(true)
      duplicationMode.set(DuplicateMode.KEEP)
    }
  }

  val designsystem = rootProject.layout.projectDirectory.dir("core/designsystem/src/main")
  val verify = tasks.register<VerifyLicences>("verifyLicences") {
    group = "verification"
    description = "Compares the release licence report and the bundled fonts and icons with THIRD-PARTY-NOTICES.md 7.2 and 7.3."
    dependsOn(REPORT_TASK)
    report.set(layout.buildDirectory.file(REPORT_FILE))
    notices.set(rootProject.layout.projectDirectory.file("../THIRD-PARTY-NOTICES.md"))
    licenceFiles.from(designsystem.dir("assets/licenses").asFileTree)
    fontFiles.from(designsystem.dir("res/font").asFileTree)
    result.set(layout.buildDirectory.file("reports/licences/verifyLicences.txt"))
  }
  tasks.named("check") {
    dependsOn(verify)
    // The licence check's own tests (every planted fault of work-plan WP-19's proof) run with the app's check.
    dependsOn(gradle.includedBuild("build-logic").task(":convention:test"))
  }
}

/** The task and file of the release report (AboutLibraries' Android integration, one per variant). */
private const val REPORT_TASK = "prepareLibraryDefinitionsRelease"
private const val REPORT_FILE = "generated/aboutLibraries/release/res/raw/aboutlibraries.json"
