package com.mapineda48.phonotrainer.core.content

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

/**
 * The computer's reference body, kept as received in `filesDir/reference/reference.json` so a restart serves the same
 * snapshot that sync last fetched instead of falling back to the bundled one. Not backed up (the backup rules include
 * only the settings store): the next sync with a computer fetches it again.
 */
internal class ReferenceStore(private val file: File) {
  @Inject
  constructor(@ApplicationContext context: Context) : this(File(File(context.filesDir, DIRECTORY), FILE))

  /** The stored body, or null when there is none or it cannot be read. */
  fun read(): String? = try {
    if (file.isFile) file.readText(Charsets.UTF_8) else null
  } catch (_: IOException) {
    null
  }

  /** Replaces the stored body atomically: a reader sees the old body or the new one, never a part. */
  @Throws(IOException::class)
  fun write(json: String) {
    val directory = file.parentFile ?: throw IOException("The reference file has no directory")
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create the reference directory")
    val part = File(directory, ".${file.name}.part")
    FileOutputStream(part).use { out ->
      out.write(json.toByteArray(Charsets.UTF_8))
      out.fd.sync()
    }
    Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }

  private companion object {
    const val DIRECTORY = "reference"
    const val FILE = "reference.json"
  }
}
