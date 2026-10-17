package com.mapineda48.phonotrainer.core.corpus

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mapineda48.phonotrainer.core.common.hash.Sha256
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.database.library.entity.ArtifactEntity
import com.mapineda48.phonotrainer.core.database.library.entity.ClipEntity
import com.mapineda48.phonotrainer.core.domain.api.ReferenceRepository
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.testing.fake.content.FakeReferenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.study.FakeSavedRepository
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/**
 * The real corpus over an in-memory `library.db` and a temporary `filesDir` (architecture 21.2: Robolectric), with the
 * fakes of work-plan 4.6 for what WP-10 does not own (the reference, the saved items). Clips are seeded the way the
 * sync engine leaves them: a `clip` row, verified files under `clips/<jobId>/`, and their `artifact` rows.
 */
internal class CorpusHarness(
  filesDir: File,
  val reference: ReferenceRepository = FakeReferenceRepository(),
  dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Closeable {
  private val context: Context = ApplicationProvider.getApplicationContext()
  val db: LibraryDatabase = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
  val files = CorpusFiles(filesDir)
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val leases = ClipLeaseRegistry()
  val loader = ClipDataLoader(db.shortDao(), files, dispatcher, ParsedClipCache())
  val indexer = RoomClipIndexer(db.shortDao(), loader, reference, leases, dispatcher, scope, PhonoLog.NONE)
  val content = RoomClipContentRepository(loader, db.shortDao(), files, leases, Dispatchers.IO)
  val saved = FakeSavedRepository()
  val shorts = RoomShortRepository(db.shortDao(), db.occurrenceDao()) { saved }
  val occurrences = RoomOccurrenceRepository(db.occurrenceDao(), reference)
  val removalHook = CorpusRemovalHook(indexer)

  /**
   * Seeds clip [id]: its row, its verified clip data ([analysis], unless null), and the audio tracks given as
   * `name to local state` (READY ones get a file). [clipDataName] is `analysis` or the gzip `analysis_compact`.
   */
  fun addClip(
    id: String,
    analysis: ByteArray?,
    title: String = "Clip $id",
    audio: Map<String, String> = mapOf(AUDIO_MIX to READY),
    clipDataName: String = ClipDataLoader.ANALYSIS,
    mediaKind: String? = "VIDEO",
    wordCount: Int? = null,
    extra: List<ArtifactEntity> = emptyList(),
    clip: (ClipEntity) -> ClipEntity = { it },
  ): JobId {
    val row = ClipEntity(
      jobId = id, serverId = "server-1", libraryId = "library-1", origin = "ACTIVE", revision = "listing-1",
      status = "DONE", title = title, mediaKind = mediaKind, wordCount = wordCount, firstSeenAt = 1_000,
    )
    insertClip(clip(row))
    if (analysis != null) putClipData(id, analysis, clipDataName)
    for ((name, state) in audio) {
      val role = if (name.startsWith("audio_dialogue")) MediaResolver.AUDIO_DIALOGUE else MediaResolver.AUDIO_MIX
      putArtifact(id, name, role, state, if (state == READY) WAV else null, "wav")
    }
    extra.forEach { putRow(it) }
    return JobId(id)
  }

  /** Writes [bytes] as the verified clip-data file [name] of [id] (replacing the previous revision's row). */
  fun putClipData(id: String, bytes: ByteArray, name: String = ClipDataLoader.ANALYSIS): String {
    val ext = if (name == ClipDataLoader.COMPACT) "json.gz" else "json"
    return putArtifact(id, name, "CLIP_DATA", READY, bytes, ext)!!
  }

  /** An artifact row of [id] and, when [bytes] is given, its verified file; returns its SHA-256. */
  fun putArtifact(
    id: String,
    name: String,
    role: String,
    state: String,
    bytes: ByteArray?,
    ext: String,
    remoteStatus: String = READY,
    remoteBytes: Long? = bytes?.size?.toLong(),
    partBytes: Long? = null,
  ): String? {
    val sha = bytes?.let(Sha256::hex)
    val local = bytes?.let {
      val relative = "clips/$id/$name.${sha!!.take(8)}.$ext"
      File(files.filesDir, relative).apply {
        parentFile!!.mkdirs()
        writeBytes(it)
      }
      relative
    }
    putRow(
      ArtifactEntity(
        jobId = id, name = name, role = role, remoteStatus = remoteStatus, remoteBytes = remoteBytes,
        remoteSha256 = sha, localState = state, localFile = local, localSha256 = sha, partBytes = partBytes,
        updatedAt = 1_000,
      ),
    )
    return sha
  }

  /**
   * Writes an `artifact` row, replacing the one of the same name. WP-08's DAOs own these tables, so the tests write
   * them with plain SQL, the way the sync engine's rows look.
   */
  fun putRow(row: ArtifactEntity) {
    val values = ContentValues().apply {
      put("job_id", row.jobId)
      put("name", row.name)
      put("role", row.role)
      put("remote_status", row.remoteStatus)
      put("remote_bytes", row.remoteBytes)
      put("remote_sha256", row.remoteSha256)
      put("local_state", row.localState)
      put("local_file", row.localFile)
      put("local_sha256", row.localSha256)
      put("local_bytes", row.localBytes)
      put("part_bytes", row.partBytes)
      put("verify_failures", row.verifyFailures)
      put("updated_at", row.updatedAt)
    }
    db.openHelper.writableDatabase.insert("artifact", SQLiteDatabase.CONFLICT_REPLACE, values)
  }

  private fun insertClip(row: ClipEntity) {
    val values = ContentValues().apply {
      put("job_id", row.jobId)
      put("server_id", row.serverId)
      put("library_id", row.libraryId)
      put("origin", row.origin)
      put("revision", row.revision)
      put("status", row.status)
      put("title", row.title)
      put("media_kind", row.mediaKind)
      put("audio_only_job", row.audioOnlyJob)
      put("has_dialogue", row.hasDialogue)
      put("video_codec", row.videoCodec)
      put("video_width", row.videoWidth)
      put("video_height", row.videoHeight)
      put("video_rotation", row.videoRotation)
      put("video_offset_ms", row.videoOffsetMs)
      put("word_count", row.wordCount)
      put("deleted_on_computer", row.deletedOnComputer)
      put("indexed_revision", row.indexedRevision)
      put("index_rule_version", row.indexRuleVersion)
      put("first_seen_at", row.firstSeenAt)
    }
    db.openHelper.writableDatabase.insert("clip", SQLiteDatabase.CONFLICT_ABORT, values)
  }

  /** Runs [sql] on the database (to age an index, mark a deletion…) and tells Room's observers. */
  fun exec(sql: String) {
    db.openHelper.writableDatabase.execSQL(sql)
    changed()
  }

  /** Plain SQL writes bypass Room's own transactions: this makes its flows look at the tables again. */
  fun changed() {
    db.invalidationTracker.refreshAsync()
  }

  /** The number of rows [sql] (a `SELECT COUNT(*) …`) counts. */
  fun count(sql: String): Int = db.openHelper.readableDatabase.query(sql).use { it.moveToFirst(); it.getInt(0) }

  /** Waits until [condition] holds (work launched on [scope]), failing after 5 s. */
  suspend fun eventually(condition: suspend () -> Boolean) {
    withTimeout(EVENTUALLY_MS) {
      while (!condition()) delay(POLL_MS)
    }
  }

  override fun close() {
    scope.cancel()
    db.close()
  }

  companion object {
    const val READY: String = "READY"
    const val AUDIO_MIX: String = "audio_mix"
    const val AUDIO_DIALOGUE: String = "audio_dialogue"
    val WAV: ByteArray = "RIFF-not-really-a-wav".toByteArray()
    private const val EVENTUALLY_MS = 5_000L
    private const val POLL_MS = 10L

    /** [json] as the bytes of `analysis.json`. */
    fun bytes(json: JsonObject): ByteArray = AnalysisFixtures.encode(json)
  }
}
