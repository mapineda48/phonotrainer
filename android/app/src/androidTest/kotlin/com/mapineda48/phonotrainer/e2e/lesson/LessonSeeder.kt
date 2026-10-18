package com.mapineda48.phonotrainer.e2e.lesson

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.mapineda48.phonotrainer.core.common.files.FileLayout
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.domain.model.IndexOutcome
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.spi.ClipIndexer
import com.mapineda48.phonotrainer.core.testing.computer.FakeMediaBytes
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Puts a finished audio clip on this phone for the lesson's area tests: the `clip` and `artifact` rows and the verified
 * files a sync pass leaves behind (architecture 4.2, 4.6), then the real indexer (WP-10) cuts it into shorts, so the
 * feed beneath the sheet, the corpus (the lesson's analysis and Compare rows) and the player are all real.
 *
 * The clip is WP-12's rules-3 analysis ("Did you get the water?", "I'm gonna go at 9.30.", "% uh": palatalization
 * across a boundary, flapping, a lexical contraction, a low-confidence word, a numeral and a bare symbol), or, for a
 * lesson that loads after its sheet opens, WP-12's longer synthetic analysis; a synthetic tone is its audio.
 *
 * Why not `TestSeeder`: it reaches the phone through pairing and a sync pass (WP-07, WP-05b, WP-08), and WP-08 is not
 * merged in this baseline (work-plan 2.4: the cores the lesson reads, corpus and player, are). WP-19's flows seed
 * through `TestSeeder` once it is; this helper then goes. It writes only what WP-08's sync writes for a downloaded
 * clip, in the frozen schema 1 (architecture 4.2).
 */
class LessonSeeder @Inject constructor(
  @ApplicationContext private val context: Context,
  private val library: LibraryDatabase,
  private val indexer: ClipIndexer,
) {
  private val written = mutableListOf<File>()

  /** The rules-3 clip on this phone under [id] (with the dialogue track when [dialogue]); its id. */
  suspend fun clip(id: String, dialogue: Boolean = false): JobId =
    write(id, AnalysisFixtures.bytes(AnalysisFixtures.Variant.RULES_3), RULES_3_DURATION_S, dialogue)

  /**
   * A longer clip (WP-12's synthetic analysis, 300 words over 60 phrases, with pitch every 10 ms: the Watch area's
   * clip, whose first short has a spotlight), so its lesson loads after the sheet has opened; its id.
   */
  suspend fun largeClip(id: String): JobId {
    val analysis = AnalysisFixtures.syntheticBytes(words = LARGE_WORDS, phrases = LARGE_PHRASES)
    val duration = DURATION.find(String(analysis, Charsets.UTF_8))?.groupValues?.get(1)?.toDouble() ?: LARGE_DURATION_S
    return write(id, analysis, duration, dialogue = false)
  }

  private suspend fun write(id: String, analysis: ByteArray, durationS: Double, dialogue: Boolean): JobId {
    val jobId = JobId(id)
    val now = System.currentTimeMillis()
    val clip = ContentValues().apply {
      put("job_id", id)
      put("server_id", "server-e2e")
      put("library_id", "library-e2e")
      put("origin", "ACTIVE")
      put("revision", "r1")
      put("status", "DONE")
      put("title", TITLE)
      put("audio_only_job", 1)
      put("media_kind", "AUDIO")
      put("has_dialogue", if (dialogue) 1 else 0)
      put("first_seen_at", now)
      put("on_phone_at", now)
      put("duration_ms", (durationS * MS).toLong())
    }
    db().insert("clip", SQLiteDatabase.CONFLICT_REPLACE, clip)
    artifact(jobId, "analysis", "CLIP_DATA", "json", analysis)
    artifact(jobId, "audio_mix", "AUDIO_MIX", "wav", FakeMediaBytes.wav(durationS + 1.0))
    if (dialogue) artifact(jobId, "audio_dialogue", "AUDIO_DIALOGUE", "wav", FakeMediaBytes.wav(durationS + 1.0, level = 1))
    val outcome = indexer.index(jobId)
    check(outcome is IndexOutcome.Indexed) { "the indexer answered $outcome for $id" }
    return jobId
  }

  private fun artifact(jobId: JobId, name: String, role: String, extension: String, bytes: ByteArray) {
    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val file = FileLayout.artifact(context.filesDir, jobId, name, sha, extension)
    file.parentFile?.mkdirs()
    file.writeBytes(bytes)
    written += file
    val row = ContentValues().apply {
      put("job_id", jobId.value)
      put("name", name)
      put("role", role)
      put("remote_status", "READY")
      put("remote_bytes", bytes.size.toLong())
      put("remote_sha256", sha)
      put("local_state", "READY")
      put("local_file", file.relativeTo(context.filesDir).path)
      put("local_sha256", sha)
      put("local_bytes", bytes.size.toLong())
      put("updated_at", System.currentTimeMillis())
    }
    db().insert("artifact", SQLiteDatabase.CONFLICT_REPLACE, row)
  }

  /** Removes every file this seeder wrote (the databases are in memory and go with the test). */
  fun clear() {
    written.forEach { it.delete() }
    written.clear()
    FileLayout.clipsRoot(context.filesDir).listFiles()?.filter { it.name.startsWith(PREFIX) }?.forEach { it.deleteRecursively() }
  }

  private fun db() = library.openHelper.writableDatabase

  companion object {
    /** Job ids of this area start with it, so [clear] finds their folders. */
    const val PREFIX = "e2e-lesson-"
    const val TITLE = "Two friends at the lake"

    /** The rules-3 analysis's `meta.duration`. */
    private const val RULES_3_DURATION_S = 6.0

    private const val LARGE_WORDS = 300
    private const val LARGE_PHRASES = 60

    /** Used only if the synthetic analysis had no `meta.duration`. */
    private const val LARGE_DURATION_S = 900.0
    private val DURATION = Regex(""""duration"\s*:\s*([0-9.]+)""")
    private const val MS = 1000.0
  }
}
