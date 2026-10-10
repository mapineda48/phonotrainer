package com.mapineda48.phonotrainer.core.database.library.dao

import androidx.room.Dao
import androidx.room.Query

/** The `occurrence` table's queries (WP-10 owns this file; it may add queries without a schema change). */
@Dao
interface OccurrenceDao {
  @Query("SELECT COUNT(*) FROM occurrence")
  suspend fun count(): Int
}
