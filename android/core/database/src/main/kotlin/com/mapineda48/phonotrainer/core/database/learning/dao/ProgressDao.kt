package com.mapineda48.phonotrainer.core.database.learning.dao

import androidx.room.Dao
import androidx.room.Query

/** Queries over short_seen, clip_progress and feed_position (WP-14 owns every learning DAO; it may add queries without a schema change). */
@Dao
interface ProgressDao {
  @Query("SELECT COUNT(*) FROM short_seen")
  suspend fun count(): Int
}
