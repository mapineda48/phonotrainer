package com.mapineda48.phonotrainer.core.content

import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.common.di.DefaultDispatcher
import com.mapineda48.phonotrainer.core.domain.api.DidacticContent
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.GlossaryEntry
import com.mapineda48.phonotrainer.core.domain.model.GlossaryHit
import com.mapineda48.phonotrainer.core.domain.model.IpaLayout
import com.mapineda48.phonotrainer.core.domain.model.LessonContent
import com.mapineda48.phonotrainer.core.domain.model.PhoneArticulation
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The bundled didactic content (architecture 15.3): the glossary behind every "What's this?", the Learn lessons, the
 * IPA chart layout and the articulation table, available offline and before pairing (F-14-AC2).
 *
 * The asset (about 130 KB) is decoded once. Decoding starts on [DefaultDispatcher] as soon as the content is created,
 * so by the time a screen asks it is normally done; a call that arrives earlier waits for that decoding rather than
 * starting another. The asset is checked at build time (the drift check and the decode test), so a failure to decode
 * it is a build defect and fails loudly instead of showing empty explanations.
 */
@Singleton
internal class DidacticContentImpl @Inject constructor(
  private val assets: BundledAssets,
  @ApplicationScope scope: CoroutineScope,
  @DefaultDispatcher dispatcher: CoroutineDispatcher,
) : DidacticContent {
  private val index: DidacticIndex by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    DidacticIndex.decode(assets.read(ContentAssets.DIDACTIC))
  }

  init {
    // A warm-up only: if decoding fails, the first call fails with it, where the defect belongs.
    scope.launch(dispatcher) { runCatching { index } }
  }

  override fun glossary(key: String): GlossaryEntry? = index.glossary[key]

  override fun lesson(change: ChangeLabel): LessonContent? = index.lessons[change]

  override fun ipaLayout(): IpaLayout = index.ipaLayout

  override fun phone(symbol: String): PhoneArticulation? = index.phone(symbol)

  override fun search(query: String): List<GlossaryHit> = index.search(query)
}
