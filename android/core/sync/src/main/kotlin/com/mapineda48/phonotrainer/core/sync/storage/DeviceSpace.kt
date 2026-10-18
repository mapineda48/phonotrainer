package com.mapineda48.phonotrainer.core.sync.storage

import android.content.Context
import android.os.storage.StorageManager
import com.mapineda48.phonotrainer.core.common.files.FileLayout
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** The phone's storage as the budget and the floor see it (architecture 7.5). */
internal interface DeviceSpace {
  /** Bytes this app may still write, counting cache the system can clear. */
  fun allocatableBytes(): Long

  /** The size of the storage `filesDir` lives on. */
  fun totalBytes(): Long

  /** The bytes under `filesDir/requests` (the device queue's waiting copies) and `cacheDir`. */
  fun waitingCopiesBytes(): Long

  fun cacheBytes(): Long
}

/**
 * [DeviceSpace] of the phone: `StorageManager.getAllocatableBytes(getUuidForPath(filesDir))`, which counts the cache the
 * system can clear, not `StatFs` alone (Android review L3); the usable space when the storage manager cannot answer.
 */
internal class AndroidDeviceSpace @Inject constructor(
  @param:ApplicationContext private val context: Context,
  private val log: PhonoLog,
) : DeviceSpace {
  override fun allocatableBytes(): Long {
    val dir = context.filesDir
    return try {
      val storage = context.getSystemService(StorageManager::class.java)
      storage.getAllocatableBytes(storage.getUuidForPath(dir))
    } catch (failed: IOException) {
      log.w("sync.allocatable_unknown", failed)
      dir.usableSpace
    } catch (failed: RuntimeException) {
      log.w("sync.allocatable_unknown", failed)
      dir.usableSpace
    }
  }

  override fun totalBytes(): Long = context.filesDir.totalSpace

  override fun waitingCopiesBytes(): Long = sizeOf(FileLayout.requestsRoot(context.filesDir))

  override fun cacheBytes(): Long = sizeOf(context.cacheDir)

  private fun sizeOf(dir: File): Long =
    if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
