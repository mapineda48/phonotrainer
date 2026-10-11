package com.mapineda48.phonotrainer.core.database.learning

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import com.mapineda48.phonotrainer.core.database.learning.dao.PracticeDao
import com.mapineda48.phonotrainer.core.database.learning.dao.ProgressDao
import com.mapineda48.phonotrainer.core.database.learning.dao.SavedDao
import com.mapineda48.phonotrainer.core.database.learning.dao.SetDao
import com.mapineda48.phonotrainer.core.database.learning.entity.ChangeMetEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.ClipProgressEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.FeedPositionEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.LearningMetaEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.PracticeSessionEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.PracticeStatEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.SavedItemEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.ShortSeenEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.StudySetEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.StudySetItemEntity
import java.util.UUID

/**
 * `learning.db` (architecture 4.1, 4.3): seen state, positions, saved items, practice stats, changes met, study sets,
 * the practice session and the history's `learning_id`. TRUNCATE journal, so Auto Backup copies one consistent file;
 * backed up with the settings (architecture 20.1). Version 1 is frozen at B1 under the policy of architecture 4.4;
 * `learning_meta` was folded into it by CR-01-A1 before any install.
 */
@Database(
  entities = [
    ShortSeenEntity::class,
    ClipProgressEntity::class,
    FeedPositionEntity::class,
    SavedItemEntity::class,
    PracticeStatEntity::class,
    ChangeMetEntity::class,
    StudySetEntity::class,
    StudySetItemEntity::class,
    PracticeSessionEntity::class,
    LearningMetaEntity::class,
  ],
  version = 1,
  exportSchema = true,
)
abstract class LearningDatabase : RoomDatabase() {
  abstract fun progressDao(): ProgressDao

  abstract fun setDao(): SetDao

  abstract fun practiceDao(): PracticeDao

  abstract fun savedDao(): SavedDao

  companion object {
    const val FILE_NAME = "learning.db"

    /**
     * Writes `learning_meta.learning_id` (CR-01-A1; architecture 4.3, 4.4): a random UUID when the database is
     * created, and the same insert with `INSERT OR IGNORE` at every open, so a file without the row gains one and an
     * existing id never changes. Both builders add it: `DatabaseModule`'s and the end-to-end set's in-memory one.
     *
     * It overrides the [SQLiteConnection] callbacks because those are the ones Room calls in both of its modes; without
     * a driver it passes a connection that wraps the support database.
     */
    val LEARNING_ID_CALLBACK: RoomDatabase.Callback =
      object : RoomDatabase.Callback() {
        override fun onCreate(connection: SQLiteConnection) = connection.insertLearningId("INSERT")

        override fun onOpen(connection: SQLiteConnection) = connection.insertLearningId("INSERT OR IGNORE")
      }

    private const val LEARNING_ID = "learning_id"

    private fun SQLiteConnection.insertLearningId(insert: String) {
      prepare("$insert INTO `learning_meta` (`key`, `value`) VALUES (?, ?)").use { statement ->
        statement.bindText(1, LEARNING_ID)
        statement.bindText(2, UUID.randomUUID().toString())
        statement.step()
      }
    }
  }
}
