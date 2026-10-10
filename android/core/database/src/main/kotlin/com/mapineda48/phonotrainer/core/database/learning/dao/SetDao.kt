package com.mapineda48.phonotrainer.core.database.learning.dao

import androidx.room.Dao
import androidx.room.Query

/** Queries over study_set and study_set_item (WP-14 owns every learning DAO; it may add queries without a schema change). */
@Dao
interface SetDao {
  @Query("SELECT COUNT(*) FROM study_set")
  suspend fun count(): Int
}
