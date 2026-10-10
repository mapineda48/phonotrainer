package com.mapineda48.phonotrainer.core.analysis

import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.PracticeAdvice
import com.mapineda48.phonotrainer.core.domain.model.Reference
import com.mapineda48.phonotrainer.core.domain.model.Register
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale

/**
 * The reference snapshot `:core:content` bundles (`/api/reference`, WP-04), read straight from its asset file (the
 * module's test task declares it as an input): the advice, families and `ipa_tokens` the rules are tested with are
 * the computer's real ones. Only the fields `:core:analysis` reads are mapped.
 */
object ReferenceSnapshot {
  /** Relative to this module's directory, the test working directory. */
  private val FILE = File("../content/src/main/assets/reference/reference.json")

  val json: JsonObject by lazy { Json.parseToJsonElement(FILE.readText()).jsonObject }

  val reference: Reference by lazy { toReference(json) }

  val ipaTokens: List<String> get() = reference.ipaTokens

  private fun toReference(root: JsonObject): Reference {
    fun advice(node: JsonObject) = node.mapValues { (_, value) ->
      val entry = value.jsonObject
      PracticeAdvice(
        advice = Advice.valueOf(entry.getValue("practice").jsonPrimitive.content.uppercase(Locale.ROOT)),
        register = Register.valueOf(entry.getValue("register").jsonPrimitive.content.uppercase(Locale.ROOT)),
        why = entry.getValue("why").jsonPrimitive.content,
      )
    }
    return Reference(
      apiVersion = root.getValue("api_version").jsonPrimitive.content.toInt(),
      families = emptyList(),
      familyOf = root.getValue("family_of").jsonObject.entries.associate { (label, family) ->
        ChangeLabel(label) to Family.valueOf(family.jsonPrimitive.content.uppercase(Locale.ROOT))
      },
      labels = root.getValue("labels").jsonObject.entries.associate { (label, name) ->
        ChangeLabel(label) to name.jsonPrimitive.content
      },
      descriptions = emptyMap(),
      practice = advice(root.getValue("practice").jsonObject).mapKeys { ChangeLabel(it.key) },
      lexicalPractice = advice(root.getValue("lexical_practice").jsonObject),
      linkTypes = (root.getValue("link_types") as JsonArray).map { it.jsonPrimitive.content },
      ipaTokens = (root.getValue("ipa_tokens") as JsonArray).map { it.jsonPrimitive.content },
      metricsReference = emptyMap(),
      metricLabels = emptyMap(),
    )
  }
}
