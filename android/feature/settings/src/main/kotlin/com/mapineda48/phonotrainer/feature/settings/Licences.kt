package com.mapineda48.phonotrainer.feature.settings

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** What a licence entry is (spec F-24, D-50): the app itself, a bundled font, the vendored icon set or a library. */
enum class LicenceKind { APP, FONT, ICONS, LIBRARY }

/**
 * One licence of an entry: its short identifier ("Apache-2.0"), its name, where it is published, and where its text is
 * read on this phone: [text] when the report carries it, otherwise the asset [asset] (null: no text bundled).
 */
@Immutable
data class LicenceRef(val id: String, val name: String, val url: String?, val asset: String?, val text: String? = null) {
  /** Whether the text is the licence's standard text with a placeholder copyright line (MIT and BSD templates). */
  val template: Boolean get() = text == null && asset?.startsWith(Licences.TEXTS) == true && id in Licences.TEMPLATES
}

/**
 * One row of "Open-source licences" (architecture 20.6): a runtime library of the generated report, a font family or the
 * icon set of `bundled.json`, or the app. [id] is stable (the library's coordinates, `bundled:<name>`, `app`) and names
 * the row's test tag.
 */
@Immutable
data class LicenceEntry(
  val id: String,
  val kind: LicenceKind,
  val name: String,
  val version: String?,
  val licences: List<LicenceRef>,
  /** The copyright line or the authors. */
  val holder: String? = null,
  /** Where it comes from, shown as text. */
  val source: String? = null,
  /** Font files bundled ([LicenceKind.FONT]). */
  val files: Int? = null,
  /** Icons bundled ([LicenceKind.ICONS]). */
  val icons: Int? = null,
) {
  /** The licences in short form, as the row shows them: "Apache-2.0", "ISC AND MIT". */
  val licenceLine: String get() = licences.joinToString(" AND ") { it.id }
}

/** Everything "Open-source licences" lists, in its three groups. */
@Immutable
data class LicenceCatalogue(val app: LicenceEntry, val bundled: List<LicenceEntry>, val libraries: List<LicenceEntry>) {
  val all: List<LicenceEntry> get() = listOf(app) + bundled + libraries
}

/**
 * Reads the two sources of the licences screen (architecture 20.6, decision I-01): the AboutLibraries report of the
 * runtime classpath (`res/raw/aboutlibraries.json`, generated per variant) and `:core:designsystem`'s
 * `assets/licenses/bundled.json` (the fonts and the icon set, which no dependency report sees, D-50).
 *
 * The report runs offline, so it names each licence but carries no text. The texts are this module's
 * `assets/licence-texts/<SPDX id>.txt` (the SPDX licence list's text of every licence the build allows, plus the
 * repository's own `LICENSE` for the app), and a report that does carry a licence's `content` is read as is.
 */
object Licences {
  /** Where this module keeps licence texts by SPDX id. */
  const val TEXTS = "licence-texts/"

  /** Where `:core:designsystem` keeps the fonts' and icons' licence files and their index. */
  const val BUNDLED_DIR = "licenses/"
  const val BUNDLED_INDEX = "licenses/bundled.json"

  /** The app's name and where its source is published (the public repository). */
  const val APP_NAME = "PhonoTrainer"
  const val SOURCE_URL = "https://github.com/mapineda48/phenomena"

  /** The app's own licence (the repository's `LICENSE`). */
  const val APP_LICENCE = "GPL-3.0-or-later"
  const val APP_LICENCE_NAME = "GNU General Public License v3.0 or later"
  const val APP_LICENCE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

  /** Licences whose standard text begins with a placeholder copyright line. */
  val TEMPLATES: Set<String> = setOf("MIT", "BSD-2-Clause", "BSD-3-Clause")

  private val json = Json { ignoreUnknownKeys = true }

  /** The asset holding the text of the SPDX licence [spdx]. */
  fun textAsset(spdx: String): String = "$TEXTS$spdx.txt"

