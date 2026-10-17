package com.mapineda48.phonotrainer.core.corpus

import com.mapineda48.phonotrainer.core.analysis.CompareRows
import com.mapineda48.phonotrainer.core.common.di.IoDispatcher
import com.mapineda48.phonotrainer.core.database.library.dao.ShortDao
import com.mapineda48.phonotrainer.core.domain.api.ClipContentRepository
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.ClipDataException
import com.mapineda48.phonotrainer.core.domain.model.ClipLease
import com.mapineda48.phonotrainer.core.domain.model.ClipMedia
import com.mapineda48.phonotrainer.core.domain.model.CompareRow
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * A clip's content for the feed, the lesson and the Clip page (architecture 3.4, 9.5): its parsed analysis from the
 * shared cache of three, its local media, the lease that defers its removal while a screen shows it, and the lesson's
 * Compare rows, computed by `:core:analysis`'s [CompareRows] on the parsed clip.
 */
@Singleton
internal class RoomClipContentRepository @Inject constructor(
  private val loader: ClipDataLoader,
  private val shorts: ShortDao,
  private val files: CorpusFiles,
  private val leases: ClipLeaseRegistry,
  @IoDispatcher private val io: CoroutineDispatcher,
) : ClipContentRepository {
  override suspend fun clipData(id: JobId): ClipData = loader.load(id)

  /** Null when the clip is unknown or none of its audio tracks is on the phone ([MediaResolver]). */
  override suspend fun media(id: JobId): ClipMedia? {
    val facts = shorts.mediaFacts(id.value) ?: return null
    val rows = shorts.mediaFiles(id.value)
    // Each file is checked on disk: never on the caller's (main) thread.
    return withContext(io) { MediaResolver.resolve(facts, rows, files::resolve) }
  }

  override fun open(id: JobId): ClipLease = leases.open(id)

  /**
   * Lesson step 2 for [word] (spec F-10 step 2): [CompareRows.of] on the clip's parsed analysis. Empty when the word is
   * not in the clip, or the clip's analysis cannot be read (the lesson reports that through [clipData]).
   */
  override suspend fun compareRows(word: WordRef): List<CompareRow> {
    val clip = try {
      loader.load(word.jobId)
    } catch (unreadable: ClipDataException) {
      return emptyList()
    }
    return CompareRows.of(clip, word)
  }
}
