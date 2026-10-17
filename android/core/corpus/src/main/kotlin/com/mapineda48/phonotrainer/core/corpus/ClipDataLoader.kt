package com.mapineda48.phonotrainer.core.corpus

import com.mapineda48.phonotrainer.core.analysis.AnalysisParser
import com.mapineda48.phonotrainer.core.common.di.DefaultDispatcher
import com.mapineda48.phonotrainer.core.database.library.dao.ClipDataFile
import com.mapineda48.phonotrainer.core.database.library.dao.ShortDao
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.ClipDataException
import com.mapineda48.phonotrainer.core.domain.model.JobId
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Reads a clip's analysis from its verified clip-data file (architecture 9.1, 9.5) through the [ParsedClipCache]
 * shared by the whole app, parsing on [parsing] (`Dispatchers.Default`), never on the caller's thread.
 */
@Singleton
internal class ClipDataLoader(
  private val shorts: ShortDao,
  private val files: CorpusFiles,
  private val parsing: CoroutineDispatcher,
  private val cache: ParsedClipCache,
) {
  @Inject
  constructor(
    shorts: ShortDao,
    files: CorpusFiles,
    @DefaultDispatcher parsing: CoroutineDispatcher,
  ) : this(shorts, files, parsing, ParsedClipCache())

  private val parseCount = AtomicInteger()

  /** Parses done since this loader was made (tests: what the cache saved). */
  val parses: Int get() = parseCount.get()

  /** The clip-data file in use for [id], or null when none is verified on this phone. */
  suspend fun currentFile(id: JobId): ClipDataFile? = preferred(shorts.clipDataFiles(id.value))

  /** The parsed analysis of [id]; throws [ClipDataException] (Missing or Corrupt). */
  suspend fun load(id: JobId): ClipData {
    val file = currentFile(id) ?: throw ClipDataException(AppError.Content.Missing, "no clip data on this phone")
    return load(id, file)
  }

  /** The parsed analysis of [id] from [file] (one of [currentFile]'s answers); throws [ClipDataException]. */
  suspend fun load(id: JobId, file: ClipDataFile): ClipData =
    cache.get(id, revisionOf(file)) { parse(id, file) }

  /** Forgets the parsed clips of [ids]. */
  suspend fun evict(ids: Set<JobId>) = cache.evict(ids)

  /** The job ids parsed and cached now, least recently used first (tests). */
  suspend fun cachedIds(): List<JobId> = cache.cachedIds()

  private suspend fun parse(id: JobId, file: ClipDataFile): ClipData = withContext(parsing) {
    parseCount.incrementAndGet()
    val path = files.resolve(file.localFile)
      ?: throw ClipDataException(AppError.Content.Missing, "the clip data file is not on this phone")
    try {
      path.inputStream().buffered().use { input ->
        AnalysisParser.parse(id, revisionOf(file), input, gzip = file.name == COMPACT)
      }
    } catch (gone: FileNotFoundException) {
      throw ClipDataException(AppError.Content.Missing, "the clip data file is not on this phone", gone)
    }
  }

  companion object {
    /** The plain `analysis.json` artifact (v1-core). */
    const val ANALYSIS: String = "analysis"

    /** The gzip rendition of the same JSON (v1-complete, server-contract 6.4.1). */
    const val COMPACT: String = "analysis_compact"

    /** Server-contract 6.4.2: an artifact's revision is the first 16 hex digits of its SHA-256. */
    private const val REVISION_DIGITS = 16

    /** The clip data [ClipData.revision] names: the revision of the verified file. */
    fun revisionOf(file: ClipDataFile): String = file.localSha256.take(REVISION_DIGITS)

    /**
     * Which verified clip-data file to read: the compact rendition when it is on the phone (the study pack prefers it,
     * architecture 7.4), else `analysis.json`; both hold the same analysis.
     */
    fun preferred(files: List<ClipDataFile>): ClipDataFile? =
      files.firstOrNull { it.name == COMPACT } ?: files.firstOrNull { it.name == ANALYSIS } ?: files.firstOrNull()
  }
}
