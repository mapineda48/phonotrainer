package com.mapineda48.phonotrainer.core.database.library.dao

import androidx.room.Dao
import androidx.room.Query

/** The `artifact` table's queries (WP-08 owns this file; it may add queries without a schema change). */
@Dao
interface ArtifactDao {
  @Query("SELECT COUNT(*) FROM artifact")
  suspend fun count(): Int
}
