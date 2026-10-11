package com.mapineda48.phonotrainer.core.content

import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.common.di.IoDispatcher
import com.mapineda48.phonotrainer.core.content.json.decodeReference
import com.mapineda48.phonotrainer.core.content.json.decodeReferenceOrNull
import com.mapineda48.phonotrainer.core.domain.api.ReferenceRepository
import com.mapineda48.phonotrainer.core.domain.model.Reference
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The reference snapshot (architecture 15.4): change names, families, descriptions, practice advice, the IPA tokens
 * and the metric references, available offline and before pairing.
 *
 * [reference] starts with the snapshot bundled at build time (`tools/reference/export.py`). The body sync last fetched
 * from the computer, kept by [ReferenceStore], replaces it as soon as it is read from disk, and wins over the bundled
 * one whatever its version: it is what the paired computer's clips were analysed with.
 *
 * [replace] takes a body fetched from the computer (sync, architecture 7.1 step 5). A body that does not decode, or
 * that declares an `api_version` other than the one the listing announced, is refused: the current snapshot stays and
 * nothing is stored. A body that decodes is stored first and shown second, so [reference] never shows a snapshot that
 * a restart would lose. Whether a replace took is therefore `reference.value.apiVersion == apiVersion` afterwards;
 * `replace` itself never throws for a bad body.
 */
@Singleton
internal class ReferenceRepositoryImpl @Inject constructor(
  assets: BundledAssets,
  private val store: ReferenceStore,
  @ApplicationScope scope: CoroutineScope,
  @IoDispatcher private val io: CoroutineDispatcher,
) : ReferenceRepository {
  /** The bundled snapshot is checked at build time (the snapshot test), so a failure here is a build defect. */
  private val state = MutableStateFlow(decodeReference(assets.read(ContentAssets.REFERENCE)))
  private val writes = Mutex()
  private val restored: Job = scope.launch(io) {
    store.read()?.let(::decodeReferenceOrNull)?.let { state.value = it }
  }

  override val reference: StateFlow<Reference> = state.asStateFlow()

  override suspend fun replace(json: String, apiVersion: Int) {
    // A stored body read late must never overwrite a newer one.
    restored.join()
    writes.withLock {
      withContext(io) {
        val decoded = decodeReferenceOrNull(json)?.takeIf { it.apiVersion == apiVersion } ?: return@withContext
        try {
          store.write(json)
        } catch (_: IOException) {
          return@withContext
        }
        state.value = decoded
      }
    }
  }
}
