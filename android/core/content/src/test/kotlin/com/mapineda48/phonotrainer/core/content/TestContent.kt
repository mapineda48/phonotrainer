package com.mapineda48.phonotrainer.core.content

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The module's own `src/main/assets/`, read from the source tree (Gradle runs local tests in the module directory),
 *  so the decoding is tested in milliseconds without Robolectric. [reads] counts every read. */
internal class SourceAssets : BundledAssets {
  var reads = 0
    private set

  override fun read(path: String): String {
    reads++
    return text(path)
  }

  companion object {
    private val ROOT = File("src/main/assets")

    fun text(path: String): String {
      val file = File(ROOT, path)
      check(file.isFile) { "$path is not under ${ROOT.absolutePath}: local tests run in the module directory" }
      return file.readText(Charsets.UTF_8)
    }

    val didactic: String get() = text(ContentAssets.DIDACTIC)
    val reference: String get() = text(ContentAssets.REFERENCE)
  }
}

/** [this] JSON object with [edit] applied to its top-level fields. */
internal fun String.editJson(edit: MutableMap<String, JsonElement>.() -> Unit): String =
  JsonObject(Json.parseToJsonElement(this).jsonObject.toMutableMap().apply(edit)).toString()

/** [this] JSON object with [edit] applied to the fields of the object at [key]. */
internal fun MutableMap<String, JsonElement>.editObject(key: String, edit: MutableMap<String, JsonElement>.() -> Unit) {
  val inner = getValue(key).jsonObject.toMutableMap().apply(edit)
  put(key, JsonObject(inner))
}

/** The bundled reference body with another `api_version` and otherwise the same content. */
internal fun referenceWithVersion(apiVersion: Int): String =
  SourceAssets.reference.editJson { put("api_version", JsonPrimitive(apiVersion)) }
