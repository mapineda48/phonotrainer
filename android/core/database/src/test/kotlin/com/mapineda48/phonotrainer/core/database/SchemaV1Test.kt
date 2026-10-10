package com.mapineda48.phonotrainer.core.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.database.learning.LearningDatabase
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The migration harness of architecture 4.4 with the version 1 schemas exported at the contract freeze: each schema
 * is a test asset, creates a database that Room validates against its entities, and the databases open with the
 * journal modes of architecture 4.1. Every later version adds its N-1 → N and 1 → latest cases here.
 */
@RunWith(RobolectricTestRunner::class)
class SchemaV1Test {
  @get:Rule
  val library = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LibraryDatabase::class.java)

  @get:Rule
  val learning = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LearningDatabase::class.java)

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun libraryVersion1MatchesItsEntities() {
    library.createDatabase(LIBRARY_TEST_DB, 1).use { db ->
      assertThat(db.tables()).containsAtLeast("clip", "artifact", "short", "occurrence", "request", "request_op", "sync_meta")
    }
    library.runMigrationsAndValidate(LIBRARY_TEST_DB, 1, true).close()
  }

  @Test
  fun learningVersion1MatchesItsEntities() {
    learning.createDatabase(LEARNING_TEST_DB, 1).use { db ->
      assertThat(db.tables()).containsAtLeast(
        "short_seen",
        "clip_progress",
        "feed_position",
        "saved_item",
        "practice_stat",
        "change_met",
        "study_set",
        "study_set_item",
        "practice_session",
      )
    }
    learning.runMigrationsAndValidate(LEARNING_TEST_DB, 1, true).close()
  }

  @Test
  fun theDatabasesOpenWithTheirFilesAndJournalModes() = runTest {
    val module = DatabaseModule
    val libraryDb = module.libraryDatabase(context)
    val learningDb = module.learningDatabase(context)
    try {
      assertThat(module.clipDao(libraryDb).count()).isEqualTo(0)
      assertThat(module.savedDao(learningDb).count()).isEqualTo(0)
      assertThat(libraryDb.openHelper.writableDatabase.journalMode()).isEqualTo("wal")
      // learning.db asks for TRUNCATE, a rollback journal (one file for Auto Backup). Robolectric's SQLite reports
      // every rollback journal as "memory", so the JVM test can only prove it is not WAL.
      assertThat(learningDb.openHelper.writableDatabase.journalMode()).isNotEqualTo("wal")
      assertThat(context.getDatabasePath(LibraryDatabase.FILE_NAME).exists()).isTrue()
      assertThat(context.getDatabasePath(LearningDatabase.FILE_NAME).exists()).isTrue()
    } finally {
      libraryDb.close()
      learningDb.close()
    }
  }

  private fun SupportSQLiteDatabase.tables(): List<String> =
    query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
      buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

  private fun SupportSQLiteDatabase.journalMode(): String =
    query("PRAGMA journal_mode").use { cursor ->
      cursor.moveToFirst()
      cursor.getString(0).lowercase()
    }

  private companion object {
    const val LIBRARY_TEST_DB = "library-migration-test.db"
    const val LEARNING_TEST_DB = "learning-migration-test.db"
  }
}
