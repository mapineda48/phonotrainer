package com.mapineda48.phonotrainer.core.designsystem

import java.io.File

/**
 * The files the conversion tests read, relative to this module's directory (the working directory of its test task;
 * `build.gradle.kts` declares them as inputs): the design track's sources and export, this module's converted
 * resources, the notices, and the android/ tree for the strings of every module.
 */
object DesignSources {
  val module: File = File(".").canonicalFile.also {
    check(File(it, "src/main/res").isDirectory) { "run from core/designsystem (working directory: $it)" }
  }
  val android: File = module.resolve("../..").canonicalFile
  val designSrc: File = android.resolve("design-src")
  val export: File = designSrc.resolve("export")
  val paletteJson: File = designSrc.resolve("palette.json")
  val iconsJson: File = designSrc.resolve("icons.json")
  val main: File = module.resolve("src/main")
  val res: File = main.resolve("res")
  val licences: File = main.resolve("assets/licenses")
  val notices: File = android.resolve("../THIRD-PARTY-NOTICES.md").canonicalFile

  /** Every file under [root], as paths relative to it with forward slashes. */
  fun filesUnder(root: File): Set<String> =
    root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toSortedSet()
}
