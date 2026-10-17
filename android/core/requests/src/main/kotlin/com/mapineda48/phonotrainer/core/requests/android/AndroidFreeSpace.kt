package com.mapineda48.phonotrainer.core.requests.android

import android.content.Context
import android.os.storage.StorageManager
import com.mapineda48.phonotrainer.core.requests.intake.FreeSpace
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Free space as architecture 7.5 measures it: `StorageManager.getAllocatableBytes` for the volume of `filesDir` (it
 * counts cache the system can clear, Android review L3), and the floor `min(1 GB, 10 % of total)`.
 */
internal class AndroidFreeSpace @Inject constructor(
  @param:ApplicationContext private val context: Context,
) : FreeSpace {
  override fun allocatableBytes(): Long {
    val storage = context.getSystemService(StorageManager::class.java)
    val dir = context.filesDir
    return runCatching { storage.getAllocatableBytes(storage.getUuidForPath(dir)) }.getOrElse { dir.usableSpace }
  }

  override fun floorBytes(): Long = minOf(ONE_GB, context.filesDir.totalSpace / TEN_PERCENT)

  private companion object {
    const val ONE_GB = 1_000_000_000L
    const val TEN_PERCENT = 10
  }
}
