package com.mapineda48.phonotrainer.buildlogic.licences

import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * The two-part licence check of architecture 20.6 (X-AC3), as plain functions so that its unit tests need no Gradle:
 * (a) the release runtime libraries of the generated report against `THIRD-PARTY-NOTICES.md` 7.2, in both directions;
 * (b) the bundled fonts and icon set of `:core:designsystem` (`assets/licenses/bundled.json`, `res/font/`) against
 * notices 7.3, in both directions, with licence texts present and every font's SHA-256 equal.
 *
 * Every function returns the problems it found as sentences; an empty list means the check passes.
 */
object LicenceCheck {
  /** Licences a runtime library may carry (architecture 20.6). */
  val LIBRARY_LICENCES: Set<String> = setOf("Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "OFL-1.1")

  /** Licences a bundled font or icon set may carry (architecture 20.6). */
  val BUNDLED_LICENCES: Set<String> = setOf("OFL-1.1", "ISC", "MIT")

  /** The headings of notices section 7 that the check reads (work-plan 3.2 names the section rows). */
  const val LIBRARIES_HEADING = "7.2 Runtime libraries"
  const val BUNDLED_HEADING = "7.3 Fonts and icons"

  /** File extensions of font files under `res/font/` (XML font families there are not font files). */
  private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc", "woff", "woff2")

  // -----------------------------------------------------------------------------------------------------------------
  // Inputs

  /** One library of the AboutLibraries report. */
  data class Library(val coordinates: String, val name: String, val version: String, val licences: Set<String>)

  /** One component of `bundled.json`: a font family (with its files) or the icon set (with its count). */
  data class Bundled(
    val name: String,
    val version: String,
    val kind: String,
    val licence: String,
    val licenceFile: String,
    val source: String,
    val fonts: Map<String, String>,
    val count: Int?,
  )

  /** Reads the AboutLibraries JSON (`libraries[]` with `uniqueId`, `artifactVersion`, `licenses` keys into `licenses{}`). */
  fun parseReport(json: String): List<Library> {
    val root = JsonSlurper().parseText(json) as? Map<*, *> ?: error("The licence report is not a JSON object")
    val licences = root["licenses"] as? Map<*, *> ?: emptyMap<Any, Any>()
    val libraries = root["libraries"] as? List<*> ?: error("The licence report has no 'libraries' array")
    return libraries.map { entry ->
      val library = entry as Map<*, *>
      val keys = (library["licenses"] as? List<*>).orEmpty().map { it.toString() }
      Library(
        coordinates = library["uniqueId"].toString(),
        name = library["name"]?.toString().orEmpty(),
        version = library["artifactVersion"]?.toString().orEmpty(),
        licences = keys.map { key -> ((licences[key] as? Map<*, *>)?.get("spdxId") ?: key).toString() }.toSortedSet(),
      )
    }
  }

  /**
   * Reads `bundled.json` (schema 1): `{"schema": 1, "components": [{"name", "version", "kind": "font"|"icons",
   * "licence" (an SPDX expression), "licenceFile", "source", "files": [{"name", "sha256"}] for a font family,
   * "count" for the icon set}]}`.
   */
  fun parseBundled(json: String): List<Bundled> {
    val root = JsonSlurper().parseText(json) as? Map<*, *> ?: error("bundled.json is not a JSON object")
    require(root["schema"] == 1) { "bundled.json: unknown schema ${root["schema"]} (expected 1)" }
    val components = root["components"] as? List<*> ?: error("bundled.json has no 'components' array")
    return components.map { entry ->
      val component = entry as Map<*, *>
      fun text(key: String): String = component[key]?.toString() ?: error("bundled.json: a component has no '$key'")
      val files = (component["files"] as? List<*>).orEmpty().associate { file ->
        val font = file as Map<*, *>
        font["name"].toString() to font["sha256"].toString().lowercase(Locale.ROOT)
      }
      Bundled(
        name = text("name"),
        version = text("version"),
        kind = text("kind"),
        licence = text("licence"),
        licenceFile = text("licenceFile"),
        source = text("source"),
        fonts = files,
        count = (component["count"] as? Number)?.toInt(),
      )
    }
  }

  /** The lines of the Markdown section whose heading text is [heading], up to the next heading of its level or above. */
  fun section(markdown: String, heading: String): List<String>? {
    val lines = markdown.lines()
    val start = lines.indexOfFirst { headingText(it) == heading }
    if (start < 0) return null
    val level = headingLevel(lines[start])
    val end = (start + 1 until lines.size).firstOrNull { index ->
      val other = headingLevel(lines[index])
      other in 1..level
    } ?: lines.size
    return lines.subList(start + 1, end)
  }

  /**
   * The first Markdown table of [lines], as rows keyed by lower-case header; back-ticks are stripped. Fenced code
   * blocks are skipped, so an example table inside one is never read.
   */
  fun table(lines: List<String>): List<Map<String, String>> {
    var fenced = false
    val tableLines = mutableListOf<String>()
    for (line in lines) {
      val trimmed = line.trim()
      if (trimmed.startsWith("```")) fenced = !fenced
      if (fenced) continue
      if (trimmed.startsWith("|")) {
        tableLines += trimmed
      } else if (tableLines.isNotEmpty()) {
        break
      }
    }
    if (tableLines.size < 2) return emptyList()
    val header = cells(tableLines[0]).map { it.lowercase(Locale.ROOT) }
    return tableLines.drop(2).map { row -> header.zip(cells(row)).toMap() }
  }

  /** The SPDX identifiers of an expression (`ISC AND MIT`, `(Apache-2.0 OR MIT)`, or a comma-separated list). */
  fun spdxIds(expression: String): Set<String> =
    expression.split(' ', ',', '(', ')').map(String::trim)
      .filter { it.isNotEmpty() && it.uppercase(Locale.ROOT) !in setOf("AND", "OR", "WITH") }
      .toSortedSet()

  fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

  fun isFontFile(file: File): Boolean = file.extension.lowercase(Locale.ROOT) in FONT_EXTENSIONS

  // -----------------------------------------------------------------------------------------------------------------
  // Part (a): runtime libraries against notices 7.2

  /** 7.2 rows need these columns (any order, other columns allowed). */
  private const val COORDINATES = "coordinates"
  private const val VERSION = "version"
  private const val LICENCE = "licence"

  fun checkLibraries(report: List<Library>, notices: String): List<String> {
    val problems = mutableListOf<String>()
    val rows = section(notices, LIBRARIES_HEADING)?.let(::table)
      ?: return listOf("THIRD-PARTY-NOTICES.md has no section \"$LIBRARIES_HEADING\"")
    val missingColumns = listOf(COORDINATES, VERSION, LICENCE).filter { column -> rows.any { column !in it } }
    if (missingColumns.isNotEmpty()) {
      return listOf("Notices $LIBRARIES_HEADING: the table needs the columns ${missingColumns.joinToString()}")
    }
    val listed = rows.groupBy { it.getValue(COORDINATES) }
    listed.filterValues { it.size > 1 }.keys.forEach { problems += "Notices 7.2 lists `$it` more than once" }

    val byCoordinates = report.associateBy { it.coordinates }
    for (library in report) {
      if (library.licences.isEmpty()) problems += "The report gives `${library.coordinates}` no licence"
      library.licences.filter { it !in LIBRARY_LICENCES }.forEach {
        problems += "`${library.coordinates}` has the licence $it, which is not allowlisted (${LIBRARY_LICENCES.joinToString()})"
      }
      val row = listed[library.coordinates]?.first()
      if (row == null) {
        problems += "Notices 7.2 is missing `${library.coordinates}` ${library.version}; add: ${rowFor(library)}"
        continue
      }
      if (row.getValue(VERSION) != library.version) {
        problems += "Notices 7.2 gives `${library.coordinates}` version ${row.getValue(VERSION)}, the report ${library.version}"
      }
      val rowLicences = spdxIds(row.getValue(LICENCE))
      if (rowLicences != library.licences) {
        problems += "Notices 7.2 gives `${library.coordinates}` the licence ${row.getValue(LICENCE)}, " +
          "the report ${library.licences.joinToString(" AND ")}"
      }
    }
    for ((coordinates, entries) in listed) {
      if (coordinates !in byCoordinates) {
        problems += "Notices 7.2 lists `$coordinates`, which is not on the release runtime classpath; remove its row"
      }
      entries.flatMap { spdxIds(it.getValue(LICENCE)) }.filter { it !in LIBRARY_LICENCES }.toSet().forEach {
        problems += "Notices 7.2 gives `$coordinates` the licence $it, which is not allowlisted"
      }
    }
    return problems
  }

  /** The 7.2 row for [library], in the column order of the notices table. */
  fun rowFor(library: Library): String =
    "| ${library.name} | `${library.coordinates}` | ${library.version} | ${library.licences.joinToString(" AND ")} |"

  // -----------------------------------------------------------------------------------------------------------------
  // Part (b): bundled fonts and icons against notices 7.3

  private const val COMPONENT = "component"

  /**
   * [bundled] is null when `bundled.json` does not exist yet (before WP-03 merges): then 7.3 must have no rows and no
   * font file may ship. [licenceFiles] are the file names present in `assets/licenses/`; [fontFiles] the files under
   * `res/font/` (non-font files are ignored).
   */
  fun checkBundled(
    bundled: List<Bundled>?,
    notices: String,
    licenceFiles: Set<String>,
    fontFiles: List<File>,
  ): List<String> {
    val problems = mutableListOf<String>()
    val rows = section(notices, BUNDLED_HEADING)?.let(::table)
      ?: return listOf("THIRD-PARTY-NOTICES.md has no section \"$BUNDLED_HEADING\"")
    val fonts = fontFiles.filter(::isFontFile).associateBy { it.name }
    if (bundled == null) {
      rows.forEach { problems += "Notices 7.3 lists `${it[COMPONENT] ?: it.values.firstOrNull()}`, but bundled.json does not exist" }
      fonts.keys.sorted().forEach { problems += "Font file $it ships, but bundled.json does not exist" }
      return problems
    }
    if (rows.isNotEmpty()) {
      val missingColumns = listOf(COMPONENT, VERSION, LICENCE).filter { column -> rows.any { column !in it } }
      if (missingColumns.isNotEmpty()) {
        return listOf("Notices $BUNDLED_HEADING: the table needs the columns ${missingColumns.joinToString()}")
      }
    }
    val listed = rows.associateBy { it.getValue(COMPONENT) }
    val byName = bundled.associateBy { it.name }
    if (byName.size != bundled.size) problems += "bundled.json names a component more than once"

    for (component in bundled) {
      if (component.kind !in setOf("font", "icons")) {
        problems += "bundled.json: `${component.name}` has the kind ${component.kind} (font or icons expected)"
      }
      val ids = spdxIds(component.licence)
      if (ids.isEmpty() || ids.any { it !in BUNDLED_LICENCES }) {
        problems += "bundled.json: `${component.name}` has the licence ${component.licence} " +
          "(allowed: ${BUNDLED_LICENCES.joinToString()})"
      }
      if (component.licenceFile !in licenceFiles) {
        problems += "The licence text ${component.licenceFile} of `${component.name}` is missing from assets/licenses/"
      }
      if (component.kind == "font" && component.fonts.isEmpty()) {
        problems += "bundled.json: the font `${component.name}` lists no file"
      }
      if (component.kind == "icons" && (component.count ?: 0) <= 0) {
        problems += "bundled.json: the icon set `${component.name}` has no count"
      }
      val row = listed[component.name]
      if (row == null) {
        problems += "Notices 7.3 is missing `${component.name}` ${component.version} (${component.licence})"
        continue
      }
      if (row.getValue(VERSION) != component.version) {
        problems += "Notices 7.3 gives `${component.name}` version ${row.getValue(VERSION)}, bundled.json ${component.version}"
      }
      if (spdxIds(row.getValue(LICENCE)) != ids) {
        problems += "Notices 7.3 gives `${component.name}` the licence ${row.getValue(LICENCE)}, bundled.json ${component.licence}"
      }
    }
    listed.keys.filter { it !in byName }.forEach { problems += "Notices 7.3 lists `$it`, which bundled.json does not name" }

    val declared = bundled.flatMap { component -> component.fonts.map { (file, sha) -> Triple(component.name, file, sha) } }
    declared.groupBy { it.second }.filterValues { it.size > 1 }.keys.forEach {
      problems += "bundled.json lists the font file $it more than once"
    }
    for ((component, file, sha) in declared) {
      val actual = fonts[file]
      when {
        actual == null -> problems += "bundled.json lists the font file $file of `$component`, which is not in res/font/"
        sha256(actual) != sha -> problems += "The SHA-256 of $file differs from bundled.json (`$component`)"
      }
    }
    val declaredFiles = declared.map { it.second }.toSet()
    fonts.keys.filter { it !in declaredFiles }.sorted().forEach {
      problems += "Font file $it ships in res/font/ but bundled.json does not list it"
    }
    return problems
  }

  // -----------------------------------------------------------------------------------------------------------------

  private fun headingLevel(line: String): Int {
    val hashes = line.takeWhile { it == '#' }.length
    return if (hashes in 1..6 && line.length > hashes && line[hashes] == ' ') hashes else 0
  }

  private fun headingText(line: String): String? =
    if (headingLevel(line) == 0) null else line.trimStart('#').trim()

  private fun cells(row: String): List<String> =
    row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim().replace("`", "") }
}
