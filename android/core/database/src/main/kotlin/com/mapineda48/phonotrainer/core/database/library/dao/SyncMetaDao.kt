package com.mapineda48.phonotrainer.core.database.library.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mapineda48.phonotrainer.core.database.library.entity.SyncMetaEntity
import kotlinx.coroutines.flow.Flow

/**
 * The `sync_meta` table's queries (architecture 4.2; WP-08): a key/value store for the last listing time, the last
 * complete listing's server and library ids, the cached reference API version, the pending mass-deletion prompt, the
 * background retry delay and the last reindex rule version. Its keys are named once, by `:core:sync`.
 */
@Dao
interface SyncMetaDao {
  @Query("SELECT COUNT(*) FROM sync_meta")
  suspend fun count(): Int

  @Query("SELECT value FROM sync_meta WHERE `key` = :key")
  suspend fun get(key: String): String?

  @Query("SELECT value FROM sync_meta WHERE `key` = :key")
  fun observe(key: String): Flow<String?>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun put(entry: SyncMetaEntity)

  @Query("DELETE FROM sync_meta WHERE `key` = :key")
  suspend fun delete(key: String): Int
}