  /** The app's entry: PhonoTrainer [version], GPL-3.0-or-later, from the public repository. */
  fun app(version: String?): LicenceEntry = LicenceEntry(
    id = "app",
    kind = LicenceKind.APP,
    name = APP_NAME,
    version = version,
    licences = listOf(LicenceRef(APP_LICENCE, APP_LICENCE_NAME, APP_LICENCE_URL, textAsset(APP_LICENCE))),
    source = SOURCE_URL,
  )

  /** The fonts and the icon set of `bundled.json` (schema 1), in its order. */
  fun bundled(index: String): List<LicenceEntry> {
    val root = json.parseToJsonElement(index).obj()
    return root.array("components").map { element ->
      val component = element.obj()
      val name = component.string("name") ?: error("bundled.json: a component without a name")
      val kind = when (component.string("kind")) {
        "font" -> LicenceKind.FONT
        "icons" -> LicenceKind.ICONS
        else -> error("bundled.json: `$name` has an unknown kind")
      }
      val licence = component.string("licence") ?: error("bundled.json: `$name` has no licence")
      val file = component.string("licenceFile")
      LicenceEntry(
        id = "bundled:$name",
        kind = kind,
        name = name,
        version = component.string("version"),
        licences = listOf(LicenceRef(licence, licence, null, file?.let { BUNDLED_DIR + it })),
        holder = component.string("copyright"),
        source = component.string("source"),
        files = component["files"]?.let { (it as? JsonArray)?.size },
        icons = (component["count"] as? JsonPrimitive)?.intOrNull,
      )
    }
  }

  /**
   * The runtime libraries of the AboutLibraries report, sorted by name: each with its version, its authors (the
   * organisation, else the developers) and its licences, resolved through the report's `licenses` table.
   */
  fun libraries(report: String): List<LicenceEntry> {
    val root = json.parseToJsonElement(report).obj()
    val table = (root["licenses"] as? JsonObject).orEmpty()
    return root.array("libraries").map { element ->
      val library = element.obj()
      val coordinates = library.string("uniqueId") ?: error("report: a library without a uniqueId")
      val licences = library.array("licenses").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.map { key ->
        val licence = (table[key] as? JsonObject).orEmpty()
        val spdx = licence.string("spdxId") ?: key
        LicenceRef(
          id = spdx,
          name = licence.string("name") ?: spdx,
          url = licence.string("url"),
          asset = textAsset(spdx),
          text = licence.string("content")?.takeIf { it.isNotBlank() },
        )
      }
      val organisation = (library["organization"] as? JsonObject)?.string("name")
      val developers = library.array("developers").mapNotNull { (it as? JsonObject)?.string("name") }.distinct()
      LicenceEntry(
        id = coordinates,
        kind = LicenceKind.LIBRARY,
        name = library.string("name")?.takeIf { it.isNotBlank() } ?: coordinates,
        version = library.string("artifactVersion"),
        licences = licences,
        holder = organisation ?: developers.takeIf { it.isNotEmpty() }?.joinToString(", "),
        source = library.string("website") ?: (library["scm"] as? JsonObject)?.string("url"),
      )
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, LicenceEntry::name).thenBy(LicenceEntry::id))
  }

  /**
   * A licence text as paragraphs for a phone screen: blank lines separate paragraphs, and the hard line breaks of a
   * plain-text licence inside one paragraph become spaces, so the text reflows to the screen's width at any size.
   */
  fun paragraphs(text: String): List<String> = text
    .replace("\r\n", "\n")
    .split(Regex("\n[ \t]*\n"))
    .map { paragraph -> paragraph.lines().joinToString(" ") { it.trim() }.replace(Regex(" {2,}"), " ").trim() }
    .filter { it.isNotEmpty() }

  private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: error("expected a JSON object")

  private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

  private fun JsonObject.string(key: String): String? = when (val value = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> value.contentOrNull
    else -> null
  }

  private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
}
