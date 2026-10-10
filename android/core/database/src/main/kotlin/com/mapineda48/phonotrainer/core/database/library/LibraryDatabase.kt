package com.mapineda48.phonotrainer.core.database.library

import androidx.room.Database
import androidx.room.RoomDatabase
import com.mapineda48.phonotrainer.core.database.library.dao.ArtifactDao
import com.mapineda48.phonotrainer.core.database.library.dao.ClipDao
import com.mapineda48.phonotrainer.core.database.library.dao.OccurrenceDao
import com.mapineda48.phonotrainer.core.database.library.dao.RequestDao
import com.mapineda48.phonotrainer.core.database.library.dao.RequestOpDao
import com.mapineda48.phonotrainer.core.database.library.dao.ShortDao
import com.mapineda48.phonotrainer.core.database.library.dao.SyncMetaDao
import com.mapineda48.phonotrainer.core.database.library.entity.ArtifactEntity
import com.mapineda48.phonotrainer.core.database.library.entity.ClipEntity
import com.mapineda48.phonotrainer.core.database.library.entity.OccurrenceEntity
import com.mapineda48.phonotrainer.core.database.library.entity.RequestEntity
import com.mapineda48.phonotrainer.core.database.library.entity.RequestOpEntity
import com.mapineda48.phonotrainer.core.database.library.entity.ShortEntity
import com.mapineda48.phonotrainer.core.database.library.entity.SyncMetaEntity

/**
 * `library.db` (architecture 4.1, 4.2): the catalogue mirror, artifacts and local files, derived shorts and
 * occurrences, the device queue and its outbox, sync metadata. WAL journal; never backed up. Version 1 is frozen at
 * B1; until the first install on the test phone, entity change requests are folded into version 1 (architecture
 * 4.4), and from then on every change ships a migration and its MigrationTestHelper test.
 */
@Database(
  entities = [
    ClipEntity::class,
    ArtifactEntity::class,
    ShortEntity::class,
    OccurrenceEntity::class,
    RequestEntity::class,
    RequestOpEntity::class,
    SyncMetaEntity::class,
  ],
  version = 1,
  exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
  abstract fun clipDao(): ClipDao

  abstract fun artifactDao(): ArtifactDao

  abstract fun syncMetaDao(): SyncMetaDao

  abstract fun shortDao(): ShortDao

  abstract fun occurrenceDao(): OccurrenceDao

  abstract fun requestDao(): RequestDao

  abstract fun requestOpDao(): RequestOpDao

  companion object {
    const val FILE_NAME = "library.db"
  }
}
