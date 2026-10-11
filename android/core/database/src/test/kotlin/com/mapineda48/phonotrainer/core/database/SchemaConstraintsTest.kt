package com.mapineda48.phonotrainer.core.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The constraints of schema 1 that DAOs rely on (architecture 4.2, 4.3; decision I-17), checked with plain SQL on the
 * production builders, the way a DAO's `@Query` writes:
 * - `saved_item`'s UNIQUE key refuses a second save of the same short, because a short's `word` is the sentinel
 *   `NO_WORD`, never NULL (SQLite treats NULLs in a UNIQUE key as distinct);
 * - counters and flags are NOT NULL DEFAULT 0, so a row inserted without them reads 0 and `x = x + 1` counts;
 * - kind, state and record-time columns are NOT NULL, so no mapper has to invent a value.
 */
@RunWith(RobolectricTestRunner::class)
class SchemaConstraintsTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val library = DatabaseModule.libraryDatabase(context)
  private val learning = DatabaseModule.learningDatabase(context)
  private val lib: SupportSQLiteDatabase = library.openHelper.writableDatabase
  private val learn: SupportSQLiteDatabase = learning.openHelper.writableDatabase

  @After
  fun close() {
    library.close()
    learning.close()
  }

  @Test
  fun aShortIsSavedOnceAndAWordOfItIsAnotherItem() {
    repeat(2) {
      learn.execSQL(
        "INSERT OR IGNORE INTO saved_item (kind, job_id, segment, short_first_segment, saved_at) " +
          "VALUES ('SHORT', 'job-1', 3, 3, 1000)",
      )
    }
    learn.execSQL(
      "INSERT OR IGNORE INTO saved_item (kind, job_id, segment, word, short_first_segment, saved_at) " +
        "VALUES ('WORD', 'job-1', 3, 0, 3, 1000)",
    )
    learn.execSQL(
      "INSERT OR IGNORE INTO saved_item (kind, job_id, segment, word, short_first_segment, saved_at) " +
        "VALUES ('WORD', 'job-1', 3, 0, 3, 2000)",
    )

    assertThat(learn.rows("SELECT kind, word FROM saved_item ORDER BY kind")).containsExactly(
      listOf("SHORT", "-1"),
      listOf("WORD", "0"),
    ).inOrder()
  }

  @Test
  fun learningCountersStartAtZeroAndCount() {
    learn.execSQL("INSERT INTO practice_stat (change) VALUES ('flapping')")
    learn.execSQL("UPDATE practice_stat SET seen = seen + 1, correct = correct + 1 WHERE change = 'flapping'")
    assertThat(learn.row("SELECT seen, correct FROM practice_stat")).containsExactly("1", "1").inOrder()

    learn.execSQL("INSERT INTO short_seen (job_id, first_segment, first_seen_at, last_seen_at) VALUES ('job-1', 0, 1, 1)")
    learn.execSQL("UPDATE short_seen SET seen_count = seen_count + 1")
    assertThat(learn.row("SELECT seen_count FROM short_seen")).containsExactly("1")

    learn.execSQL("INSERT INTO clip_progress (job_id) VALUES ('job-1')")
    assertThat(learn.row("SELECT excluded_from_mix FROM clip_progress")).containsExactly("0")

    learn.execSQL(
      "INSERT INTO study_set (kind, local_date, seed, rule_version, created_at) VALUES ('TODAY', '2026-10-11', 7, 1, 1)",
    )
    assertThat(learn.row("SELECT cursor, answered, `right` FROM study_set")).containsExactly("0", "0", "0").inOrder()
    learn.execSQL("INSERT INTO study_set_item (set_id, position, kind) VALUES (1, 0, 'QUESTION')")
    assertThat(learn.row("SELECT saved_tag, dropped, correct FROM study_set_item"))
      .containsExactly("0", "0", null).inOrder()

    learn.execSQL("INSERT INTO practice_session (seed, questions_json, created_at) VALUES (7, '[]', 1)")
    assertThat(learn.row("SELECT weak_spots, `index` FROM practice_session")).containsExactly("0", "0").inOrder()
  }

  @Test
  fun libraryCountersFlagsAndStatesStartAtTheirDefaults() {
    lib.execSQL(
      "INSERT INTO clip (job_id, server_id, library_id, origin, revision, status, title, first_seen_at) " +
        "VALUES ('job-1', 'server', 'library', 'ACTIVE', 'r1', 'done', 'A walk in the park', 1)",
    )
    assertThat(lib.row("SELECT wanted, removed_by_learner, kept, hidden, video_wanted, has_dialogue, audio_only_job FROM clip"))
      .containsExactly("0", "0", "0", "0", "0", "0", "0")

    lib.execSQL(
      "INSERT INTO artifact (job_id, name, role, remote_status, updated_at) VALUES ('job-1', 'analysis', 'CLIP_DATA', 'READY', 1)",
    )
    lib.execSQL("UPDATE artifact SET verify_failures = verify_failures + 1")
    assertThat(lib.row("SELECT local_state, verify_failures FROM artifact")).containsExactly("ABSENT", "1").inOrder()

    lib.execSQL("INSERT INTO request (id, seq, kind, state, created_at, updated_at) VALUES ('r-1', 1, 'LINK', 'WAITING', 1, 1)")
    lib.execSQL("UPDATE request SET attempts = attempts + 1, rerun_count = rerun_count + 1")
    assertThat(
      lib.row(
        "SELECT attempts, rerun_count, held, copy_complete, source_persisted, audio_extracted, whole_file_fallback, " +
          "audio_only, separate_dialogue, auto_rerun_used, notified_ready, notified_attention FROM request",
      ),
    ).containsExactly("1", "1", "0", "0", "0", "0", "0", "0", "0", "0", "0", "0").inOrder()

    lib.execSQL("INSERT INTO request_op (id, kind, created_at) VALUES ('op-1', 'CANCEL', 1)")
    assertThat(lib.row("SELECT state, attempts, audio_only FROM request_op")).containsExactly("PENDING", "0", null).inOrder()
  }

  @Test
  fun kindsStatesAndRecordTimesAreRequired() {
    val refused = listOf(
      learn to "INSERT INTO saved_item (job_id, segment, saved_at) VALUES ('job-1', 0, 1)",
      learn to "INSERT INTO saved_item (kind, segment, saved_at) VALUES ('SHORT', 0, 1)",
      learn to "INSERT INTO saved_item (kind, job_id, segment) VALUES ('SHORT', 'job-1', 0)",
      learn to "INSERT INTO change_met (change) VALUES ('flapping')",
      learn to "INSERT INTO study_set (local_date, seed, rule_version, created_at) VALUES ('2026-10-11', 7, 1, 1)",
      learn to "INSERT INTO study_set (kind, seed, rule_version, created_at) VALUES ('TODAY', 7, 1, 1)",
      learn to "INSERT INTO feed_position (slot, mode_json) VALUES ('WATCH_LAST', '{}')",
      lib to "INSERT INTO request (id, seq, state, created_at, updated_at) VALUES ('r-2', 2, 'WAITING', 1, 1)",
      lib to "INSERT INTO request (id, seq, kind, created_at, updated_at) VALUES ('r-3', 3, 'LINK', 1, 1)",
      lib to "INSERT INTO request (id, seq, kind, state, updated_at) VALUES ('r-4', 4, 'LINK', 'WAITING', 1)",
      lib to "INSERT INTO request_op (id, created_at) VALUES ('op-2', 1)",
      lib to "INSERT INTO request_op (id, kind) VALUES ('op-3', 'CANCEL')",
    )
    refused.forEach { (db, sql) ->
      assertThrows(sql, SQLiteConstraintException::class.java) { db.execSQL(sql) }
    }
  }

  private fun SupportSQLiteDatabase.rows(sql: String): List<List<String?>> = query(sql).use { cursor ->
    buildList {
      while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
    }
  }

  private fun SupportSQLiteDatabase.row(sql: String): List<String?> = rows(sql).single()
}
