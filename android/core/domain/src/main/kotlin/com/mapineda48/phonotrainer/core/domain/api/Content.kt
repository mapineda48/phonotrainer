package com.mapineda48.phonotrainer.core.domain.api

import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.GlossaryEntry
import com.mapineda48.phonotrainer.core.domain.model.GlossaryHit
import com.mapineda48.phonotrainer.core.domain.model.IpaLayout
import com.mapineda48.phonotrainer.core.domain.model.LessonContent
import com.mapineda48.phonotrainer.core.domain.model.PhoneArticulation
import com.mapineda48.phonotrainer.core.domain.model.Reference
import kotlinx.coroutines.flow.StateFlow

// :core:content (WP-04), architecture 3.4 and 15.

interface DidacticContent {
  fun glossary(key: String): GlossaryEntry?

  fun lesson(change: ChangeLabel): LessonContent?

  fun ipaLayout(): IpaLayout

  fun phone(symbol: String): PhoneArticulation?

  fun search(query: String): List<GlossaryHit>
}

interface ReferenceRepository {
  val reference: StateFlow<Reference>

  suspend fun replace(json: String, apiVersion: Int)
}
