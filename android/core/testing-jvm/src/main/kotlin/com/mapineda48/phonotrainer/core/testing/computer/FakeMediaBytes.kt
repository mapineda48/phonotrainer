package com.mapineda48.phonotrainer.core.testing.computer

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

/**
 * Bytes that stand for media files: right signatures and sizes, not playable (except [wav], which is real silence).
 * The fake computer only needs them to be stored, listed, hashed and served; playable media comes from WP-11's
 * `SyntheticMedia` on a device.
 */
object FakeMediaBytes {
  /** An MP4-signed stand-in for a downloaded or phone-ready video. */
  fun video(size: Int = DEFAULT_SIZE, salt: Int = 0): ByteArray = signed("\u0000\u0000\u0000\u0018ftypmp42", size, salt)

  /** An Ogg-signed stand-in for an Opus rendition. */
  fun opus(size: Int = DEFAULT_SIZE / 2, salt: Int = 0): ByteArray = signed("OggS", size, salt)

  /** A JPEG-signed stand-in for a poster. */
  fun jpeg(size: Int = DEFAULT_SIZE / 4, salt: Int = 0): ByteArray =
    signed("ÿØÿà", size, salt).also {
      it[it.size - 2] = 0xff.toByte()
      it[it.size - 1] = 0xd9.toByte()
    }

  /** gzip of [bytes] (the compact clip data of server-contract 6.4.1 is gzip, never `Content-Encoding`). */
  fun gzip(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
  }

  /**
   * A real mono PCM16 WAV of [seconds] at [rate] Hz, like the server's `audio.wav`. [level] fills every sample (0 is
   * silence), so two tracks of one length still differ in their bytes and checksums.
   */
  fun wav(seconds: Double, rate: Int = 16_000, level: Short = 0): ByteArray {
    val samples = (rate * seconds).toInt()
    val dataBytes = samples * 2
    val buffer = ByteBuffer.allocate(WAV_HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(WAV_HEADER_BYTES - 8 + dataBytes)
    buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
    buffer.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(PCM_FORMAT_BYTES).putShort(1).putShort(1)
    buffer.putInt(rate).putInt(rate * 2).putShort(2).putShort(BITS_PER_SAMPLE)
    buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
    repeat(samples) { buffer.putShort(level) }
    return buffer.array()
  }

  private fun signed(signature: String, size: Int, salt: Int): ByteArray {
    val head = signature.toByteArray(Charsets.ISO_8859_1)
    return ByteArray(maxOf(size, head.size + 2)) { i -> if (i < head.size) head[i] else ((i * PRIME + salt) and 0xff).toByte() }
  }

  private const val DEFAULT_SIZE = 4_096
  private const val WAV_HEADER_BYTES = 44
  private const val PCM_FORMAT_BYTES = 16
  private const val BITS_PER_SAMPLE: Short = 16
  private const val PRIME = 31
}
