package com.mapineda48.phonotrainer.core.requests.android

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.mapineda48.phonotrainer.core.requests.intake.AudioExtraction
import com.mapineda48.phonotrainer.core.requests.intake.ExtractorInput
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject

/**
 * "Audio only" for a local video (architecture 8.3): `MediaExtractor` + `MediaMuxer` copy the first audio track into
 * its own container without re-encoding: AAC → MPEG-4 (`.m4a`), Opus → Ogg (`.ogg`), Vorbis → WebM (`.webm`). Any
 * other codec, a file without audio, or any failure gives null, and the caller keeps the whole file ("Sent the whole
 * file: the audio couldn't be separated on the phone.").
 */
internal class MediaAudioExtraction @Inject constructor() : AudioExtraction {
  override fun extract(input: ExtractorInput, target: File, onProgress: (Long) -> Unit): String? {
    val extractor = MediaExtractor()
    try {
      when (input) {
        is ExtractorInput.Descriptor -> extractor.setDataSource(input.descriptor, 0, input.length)
        is ExtractorInput.Path -> extractor.setDataSource(input.file.path)
      }
      val track = (0 until extractor.trackCount).firstOrNull { index ->
        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith(AUDIO) == true
      } ?: return null
      val format = extractor.getTrackFormat(track)
      val (container, extension) = when (format.getString(MediaFormat.KEY_MIME)) {
        MediaFormat.MIMETYPE_AUDIO_AAC -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 to "m4a"
        MediaFormat.MIMETYPE_AUDIO_OPUS -> MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG to "ogg"
        MediaFormat.MIMETYPE_AUDIO_VORBIS -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM to "webm"
        else -> return null
      }
      extractor.selectTrack(track)
      val written = remux(extractor, format, target, container, onProgress)
      return if (written > 0) extension else null
    } catch (expected: Exception) {
      target.delete()
      return null
    } finally {
      extractor.release()
    }
  }

  private fun remux(extractor: MediaExtractor, format: MediaFormat, target: File, container: Int, onProgress: (Long) -> Unit): Long {
    val muxer = MediaMuxer(target.path, container)
    var started = false
    var total = 0L
    try {
      val out = muxer.addTrack(format)
      muxer.start()
      started = true
      val capacity = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
        maxOf(format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), MIN_BUFFER_BYTES)
      } else {
        MIN_BUFFER_BYTES
      }
      val buffer = ByteBuffer.allocate(capacity)
      val info = MediaCodec.BufferInfo()
      while (true) {
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) break
        val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        info.set(0, size, extractor.sampleTime, flags)
        muxer.writeSampleData(out, buffer, info)
        total += size
        onProgress(total)
        extractor.advance()
      }
      if (total > 0) muxer.stop()
    } finally {
      runCatching { if (started && total == 0L) muxer.stop() }
      muxer.release()
    }
    return total
  }

  private companion object {
    const val AUDIO = "audio/"
    const val MIN_BUFFER_BYTES = 256 * 1024
  }
}
