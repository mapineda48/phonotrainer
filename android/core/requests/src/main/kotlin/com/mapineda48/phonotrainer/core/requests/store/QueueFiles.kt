package com.mapineda48.phonotrainer.core.requests.store

import com.mapineda48.phonotrainer.core.common.files.FileLayout
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import java.io.File

/**
 * The device queue's files (architecture 4.6): `requests/<requestId>/source.<ext>` and `requests/<requestId>/.copy.part`
 * under `filesDir`. The request id is the app's own UUID and the extension comes from the closed suffix list, and every
 * resolved path is still checked to stay under `filesDir/requests` before it is used (security review M-3, M-4).
 */
internal class QueueFiles(val filesDir: File) {
  val root: File get() = FileLayout.requestsRoot(filesDir)

  fun dir(id: RequestId): File = confined(FileLayout.requestDir(filesDir, id))

  fun part(id: RequestId): File = confined(FileLayout.requestCopyPart(filesDir, id))

  /** A second part, for the audio extracted from a copy that is already complete. */
  fun audioPart(id: RequestId): File = confined(File(FileLayout.requestDir(filesDir, id), AUDIO_PART))

  fun copy(id: RequestId, extension: String): File = confined(FileLayout.requestCopy(filesDir, id, extension))

  /** The copy a row names (relative to `filesDir`), or null when the name leaves the request's directory. */
  fun resolve(id: RequestId, relative: String?): File? {
    if (relative == null) return null
    val file = File(filesDir, relative)
    return file.takeIf { FileLayout.isConfined(it, FileLayout.requestDir(filesDir, id)) }
  }

  /** The path a row stores: relative to `filesDir`. */
  fun relative(file: File): String = file.relativeTo(filesDir).path

  /** Deletes the request's directory with everything in it. */
  fun delete(id: RequestId): Boolean {
    val dir = FileLayout.requestDir(filesDir, id)
    if (!FileLayout.isConfined(dir, filesDir)) return false
    return !dir.exists() || dir.deleteRecursively()
  }

  /** Bytes of everything kept for [id]. */
  fun bytes(id: RequestId): Long = FileLayout.requestDir(filesDir, id).walkTopDown().filter { it.isFile }.sumOf { it.length() }

  private fun confined(file: File): File {
    check(FileLayout.isConfined(file, root)) { "a request file must stay under requests/" }
    return file
  }

  private companion object {
    const val AUDIO_PART = ".audio.part"
  }
}
