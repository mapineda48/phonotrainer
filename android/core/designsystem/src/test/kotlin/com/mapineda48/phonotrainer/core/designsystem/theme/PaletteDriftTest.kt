package com.mapineda48.phonotrainer.core.designsystem.theme

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.designsystem.DesignSources
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Test

/**
 * `Palette.kt` is generated from `design-src/palette.json` (design 12.1): every colour and number of the JSON is in
 * the generated index with the same value, and the index holds nothing else. A palette change that skipped
 * `convert.py` fails here.
 */
class PaletteDriftTest {
  private val skipped = setOf("gates", "version", "familyOrder")

  private fun flatten(path: String, element: JsonElement, colors: MutableMap<String, String>, numbers: MutableMap<String, Double>) {
    when (element) {
      is JsonObject -> {
        if ("hex" in element && "alpha" in element) {
          colors[path] = element.getValue("hex").let { (it as JsonPrimitive).content.lowercase() }
          numbers["$path.alpha"] = (element.getValue("alpha") as JsonPrimitive).doubleOrNull!!
          return
        }
        if (path.startsWith("launcher.shareSheets.")) {
          colors["$path.color"] = (element.getValue("color") as JsonPrimitive).content.lowercase()
          numbers["$path.min"] = (element.getValue("min") as JsonPrimitive).doubleOrNull!!
          return
        }
        for ((key, value) in element) {
          if (key.startsWith("_") || (path.isEmpty() && key in skipped)) continue
          flatten(if (path.isEmpty()) key else "$path.$key", value, colors, numbers)
        }
      }
      is JsonArray -> element.forEachIndexed { i, value -> flatten("$path[$i]", value, colors, numbers) }
      is JsonPrimitive -> if (element.isString) {
        colors[path] = element.content.lowercase()
      } else {
        numbers[path] = element.doubleOrNull!!
      }
    }
  }

  @Test
  fun everyColourAndNumberOfPaletteJsonIsGeneratedWithTheSameValue() {
    val json = Json.parseToJsonElement(DesignSources.paletteJson.readText())
    val colors = linkedMapOf<String, String>()
    val numbers = linkedMapOf<String, Double>()
    flatten("", json, colors, numbers)

    val generatedColors = PaletteIndex.colors.mapValues { Rgb.of(it.value).hex }
    assertThat(generatedColors).containsExactlyEntriesIn(colors)
    assertThat(PaletteIndex.numbers.keys).containsExactlyElementsIn(numbers.keys)
    for ((key, value) in numbers) {
      assertThat(PaletteIndex.numbers.getValue(key).toDouble()).isWithin(1e-6).of(value)
    }
    // Sanity: the JSON is not empty and the index covers all its groups.
    assertThat(colors.keys.map { it.substringBefore('.') }.toSet())
      .containsExactly("neutrals", "brand", "families", "tints", "illustration", "tract", "launcher")
  }
}
