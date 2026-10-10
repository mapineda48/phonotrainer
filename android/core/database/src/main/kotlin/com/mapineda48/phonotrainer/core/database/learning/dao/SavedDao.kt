package com.mapineda48.phonotrainer.core.database.learning.dao

import androidx.room.Dao
import androidx.room.Query

/** Queries over saved_item (WP-14 owns every learning DAO; it may add queries without a schema change). */
@Dao
interface SavedDao {
  @Query("SELECT COUNT(*) FROM saved_item")
  suspend fun count(): Int
}
