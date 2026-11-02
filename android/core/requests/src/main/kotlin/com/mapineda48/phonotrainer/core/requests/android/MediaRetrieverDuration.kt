package com.mapineda48.phonotrainer.core.requests.android

import android.media.MediaMetadataRetriever
import com.mapineda48.phonotrainer.core.requests.intake.MediaDuration
import java.io.File
import javax.inject.Inject

/**
 * [MediaDuration] from the container's own header (`MediaMetadataRetriever`'s `METADATA_KEY_DURATION`), read from the
 * copy this app keeps; anything unreadable, missing or not positive gives null.
 */
internal class MediaRetrieverDuration @Inject constructor() : MediaDuration {
  override fun durationMs(file: File): Long? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(file.path)
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
    } finally {
      retriever.release()
    }
  }.getOrNull()
}
