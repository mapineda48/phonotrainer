package com.mapineda48.phonotrainer.core.database.library.dao

import androidx.room.Dao
import androidx.room.Query

/** The `sync_meta` table's queries (WP-08 owns this file; it may add queries without a schema change). */
@Dao
interface SyncMetaDao {
  @Query("SELECT COUNT(*) FROM sync_meta")
  suspend fun count(): Int
}
