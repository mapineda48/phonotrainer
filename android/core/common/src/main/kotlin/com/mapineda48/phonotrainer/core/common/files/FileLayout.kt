package com.mapineda48.phonotrainer.core.common.files

import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import java.io.File

/**
 * The files on disk of architecture 4.6, named once. `filesDir` holds the verified artifacts in use, their resumable
 * parts and the device queue's file copies (never in shared storage); `cacheDir` holds recordings and generated
 * posters. No path segment comes from outside the app unchecked: job ids, artifact names, revisions and hashes are
 * validated by :core:network's mappers before a domain object exists, extensions come from closed maps, and every
 * resolved path is checked with [isConfined] before it is opened.
 */
object FileLayout {
  /** `filesDir/clips/<jobId>/`. */
  const val CLIPS = "clips"

  /** `filesDir/requests/<requestId>/`. */
  const val REQUESTS = "requests"

  /** `filesDir/datastore/` (DataStore's own directory, architecture 4.5). */
  const val DATASTORE = "datastore"

  /**
   * `filesDir/reference/reference.json`: the computer's reference body last fetched by sync, so a restart serves it
   * instead of the bundled snapshot (`:core:content`'s `ReferenceStore`, architecture 15.4).
   */
  const val REFERENCE = "reference"

  /** `cacheDir/takes/` (v1-complete recordings). */
  const val TAKES = "takes"

  /** `cacheDir/posters/` (generated poster fallbacks). */
  const val POSTERS = "posters"

  /** The device queue's copy in progress, inside the request's directory. */
  const val COPY_PART = ".copy.part"

  fun clipsRoot(filesDir: File): File = File(filesDir, CLIPS)

  fun requestsRoot(filesDir: File): File = File(filesDir, REQUESTS)

  fun clipDir(filesDir: File, id: JobId): File = File(clipsRoot(filesDir), id.value)

  /** A verified artifact in use: `clips/<jobId>/<name>.<sha8>.<ext>`. */
  fun artifact(filesDir: File, id: JobId, name: String, sha256: String, extension: String): File =
    File(clipDir(filesDir, id), "$name.${sha256.take(SHA_PREFIX)}.$extension")

  /** A resumable partial download: `clips/<jobId>/.<name>.<revision>.part`. */
  fun artifactPart(filesDir: File, id: JobId, name: String, revision: String): File =
    File(clipDir(filesDir, id), ".$name.$revision.part")

  fun requestDir(filesDir: File, id: RequestId): File = File(requestsRoot(filesDir), id.value)

  /** A complete file copy: `requests/<requestId>/source.<ext>`; the sharing app's name is kept only in Room. */
  fun requestCopy(filesDir: File, id: RequestId, extension: String): File =
    File(requestDir(filesDir, id), "source.$extension")

  /** The copy in progress: `requests/<requestId>/.copy.part`. */
  fun requestCopyPart(filesDir: File, id: RequestId): File = File(requestDir(filesDir, id), COPY_PART)

  fun takesDir(cacheDir: File): File = File(cacheDir, TAKES)

  fun postersDir(cacheDir: File): File = File(cacheDir, POSTERS)

  /** Whether [file], resolved canonically, stays strictly inside [root] (security review M-3, M-4). */
  fun isConfined(file: File, root: File): Boolean {
    val base = root.canonicalFile
    var current: File? = file.canonicalFile.parentFile
    while (current != null) {
      if (current == base) return true
      current = current.parentFile
    }
    return false
  }

  private const val SHA_PREFIX = 8
}
