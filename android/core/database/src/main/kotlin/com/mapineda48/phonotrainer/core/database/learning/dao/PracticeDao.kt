package com.mapineda48.phonotrainer.core.database.learning.dao

import androidx.room.Dao
import androidx.room.Query

/** Queries over practice_stat, change_met and practice_session (WP-14 owns every learning DAO; it may add queries without a schema change). */
@Dao
interface PracticeDao {
  @Query("SELECT COUNT(*) FROM practice_stat")
  suspend fun count(): Int
}
