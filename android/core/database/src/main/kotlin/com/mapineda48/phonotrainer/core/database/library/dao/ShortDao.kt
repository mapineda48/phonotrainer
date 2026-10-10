package com.mapineda48.phonotrainer.core.database.library.dao

import androidx.room.Dao
import androidx.room.Query

/** The `short` table's queries (WP-10 owns this file; it may add queries without a schema change). */
@Dao
interface ShortDao {
  @Query("SELECT COUNT(*) FROM short")
  suspend fun count(): Int
}
