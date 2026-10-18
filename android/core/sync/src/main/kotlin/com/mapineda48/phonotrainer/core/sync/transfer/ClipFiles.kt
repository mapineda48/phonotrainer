package com.mapineda48.phonotrainer.core.sync.transfer

import com.mapineda48.phonotrainer.core.common.files.FileLayout
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.sync.catalogue.ArtifactNames
import java.io.File

/**
 * The clip files of architecture 4.6 under `filesDir`: verified artifacts `clips/<jobId>/<name>.<sha8>.<ext>` and
 * resumable parts `clips/<jobId>/.<name>.<revision>.part`. Every path is built from values `:core:network`'s mappers
 * validated (job id, artifact name, revision, hash) and an extension from the closed map of [ArtifactNames]; each one is
 * also checked to stay under `filesDir/clips` before it is opened (security review M-3, M-4). Rows store paths relative
 * to `filesDir`.
 */
internal class ClipFiles(val filesDir: File) {
  val root: File get() = FileLayout.clipsRoot(filesDir)

  fun clipDir(jobId: String): File? = File(root, jobId).takeIf { confined(it) }

  /** The resumable part of [name]'s [revision], or null when the path would leave `clips/`. */
  fun part(jobId: String, name: String, revision: String): File? {
    if (name !in ArtifactNames.EXTENSIONS) return null
    return FileLayout.artifactPart(filesDir, JobId(jobId), name, revision).takeIf { confined(it) }
  }

  /** Where the verified copy of [name] with checksum [sha256] lives, or null for a name or path outside the rules. */
  fun verified(jobId: String, name: String, sha256: String): File? {
    val extension = ArtifactNames.EXTENSIONS[name] ?: return null
    return FileLayout.artifact(filesDir, JobId(jobId), name, sha256, extension).takeIf { confined(it) }
  }

  /** [file] as a row stores it: relative to `filesDir`. */
  fun relative(file: File): String = file.relativeTo(filesDir).path

  /** The file a row's relative path names, or null when it leaves `clips/`. */
  fun resolve(relative: String?): File? {
    if (relative.isNullOrEmpty()) return null
    return File(filesDir, relative).takeIf { confined(it) }
  }

  /** Deletes everything of [jobId] on this phone; true when nothing is left. */
  fun deleteClip(jobId: String): Boolean {
    val dir = clipDir(jobId) ?: return false
    return !dir.exists() || dir.deleteRecursively()
  }

  /** The bytes [relative] takes on this phone (0 when it is not there). */
  fun sizeOf(relative: String?): Long = resolve(relative)?.takeIf { it.isFile }?.length() ?: 0

  private fun confined(file: File): Boolean = FileLayout.isConfined(file, root)
}
