package com.mapineda48.phonotrainer.core.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.database.learning.LearningDatabase
import java.util.UUID
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * CR-01-A1 (work-plan 4.7; architecture 4.3, 4.4): `learning.db`, built by `DatabaseModule`, names its learning history
 * with exactly one `learning_meta.learning_id`, a random UUID written when the database is created and never changed
 * by a later open. A version 1 file that lacks the row gains one when it is opened (the `INSERT OR IGNORE` at open).
 */
@RunWith(RobolectricTestRunner::class)
class LearningIdTest {
  @get:Rule
  val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LearningDatabase::class.java)

  private val context: Context = ApplicationProvider.getApplicationContext()

  @After
  fun deleteFile() {
    context.deleteDatabase(LearningDatabase.FILE_NAME)
  }

  @Test
  fun aNewDatabaseHasExactlyOneLearningIdUnchangedAfterReopening() {
    val created = metaRows()

    assertThat(created).hasSize(1)
    val (key, id) = created.single()
    assertThat(key).isEqualTo(LEARNING_ID)
    assertThat(UUID.fromString(id).toString()).isEqualTo(id)
    assertThat(metaRows()).isEqualTo(created)
    assertThat(metaRows()).isEqualTo(created)
  }

  @Test
  fun aFileWithoutTheRowGainsOneWhenOpenedAndKeepsIt() {
    helper.createDatabase(LearningDatabase.FILE_NAME, 1).use { db ->
      db.query("SELECT COUNT(*) FROM learning_meta").use { cursor ->
        cursor.moveToFirst()
        assertThat(cursor.getInt(0)).isEqualTo(0)
      }
    }

    val opened = metaRows()

    assertThat(opened.map { it.first }).containsExactly(LEARNING_ID)
    assertThat(metaRows()).isEqualTo(opened)
  }

  @Test
  fun everyNewDatabaseGetsItsOwnId() {
    val first = metaRows().single().second
    context.deleteDatabase(LearningDatabase.FILE_NAME)

    val second = metaRows().single().second

    assertThat(second).isNotEqualTo(first)
  }

  /** Opens `learning.db` with the production builder, reads every `learning_meta` row and closes it again. */
  private fun metaRows(): List<Pair<String, String?>> {
    val db = DatabaseModule.learningDatabase(context)
    try {
      return db.openHelper.writableDatabase.query("SELECT `key`, `value` FROM learning_meta").use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
      }
    } finally {
      db.close()
    }
  }

  private companion object {
    const val LEARNING_ID = "learning_id"
  }
}
