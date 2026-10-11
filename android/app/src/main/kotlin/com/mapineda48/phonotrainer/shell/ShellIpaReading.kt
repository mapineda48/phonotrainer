package com.mapineda48.phonotrainer.shell

import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.common.di.DefaultDispatcher
import com.mapineda48.phonotrainer.core.domain.api.DidacticContent
import com.mapineda48.phonotrainer.core.domain.api.ReferenceRepository
import com.mapineda48.phonotrainer.core.ui.ipa.IpaReading
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The reading the shell gives `IpaText` through `LocalIpaReading` (A-05, F-16-AC2): the reference snapshot's
 * multi-character IPA tokens, so "aɪ" stays one symbol, and each phone's name from the didactic content, so "[ɾ]" is
 * read with its name.
 *
 * Both sources are created and first read on [dispatcher], never on the main thread: the reference decodes its bundled
 * snapshot when it is created, and the didactic content decodes its asset on first use (architecture 15.3). Until
 * then [reading] reads symbols as they are written. A reference snapshot with other tokens gives a new reading; one
 * with the same tokens keeps the current instance, so the shell is not recomposed for nothing.
 */
@Singleton
class ShellIpaReading @Inject constructor(
  reference: Lazy<ReferenceRepository>,
  didactic: Lazy<DidacticContent>,
  @ApplicationScope scope: CoroutineScope,
  @DefaultDispatcher dispatcher: CoroutineDispatcher,
) {
  val reading: StateFlow<IpaReading> = flow {
    // ipaLayout() forces the content's one decode here rather than on the first name TalkBack asks for.
    val content = didactic.get().also { it.ipaLayout() }
    val tokens = reference.get().reference.map { it.ipaTokens }.distinctUntilChanged()
    emitAll(tokens.map { inventory -> IpaReading(inventory) { symbol -> content.phone(symbol)?.name } })
  }
    .flowOn(dispatcher)
    .stateIn(scope, SharingStarted.Eagerly, IpaReading())
}
