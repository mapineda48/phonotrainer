package com.mapineda48.phonotrainer.core.testing.synthetic

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.core.graphics.get
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Media made on the device for instrumented tests (architecture 21.4): no media file is ever committed. WAVs are
 * written directly; AAC audio and H.264 (or AV1) video are encoded with the platform's `MediaCodec` and muxed into
 * MP4 with `MediaMuxer`, so the files are as real as a computer's.
 *
 * Every video frame carries its own index as a [FrameCode], so a test that decodes what the player rendered knows
 * exactly which moment of the video is on screen. A video may carry its own audio track with a different tone from
 * the analysed WAV (the audio override of architecture 11.1); its start offset is not in the file but in the clip's
 * `ClipMedia.videoOffsetMs`.
 */
object SyntheticMedia {
  /** The facts of a synthetic video. */
  data class VideoSpec(
    val width: Int = 320,
    val height: Int = 240,
    val fps: Int = 30,
    val durationMs: Long = 6_000,
    /** Seconds between key frames; a long interval puts most short starts far from a key frame. */
    val keyframeIntervalS: Int = 1,
    /** The video's own audio (AAC), a tone of this frequency; null for none. */
    val audioToneHz: Double? = null,
    val mime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    val bitRate: Int = 1_000_000,
  ) {
    val frames: Int get() = (durationMs * fps / MS_PER_S).toInt()

    /** The time in the video of frame [index], in ms. */
    fun frameTimeMs(index: Int): Double = index * MS_PER_S.toDouble() / fps
  }

  /**
   * A 16-bit PCM mono WAV of [durationMs]: a sine of [toneHz] at [amplitude] (0..1), sounding everywhere, or only
   * inside [bursts] (ranges in ms) with silence elsewhere.
   */
  fun wav(
    file: File,
    durationMs: Long,
    toneHz: Double = 440.0,
    sampleRate: Int = 16_000,
    amplitude: Double = 0.5,
    bursts: List<LongRange>? = null,
  ): File {
    val samples = (durationMs * sampleRate / MS_PER_S).toInt()
    val pcm = ShortArray(samples) { i ->
      val ms = i * MS_PER_S / sampleRate
      val on = bursts == null || bursts.any { ms in it }
      if (on) (sin(2 * PI * toneHz * i / sampleRate) * amplitude * Short.MAX_VALUE).roundToInt().toShort() else 0
    }
    writeWav(file, pcm, sampleRate)
    return file
  }

  /** Whether this device can encode [mime] (the emulator encodes H.264; AV1 only on newer images). */
  fun canEncode(mime: String, width: Int = 320, height: Int = 240): Boolean = encoderFor(videoFormat(mime, width, height, 30, 1, 1_000_000)) != null

  /** An MP4 with a [spec] video whose frames carry their index, plus an AAC track when [VideoSpec.audioToneHz] is set. */
  fun video(file: File, spec: VideoSpec = VideoSpec()): VideoSpec {
    val video = encodeVideo(spec)
    val audio = spec.audioToneHz?.let { encodeAac(spec.durationMs, it) }
    file.delete()
    val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    try {
      val videoTrack = muxer.addTrack(video.format)
      val audioTrack = audio?.let { muxer.addTrack(it.format) }
      muxer.start()
      // Each track's samples stay in the encoder's output order (decode order: a frame may be presented after the
      // next one), interleaved by decode time: the video's is its place in that order.
      val frameUs = MICROS_PER_S / spec.fps
      val samples = video.samples.mapIndexed { i, sample -> Triple(videoTrack, sample, i * frameUs) } +
        audio?.samples.orEmpty().map { Triple(audioTrack!!, it, it.ptsUs) }
      for ((track, sample) in samples.sortedBy { it.third }.map { it.first to it.second }) {
        val info = MediaCodec.BufferInfo().apply { set(0, sample.data.size, sample.ptsUs, sample.flags) }
        muxer.writeSampleData(track, ByteBuffer.wrap(sample.data), info)
      }
      muxer.stop()
    } finally {
      muxer.release()
    }
    return spec
  }

  /** An audio-only MP4 (AAC-LC) of a tone. */
  fun aac(file: File, durationMs: Long, toneHz: Double): File {
    val audio = encodeAac(durationMs, toneHz)
    file.delete()
    val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    try {
      val track = muxer.addTrack(audio.format)
      muxer.start()
      for (sample in audio.samples) {
        val info = MediaCodec.BufferInfo().apply { set(0, sample.data.size, sample.ptsUs, sample.flags) }
        muxer.writeSampleData(track, ByteBuffer.wrap(sample.data), info)
      }
      muxer.stop()
    } finally {
      muxer.release()
    }
    return file
  }

