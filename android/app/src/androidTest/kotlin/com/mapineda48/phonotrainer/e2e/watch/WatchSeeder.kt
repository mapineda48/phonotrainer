package com.mapineda48.phonotrainer.e2e.watch

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
 * Puts finished audio clips on this phone for the Watch area tests: the `clip` and `artifact` rows and the verified
 * files a sync pass leaves behind (architecture 4.2, 4.6), then the real indexer (`ClipIndexer`, WP-10) cuts them
 * into shorts, so the feed, the corpus and the player under test are all real.
 *
 * Why not `TestSeeder`: it reaches the phone through pairing, the network client and a sync pass (WP-07, WP-05b,
 * WP-08), which are not merged in this baseline (work-plan 2.4: the cores the area reads, corpus and player, are).
 * Once they are, WP-19's flows seed through `TestSeeder` and this helper can go. It writes only what WP-08's sync
 * writes for a downloaded clip, in the frozen schema 1 (architecture 4.2).
 */
class WatchSeeder @Inject constructor(
  @ApplicationContext private val context: Context,
  private val library: LibraryDatabase,
  private val indexer: ClipIndexer,
) {
  private val written = mutableListOf<File>()

  /** A finished audio clip of [phrases] phrases (synthetic, rules 3, dialogue separated) on this phone; its id. */
  suspend fun audioClip(id: String, phrases: Int = 60, seed: Long = 7, dialogue: Boolean = false): JobId {
    val jobId = JobId(id)
    val analysis = AnalysisFixtures.syntheticBytes(words = phrases * 5, phrases = phrases, seed = seed)
    val duration = DURATION.find(String(analysis, Charsets.UTF_8))?.groupValues?.get(1)?.toDouble() ?: 120.0
    val now = System.currentTimeMillis()
    val clip = ContentValues().apply {
      put("job_id", id)
      put("server_id", "server-e2e")
      put("library_id", "library-e2e")
      put("origin", "ACTIVE")
      put("revision", "r1")
      put("status", "DONE")
      put("title", "Synthetic clip $id")
      put("audio_only_job", 1)
      put("media_kind", "AUDIO")
      put("has_dialogue", 1)
      put("first_seen_at", now)
      put("on_phone_at", now)
      put("duration_ms", (duration * 1000).toLong())
    }
    db().insert("clip", SQLiteDatabase.CONFLICT_REPLACE, clip)
    artifact(jobId, "analysis", "CLIP_DATA", "json", analysis)
    artifact(jobId, "audio_mix", "AUDIO_MIX", "wav", FakeMediaBytes.wav(duration + 1.0))
    if (dialogue) addDialogue(jobId, duration)
    val outcome = indexer.index(jobId)
    check(outcome is IndexOutcome.Indexed) { "the indexer answered $outcome for $id" }
    return jobId
  }

  /** The clip's dialogue track arrives on this phone (a verified `audio_dialogue`, as sync would leave it). */
  fun addDialogue(jobId: JobId, durationS: Double = 120.0) {
    artifact(jobId, "audio_dialogue", "AUDIO_DIALOGUE", "wav", FakeMediaBytes.wav(durationS + 1.0, level = 1))
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
    const val PREFIX = "e2e-watch-"
    private val DURATION = Regex(""""duration"\s*:\s*([0-9.]+)""")
  }
}
