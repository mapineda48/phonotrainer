package com.mapineda48.phonotrainer.core.common.hash

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** The SHA-256 of a byte stream and how many bytes it had. */
data class HashedBytes(val sha256: String, val bytes: Long)

/**
 * SHA-256 over streams of any length in a fixed buffer (architecture 2.2, 7.4, 8.3): verifying a downloaded artifact,
 * hashing a shared file while it is copied, the device key `K = sha256(token)` (server-contract 3.2). Hex digests are
 * lower case, as the computer reports them (server-contract 6.4).
 */
object Sha256 {
  /** Characters of a hex digest. */
  const val HEX_LENGTH: Int = 64

  /** The read buffer of every streaming function. */
  const val BUFFER_BYTES: Int = 64 * 1024

  private val HEX = Regex("^[0-9a-fA-F]{$HEX_LENGTH}$")

  /** The raw 32-byte digest of [bytes]. */
  fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance(ALGORITHM).digest(bytes)

  /** The lower-case hex digest of [bytes]. */
  fun hex(bytes: ByteArray): String = toHex(digest(bytes))

  /** Reads [input] to its end (it is not closed) and returns its digest and length; [onProgress] gets the total read. */
  fun of(input: InputStream, onProgress: (Long) -> Unit = {}): HashedBytes {
    val hasher = Sha256Hasher()
    hasher.update(input, onProgress)
    return hasher.result()
  }

  /** The digest and length of [file], read in [BUFFER_BYTES] steps. */
  fun of(file: File): HashedBytes = file.inputStream().use { of(it) }

  /**
   * Copies [input] to [output] and hashes what was copied in the same pass (neither stream is closed): the bytes that
   * really arrived are counted, whatever size the source declared (architecture 8.3).
   */
  fun copy(input: InputStream, output: OutputStream, onProgress: (Long) -> Unit = {}): HashedBytes {
    val hasher = Sha256Hasher()
    val buffer = ByteArray(BUFFER_BYTES)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      output.write(buffer, 0, read)
      hasher.update(buffer, 0, read)
      onProgress(hasher.bytes)
    }
    return hasher.result()
  }

  /** Lower-case hex of [bytes]. */
  fun toHex(bytes: ByteArray): String {
    val chars = CharArray(bytes.size * 2)
    bytes.forEachIndexed { index, byte ->
      val value = byte.toInt() and BYTE_MASK
      chars[index * 2] = DIGITS[value ushr NIBBLE]
      chars[index * 2 + 1] = DIGITS[value and NIBBLE_MASK]
    }
    return String(chars)
  }

  /** Whether [text] is a hex digest (either case). */
  fun isHex(text: String?): Boolean = text != null && HEX.matches(text)

  /** Whether two hex digests name the same bytes, ignoring case; compared in constant time. */
  fun sameDigest(a: String, b: String): Boolean =
    isHex(a) && isHex(b) && MessageDigest.isEqual(a.lowercase().toByteArray(), b.lowercase().toByteArray())

  internal const val ALGORITHM = "SHA-256"
  private const val DIGITS = "0123456789abcdef"
  private const val BYTE_MASK = 0xff
  private const val NIBBLE = 4
  private const val NIBBLE_MASK = 0x0f
}

/**
 * An incremental SHA-256 for a stream that arrives in parts: a resumed download hashes the part already on disk, then
 * every new byte, and needs no second pass over the file (architecture 7.4).
 */
class Sha256Hasher {
  private val digest = MessageDigest.getInstance(Sha256.ALGORITHM)

  /** Bytes hashed so far. */
  var bytes: Long = 0
    private set

  fun update(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
    digest.update(buffer, offset, length)
    bytes += length
  }

  /** Hashes the rest of [input] (it is not closed); [onProgress] gets the running total. */
  fun update(input: InputStream, onProgress: (Long) -> Unit = {}) {
    val buffer = ByteArray(Sha256.BUFFER_BYTES)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      update(buffer, 0, read)
      onProgress(bytes)
    }
  }

  /** The digest of everything hashed so far; the hasher starts again from empty afterwards. */
  fun result(): HashedBytes {
    val result = HashedBytes(Sha256.toHex(digest.digest()), bytes)
    bytes = 0
    return result
  }
}
