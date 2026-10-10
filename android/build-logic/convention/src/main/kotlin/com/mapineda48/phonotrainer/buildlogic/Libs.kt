package com.mapineda48.phonotrainer.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/** The version catalog `gradle/libs.versions.toml`, the only place where versions live (architecture 2.4). */
val Project.libs: VersionCatalog
  get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** A library of the catalog by its alias; fails the configuration with the alias when it is missing. */
fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> =
  findLibrary(alias).orElseThrow { IllegalStateException("No library '$alias' in gradle/libs.versions.toml") }

