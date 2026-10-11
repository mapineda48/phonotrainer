package com.mapineda48.phonotrainer.e2e.smoke

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.database.DatabaseModule
import com.mapineda48.phonotrainer.core.database.learning.LearningDatabase
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import java.io.File
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Architecture 4.1 and 20.1 on a real device (Robolectric's SQLite reports every rollback journal as `memory`):
 * `learning.db`, the one database Auto Backup copies, uses the TRUNCATE journal, so the single file it copies is
 * consistent and no `-wal` file exists to miss; `library.db` uses WAL. Built with the production builders of
 * `DatabaseModule` (the end-to-end set replaces that module only for the Hilt graph), on files deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseJournalTest {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

  @After
  fun deleteFiles() {
    context.deleteDatabase(LearningDatabase.FILE_NAME)
    context.deleteDatabase(LibraryDatabase.FILE_NAME)
  }

  @Test
  fun learningUsesTheTruncateJournalAndLeavesNoWalFile() {
    val db = DatabaseModule.learningDatabase(context)
    try {
      val sqlite = db.openHelper.writableDatabase
      sqlite.execSQL("PRAGMA user_version = user_version")
      assertThat(sqlite.journalMode()).isEqualTo("truncate")
      assertThat(File(context.getDatabasePath(LearningDatabase.FILE_NAME).path + "-wal").exists()).isFalse()
    } finally {
      db.close()
    }
  }

  @Test
  fun libraryUsesWriteAheadLogging() {
    val db = DatabaseModule.libraryDatabase(context)
    try {
      assertThat(db.openHelper.writableDatabase.journalMode()).isEqualTo("wal")
    } finally {
      db.close()
    }
  }

  private fun androidx.sqlite.db.SupportSQLiteDatabase.journalMode(): String =
    query("PRAGMA journal_mode").use { cursor ->
      cursor.moveToFirst()
      cursor.getString(0).lowercase()
    }
}
