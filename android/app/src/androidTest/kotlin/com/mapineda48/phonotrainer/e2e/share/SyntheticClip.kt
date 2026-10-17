package com.mapineda48.phonotrainer.e2e.share

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin

/**
 * A small MP4 made on the device for the share tests: an H.264 track of grey frames and an AAC track of a 440 Hz tone,
 * encoded with the platform's encoders and written with `MediaMuxer`, so the queue can copy it whole or extract its
 * audio track. The same recipe as WP-09's device suite; WP-11's `SyntheticMedia` replaces both once it is merged.
 */
internal object SyntheticClip {
  private const val WIDTH = 320
  private const val HEIGHT = 240
  private const val FPS = 15
  private const val SAMPLE_RATE = 44_100
  private const val AAC_FRAME = 1_024
  private const val TIMEOUT_US = 10_000L
  private const val MICROS = 1_000_000L

  private class Sample(val bytes: ByteArray, val timeUs: Long, val flags: Int)

  /** Writes a [seconds]-long clip to [target] (once; an existing file is kept). */
  @Synchronized
  fun ensure(target: File, seconds: Int = 2): File {
    if (target.isFile && target.length() > 0) return target
    val part = File(target.parentFile, target.name + ".part")
    write(part, seconds)
    check(part.renameTo(target)) { "could not keep the synthetic clip" }
    return target
  }

  private fun write(target: File, seconds: Int) {
    val (videoFormat, video) = encodeVideo(seconds * FPS)
    val (audioFormat, audio) = encodeAudio(seconds * SAMPLE_RATE / AAC_FRAME)
    val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    try {
      val videoTrack = muxer.addTrack(videoFormat)
      val audioTrack = muxer.addTrack(audioFormat)
      muxer.start()
      val all = video.map { videoTrack to it } + audio.map { audioTrack to it }
      val info = MediaCodec.BufferInfo()
      all.sortedBy { it.second.timeUs }.forEach { (track, sample) ->
        info.set(0, sample.bytes.size, sample.timeUs, sample.flags)
        muxer.writeSampleData(track, ByteBuffer.wrap(sample.bytes), info)
      }
      muxer.stop()
    } finally {
      muxer.release()
    }
  }

  private fun encodeVideo(frames: Int): Pair<MediaFormat, List<Sample>> {
    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
      setInteger(MediaFormat.KEY_BIT_RATE, 300_000)
      setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }
    return encode(MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC), format, frames) { codec, index, frame ->
      val image = checkNotNull(codec.getInputImage(index))
      image.planes.forEachIndexed { plane, data ->
        val value = (if (plane == 0) 40 + frame * 4 else 128).toByte()
        val width = if (plane == 0) WIDTH else WIDTH / 2
        val height = if (plane == 0) HEIGHT else HEIGHT / 2
        for (row in 0 until height) {
          for (column in 0 until width) data.buffer.put(row * data.rowStride + column * data.pixelStride, value)
        }
      }
      (WIDTH * HEIGHT * 3 / 2) to frame * MICROS / FPS
    }
  }

  private fun encodeAudio(frames: Int): Pair<MediaFormat, List<Sample>> {
    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
      setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
      setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
    }
    return encode(MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC), format, frames) { codec, index, frame ->
      val buffer = checkNotNull(codec.getInputBuffer(index))
      buffer.clear()
      for (n in 0 until AAC_FRAME) {
        val t = (frame * AAC_FRAME + n).toDouble() / SAMPLE_RATE
        val value = (sin(2 * PI * 440 * t) * 8_000).toInt()
        buffer.put((value and 0xff).toByte())
        buffer.put((value shr 8 and 0xff).toByte())
      }
      (AAC_FRAME * 2) to frame.toLong() * AAC_FRAME * MICROS / SAMPLE_RATE
    }
  }

  /** Runs [codec] over [frames] inputs that [fill] writes (returning their size and time); the output samples. */
  private fun encode(
    codec: MediaCodec,
    format: MediaFormat,
    frames: Int,
    fill: (MediaCodec, Int, Int) -> Pair<Int, Long>,
  ): Pair<MediaFormat, List<Sample>> {
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    codec.start()
    val samples = mutableListOf<Sample>()
    var output: MediaFormat? = null
    var frame = 0
    var inputDone = false
    val info = MediaCodec.BufferInfo()
    try {
      while (true) {
        if (!inputDone) {
          val index = codec.dequeueInputBuffer(TIMEOUT_US)
          if (index >= 0) {
            if (frame < frames) {
              val (size, timeUs) = fill(codec, index, frame)
              codec.queueInputBuffer(index, 0, size, timeUs, 0)
              frame++
            } else {
              codec.queueInputBuffer(index, 0, 0, frame * MICROS / FPS, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              inputDone = true
            }
          }
        }
        val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
        when {
          out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> output = codec.outputFormat
          out >= 0 -> {
            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (!config && info.size > 0) {
              val buffer = checkNotNull(codec.getOutputBuffer(out))
              val bytes = ByteArray(info.size)
              buffer.position(info.offset)
              buffer.get(bytes)
              samples += Sample(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
            }
            codec.releaseOutputBuffer(out, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
          }
        }
      }
    } finally {
      codec.stop()
      codec.release()
    }
    return checkNotNull(output) to samples
  }
}