  private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)

  private class Encoded(val format: MediaFormat, val samples: List<Sample>)

  private fun videoFormat(mime: String, width: Int, height: Int, fps: Int, keyframeS: Int, bitRate: Int) =
    MediaFormat.createVideoFormat(mime, width, height).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
      setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
      setInteger(MediaFormat.KEY_FRAME_RATE, fps)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeS)
    }

  private fun encoderFor(format: MediaFormat): String? =
    MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)

  private fun encodeVideo(spec: VideoSpec): Encoded {
    val format = videoFormat(spec.mime, spec.width, spec.height, spec.fps, spec.keyframeIntervalS, spec.bitRate)
    val name = requireNotNull(encoderFor(format)) { "no ${spec.mime} encoder on this device" }
    val codec = MediaCodec.createByCodecName(name)
    try {
      codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      codec.start()
      var next = 0
      return drain(codec) {
        if (next > spec.frames) return@drain false
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return@drain true
        val pts = (next * MICROS_PER_S / spec.fps)
        if (next == spec.frames) {
          codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        } else {
          val image = requireNotNull(codec.getInputImage(index))
          FrameCode.paint(image, next)
          codec.queueInputBuffer(index, 0, spec.width * spec.height * YUV_BYTES_NUM / YUV_BYTES_DEN, pts, 0)
        }
        next++
        true
      }
    } finally {
      codec.release()
    }
  }

  private fun encodeAac(durationMs: Long, toneHz: Double): Encoded {
    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AAC_RATE, 1).apply {
      setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
      setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)
    }
    val codec = MediaCodec.createByCodecName(requireNotNull(encoderFor(format)) { "no AAC encoder on this device" })
    try {
      codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      codec.start()
      val total = (durationMs * AAC_RATE / MS_PER_S).toInt()
      var written = 0
      var done = false
      return drain(codec) {
        if (done) return@drain false
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return@drain true
        val pts = written * MICROS_PER_S / AAC_RATE
        if (written >= total) {
          codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
          done = true
          return@drain true
        }
        val buffer = requireNotNull(codec.getInputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
        val count = minOf(AAC_CHUNK, total - written, buffer.remaining() / 2)
        for (i in 0 until count) {
          val n = written + i
          buffer.putShort((sin(2 * PI * toneHz * n / AAC_RATE) * AMPLITUDE * Short.MAX_VALUE).roundToInt().toShort())
        }
        codec.queueInputBuffer(index, 0, count * 2, pts, 0)
        written += count
        true
      }
    } finally {
      codec.release()
    }
  }

  /** Feeds the encoder with [feed] (false when everything is queued) and collects its output until end of stream. */
  private inline fun drain(codec: MediaCodec, feed: () -> Boolean): Encoded {
    val samples = ArrayList<Sample>()
    var format: MediaFormat? = null
    val info = MediaCodec.BufferInfo()
    var feeding = true
    while (true) {
      if (feeding) feeding = feed()
      val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
      when {
        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> format = codec.outputFormat
        index >= 0 -> {
          val buffer = requireNotNull(codec.getOutputBuffer(index))
          if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
            val bytes = ByteArray(info.size)
            buffer.position(info.offset)
            buffer.get(bytes)
            samples += Sample(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
          }
          codec.releaseOutputBuffer(index, false)
          if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
      }
    }
    return Encoded(requireNotNull(format) { "the encoder never gave its format" }, samples)
  }

  private fun writeWav(file: File, pcm: ShortArray, sampleRate: Int) {
    val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    pcm.forEach { data.putShort(it) }
    val header = ByteBuffer.allocate(WAV_HEADER).order(ByteOrder.LITTLE_ENDIAN).apply {
      put("RIFF".toByteArray())
      putInt(WAV_HEADER - 8 + data.capacity())
      put("WAVE".toByteArray())
      put("fmt ".toByteArray())
      putInt(16)
      putShort(1)
      putShort(1)
      putInt(sampleRate)
      putInt(sampleRate * 2)
      putShort(2)
      putShort(16)
      put("data".toByteArray())
      putInt(data.capacity())
    }
    file.delete()
    RandomAccessFile(file, "rw").use { out ->
      out.write(header.array())
      out.write(data.array())
    }
  }

  private const val MS_PER_S = 1000L
  private const val MICROS_PER_S = 1_000_000L
  private const val TIMEOUT_US = 10_000L
  private const val YUV_BYTES_NUM = 3
  private const val YUV_BYTES_DEN = 2
  private const val AAC_RATE = 44_100
  private const val AAC_BIT_RATE = 64_000
  private const val AAC_CHUNK = 1024
  private const val AMPLITUDE = 0.5
  private const val WAV_HEADER = 44

  /** The time in ms of the frame shown at [index], rounded. */
  fun frameTimeMs(spec: VideoSpec, index: Int): Long = spec.frameTimeMs(index).roundToLong()
}

