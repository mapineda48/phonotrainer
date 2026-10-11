package com.mapineda48.phonotrainer.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.mapineda48.phonotrainer.core.database.learning.LearningDatabase
import com.mapineda48.phonotrainer.core.database.learning.dao.PracticeDao
import com.mapineda48.phonotrainer.core.database.learning.dao.ProgressDao
import com.mapineda48.phonotrainer.core.database.learning.dao.SavedDao
import com.mapineda48.phonotrainer.core.database.learning.dao.SetDao
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.database.library.dao.ArtifactDao
import com.mapineda48.phonotrainer.core.database.library.dao.ClipDao
import com.mapineda48.phonotrainer.core.database.library.dao.OccurrenceDao
import com.mapineda48.phonotrainer.core.database.library.dao.RequestDao
import com.mapineda48.phonotrainer.core.database.library.dao.RequestOpDao
import com.mapineda48.phonotrainer.core.database.library.dao.ShortDao
import com.mapineda48.phonotrainer.core.database.library.dao.SyncMetaDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The two databases (architecture 4.1). Its name is frozen at B1: the end-to-end test set replaces it with
 * `TestDatabaseModule` (in-memory databases, architecture 21.5), and the DAOs of [DaoModule] then come from those.
 * No destructive fallback, ever (4.4). `learning.db` writes its `learning_id` through
 * [LearningDatabase.LEARNING_ID_CALLBACK] (CR-01-A1), which the in-memory builder of the end-to-end set adds too.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
  @Provides
  @Singleton
  fun libraryDatabase(@ApplicationContext context: Context): LibraryDatabase =
    Room.databaseBuilder(context, LibraryDatabase::class.java, LibraryDatabase.FILE_NAME)
      .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
      .build()

  @Provides
  @Singleton
  fun learningDatabase(@ApplicationContext context: Context): LearningDatabase =
    Room.databaseBuilder(context, LearningDatabase::class.java, LearningDatabase.FILE_NAME)
      .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
      .addCallback(LearningDatabase.LEARNING_ID_CALLBACK)
      .build()
}

/** Each DAO from its database, whichever module provided the database. */
@Module
@InstallIn(SingletonComponent::class)
object DaoModule {
  @Provides fun clipDao(db: LibraryDatabase): ClipDao = db.clipDao()

  @Provides fun artifactDao(db: LibraryDatabase): ArtifactDao = db.artifactDao()

  @Provides fun syncMetaDao(db: LibraryDatabase): SyncMetaDao = db.syncMetaDao()

  @Provides fun shortDao(db: LibraryDatabase): ShortDao = db.shortDao()

  @Provides fun occurrenceDao(db: LibraryDatabase): OccurrenceDao = db.occurrenceDao()

  @Provides fun requestDao(db: LibraryDatabase): RequestDao = db.requestDao()

  @Provides fun requestOpDao(db: LibraryDatabase): RequestOpDao = db.requestOpDao()

  @Provides fun progressDao(db: LearningDatabase): ProgressDao = db.progressDao()

  @Provides fun setDao(db: LearningDatabase): SetDao = db.setDao()

  @Provides fun practiceDao(db: LearningDatabase): PracticeDao = db.practiceDao()

  @Provides fun savedDao(db: LearningDatabase): SavedDao = db.savedDao()
}
