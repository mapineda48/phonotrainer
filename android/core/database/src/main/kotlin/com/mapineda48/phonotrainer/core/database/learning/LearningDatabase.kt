package com.mapineda48.phonotrainer.core.database.learning

import androidx.room.Database
import androidx.room.RoomDatabase
import com.mapineda48.phonotrainer.core.database.learning.dao.PracticeDao
import com.mapineda48.phonotrainer.core.database.learning.dao.ProgressDao
import com.mapineda48.phonotrainer.core.database.learning.dao.SavedDao
import com.mapineda48.phonotrainer.core.database.learning.dao.SetDao
import com.mapineda48.phonotrainer.core.database.learning.entity.ChangeMetEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.ClipProgressEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.FeedPositionEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.PracticeSessionEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.PracticeStatEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.SavedItemEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.ShortSeenEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.StudySetEntity
import com.mapineda48.phonotrainer.core.database.learning.entity.StudySetItemEntity

/**
 * `learning.db` (architecture 4.1, 4.3): seen state, positions, saved items, practice stats, changes met, study sets
 * and the practice session. TRUNCATE journal, so Auto Backup copies one consistent file; backed up with the settings
 * (architecture 20.1). Version 1 is frozen at B1 under the policy of architecture 4.4.
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
  }
}
