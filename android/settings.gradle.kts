pluginManagement {
  includeBuild("build-logic")
  repositories {
    google {
      content {
        includeGroupByRegex("androidx.*")
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google {
      content {
        includeGroupByRegex("androidx.*")
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
      }
    }
    mavenCentral()
  }
}

plugins {
  // Provisions the JDK 17 toolchain when the computer has none (settings plugins cannot read the version catalog).
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "PhonoTrainer"

// Every module of v1, v1-complete ones included (architecture 2.1 and 2.2), declared once so that no work package
// edits this file to add one. A module joins the build when its build file exists: Gradle 9 refuses a project
// without a directory, and the day-0 work packages create their own modules (WP-04 :core:content, WP-12
// :core:analysis). From the contract freeze (B1) on every module exists, and :app depends on each of them, so a
// module that disappears fails the build instead of being skipped.
val modules = listOf(
  ":app",
  ":core:domain",
  ":core:common",
  ":core:analysis",
  ":core:network-api",
  ":core:navigation",
  ":core:designsystem",
  ":core:ui",
  ":core:content",
  ":core:database",
  ":core:datastore",
  ":core:network",
  ":core:notifications",
  ":core:connectivity",
  ":core:corpus",
  ":core:sync",
  ":core:requests",
  ":core:player",
  ":core:study",
  ":core:testing-jvm",
  ":core:testing",
  ":core:articulator",
  ":core:recorder",
  ":feature:onboarding",
  ":feature:connect",
  ":feature:home",
  ":feature:library",
  ":feature:watch",
  ":feature:lesson",
  ":feature:learn",
  ":feature:practice",
  ":feature:requests",
  ":feature:share",
  ":feature:settings",
  ":feature:insights",
  ":qasmoke",
  ":baselineprofile",
)

val (present, pending) = modules.partition { path ->
  settingsDir.resolve(path.removePrefix(":").replace(':', '/')).resolve("build.gradle.kts").isFile
}
present.forEach { include(it) }
if (pending.isNotEmpty()) {
  logger.lifecycle("Declared modules not created yet: ${pending.joinToString()}")
}
