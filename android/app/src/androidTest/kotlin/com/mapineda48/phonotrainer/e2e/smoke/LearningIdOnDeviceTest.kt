package com.mapineda48.phonotrainer.e2e.smoke

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.database.DatabaseModule
import com.mapineda48.phonotrainer.core.database.learning.LearningDatabase
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CR-01-A1 on the device's own SQLite (architecture 4.3, 4.4): `DatabaseModule`'s production builder gives a new
 * `learning.db` exactly one `learning_id`, a UUID, and closing and reopening the file keeps it. The end-to-end set
 * replaces that module only for the Hilt graph, so the file here is the production one, deleted before and after.
 */
@RunWith(AndroidJUnit4::class)
class LearningIdOnDeviceTest {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

  @Before
  @After
  fun deleteFile() {
    context.deleteDatabase(LearningDatabase.FILE_NAME)
  }

  @Test
  fun aNewLearningDatabaseKeepsItsOneLearningIdWhenReopened() {
    val created = metaRows()

    assertThat(created.map { it.first }).containsExactly("learning_id")
    val id = created.single().second
    assertThat(UUID.fromString(id).toString()).isEqualTo(id)
    assertThat(metaRows()).isEqualTo(created)
  }

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
}
