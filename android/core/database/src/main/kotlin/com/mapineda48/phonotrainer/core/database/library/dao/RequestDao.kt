package com.mapineda48.phonotrainer.core.database.library.dao

import androidx.room.Dao
import androidx.room.Query

/** The `request` table's queries (WP-09 owns this file; it may add queries without a schema change). */
@Dao
interface RequestDao {
  @Query("SELECT COUNT(*) FROM request")
  suspend fun count(): Int
}
