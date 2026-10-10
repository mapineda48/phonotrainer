package com.mapineda48.phonotrainer.core.content

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Reads a file bundled under this module's `src/main/assets/` as UTF-8 text. Local tests read the same files from
 *  the source tree, so the decoding is tested without Robolectric. */
internal fun interface BundledAssets {
  fun read(path: String): String
}

/** The asset paths the tools write (architecture 15.1, 15.4). */
internal object ContentAssets {
  /** `tools/didactic/extract.mjs` output, schema 1. */
  const val DIDACTIC = "didactic/didactic.json"

  /** `tools/reference/export.py` output: the body of `GET /api/reference`. */
  const val REFERENCE = "reference/reference.json"
}

/** The APK's assets. */
internal class AndroidBundledAssets @Inject constructor(
  @param:ApplicationContext private val context: Context,
) : BundledAssets {
  override fun read(path: String): String =
    context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