/**
 * A frame index drawn as a 4 × 4 grid of black and white blocks (16 bits, most significant first, row by row) over a
 * grey frame, big enough to survive any encoder. [read] gets it back from a decoded `YUV_420_888` image.
 */
object FrameCode {
  private const val GRID = 4
  private const val BLACK: Byte = 16
  private const val WHITE: Byte = -21 // 235
  private const val GREY: Byte = -128 // 128
  private const val THRESHOLD = 128

  /** Draws [index] into an encoder's input [image] (`YUV_420_888`). */
  fun paint(image: Image, index: Int) {
    val width = image.width
    val height = image.height
    val y = image.planes[0]
    val row = ByteArray(width)
    for (line in 0 until height) {
      val gridRow = line * GRID / height
      for (x in 0 until width) {
        val bit = GRID * GRID - 1 - (gridRow * GRID + x * GRID / width)
        row[x] = if (index shr bit and 1 == 1) WHITE else BLACK
      }
      if (y.pixelStride == 1) {
        y.buffer.position(line * y.rowStride)
        y.buffer.put(row, 0, width)
      } else {
        for (x in 0 until width) y.buffer.put(line * y.rowStride + x * y.pixelStride, row[x])
      }
    }
    for (plane in 1..2) {
      val chroma = image.planes[plane]
      for (line in 0 until height / 2) {
        for (x in 0 until width / 2) chroma.buffer.put(line * chroma.rowStride + x * chroma.pixelStride, GREY)
      }
    }
  }

  /** The index drawn in a [bitmap] of the whole frame (a screen capture of the video), read by brightness. */
  fun read(bitmap: android.graphics.Bitmap): Int {
    var index = 0
    for (cell in 0 until GRID * GRID) {
      val cx = (cell % GRID * 2 + 1) * bitmap.width / (GRID * 2)
      val cy = (cell / GRID * 2 + 1) * bitmap.height / (GRID * 2)
      val pixel = bitmap[cx, cy]
      val brightness = ((pixel shr RED_SHIFT and 0xFF) + (pixel shr GREEN_SHIFT and 0xFF) + (pixel and 0xFF)) / 3
      index = index shl 1 or if (brightness >= THRESHOLD) 1 else 0
    }
    return index
  }

  private const val RED_SHIFT = 16
  private const val GREEN_SHIFT = 8

  /** The index drawn in a decoded [image] (`YUV_420_888`), read at the middle of each block. */
  fun read(image: Image): Int {
    val y = image.planes[0]
    var index = 0
    for (cell in 0 until GRID * GRID) {
      val cx = (cell % GRID * 2 + 1) * image.width / (GRID * 2)
      val cy = (cell / GRID * 2 + 1) * image.height / (GRID * 2)
      val luma = y.buffer.get(cy * y.rowStride + cx * y.pixelStride).toInt() and 0xFF
      index = index shl 1 or if (luma >= THRESHOLD) 1 else 0
    }
    return index
  }
}

/** Tone analysis on 16-bit PCM, for tests that check which audio was heard (Goertzel's algorithm). */
object ToneMeter {
  /** The relative power of [hz] in [samples] at [sampleRate]. */
  fun power(samples: ShortArray, sampleRate: Int, hz: Double): Double {
    val k = 2 * kotlin.math.cos(2 * PI * hz / sampleRate)
    var s1 = 0.0
    var s2 = 0.0
    for (sample in samples) {
      val s0 = sample / Short.MAX_VALUE.toDouble() + k * s1 - s2
      s2 = s1
      s1 = s0
    }
    return (s1 * s1 + s2 * s2 - k * s1 * s2) / samples.size.coerceAtLeast(1)
  }

  /** Which of [candidates] is the strongest tone in [samples]. */
  fun dominant(samples: ShortArray, sampleRate: Int, candidates: List<Double>): Double =
    candidates.maxBy { power(samples, sampleRate, it) }
}
