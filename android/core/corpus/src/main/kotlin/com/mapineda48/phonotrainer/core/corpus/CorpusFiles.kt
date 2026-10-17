package com.mapineda48.phonotrainer.core.corpus

import com.mapineda48.phonotrainer.core.common.files.FileLayout
import java.io.File

/**
 * The clip files of this phone (architecture 4.6): an artifact row's `local_file` is a path relative to `filesDir`,
 * and nothing is opened unless it resolves inside `filesDir/clips` (security review M-3, M-4). Its own type so the
 * tests point it at a temporary directory.
 */
class CorpusFiles(val filesDir: File) {
  private val clipsRoot: File = FileLayout.clipsRoot(filesDir)

  /** The file [localFile] names, or null when the path leaves `filesDir/clips` or the file is not there. */
  fun resolve(localFile: String): File? {
    if (localFile.isEmpty()) return null
    val file = File(filesDir, localFile)
    if (!FileLayout.isConfined(file, clipsRoot)) return null
    return file.takeIf { it.isFile }
  }
}
