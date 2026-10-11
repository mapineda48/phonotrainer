package com.mapineda48.phonotrainer.core.common.hash

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Random
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SHA-256 over streams: the FIPS 180-2 vectors, streaming equals one shot at any read size, copy-and-hash. */
class Sha256Test {
  @get:Rule
  val folder = TemporaryFolder()

  @Test
  fun theStandardVectors() {
    assertThat(Sha256.hex(ByteArray(0)))
      .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    assertThat(Sha256.hex("abc".toByteArray()))
      .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    assertThat(Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".toByteArray()))
      .isEqualTo("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1")
  }

  @Test
  fun aMillionBytesStreamedInSmallReads() {
    // One million 'a' (FIPS 180-2 appendix B.3), handed over 7 bytes at a time so the buffer refills many times.
    val million = ByteArray(1_000_000) { 'a'.code.toByte() }
    val result = Sha256.of(SmallReads(million.inputStream(), 7))

    assertThat(result.sha256).isEqualTo("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0")
    assertThat(result.bytes).isEqualTo(1_000_000L)
  }

  @Test
  fun streamingEqualsOneShotAtEveryReadSize() {
    val data = ByteArray(3 * Sha256.BUFFER_BYTES + 17).also { Random(7).nextBytes(it) }
    val expected = Sha256.hex(data)

    listOf(1, 63, 64, 65, Sha256.BUFFER_BYTES - 1, Sha256.BUFFER_BYTES, Sha256.BUFFER_BYTES + 1, data.size)
      .forEach { size -> assertThat(Sha256.of(SmallReads(data.inputStream(), size)).sha256).isEqualTo(expected) }
  }

  @Test
  fun aFileIsHashedAsItsBytes() {
    val data = ByteArray(200_003).also { Random(3).nextBytes(it) }
    val file = File(folder.root, "source.mp4").apply { writeBytes(data) }

    assertThat(Sha256.of(file)).isEqualTo(HashedBytes(Sha256.hex(data), data.size.toLong()))
  }

  @Test
  fun aResumedStreamHashesThePartThenTheRest() {
    val data = ByteArray(150_000).also { Random(5).nextBytes(it) }
    val hasher = Sha256Hasher()
    hasher.update(data.copyOfRange(0, 70_000).inputStream())
    hasher.update(data, 70_000, 80_000)

    assertThat(hasher.result()).isEqualTo(HashedBytes(Sha256.hex(data), 150_000L))
    // The hasher starts again from empty.
    assertThat(hasher.result()).isEqualTo(HashedBytes(Sha256.hex(ByteArray(0)), 0L))
  }

  @Test
  fun copyWritesEveryByteAndHashesWhatArrived() {
    val data = ByteArray(Sha256.BUFFER_BYTES * 2 + 5).also { Random(11).nextBytes(it) }
    val out = ByteArrayOutputStream()
    val progress = mutableListOf<Long>()

    val result = Sha256.copy(SmallReads(data.inputStream(), 10_000), out) { progress += it }

    assertThat(out.toByteArray()).isEqualTo(data)
    assertThat(result).isEqualTo(HashedBytes(Sha256.hex(data), data.size.toLong()))
    assertThat(progress).isInOrder()
    assertThat(progress.last()).isEqualTo(data.size.toLong())
  }

  @Test
  fun hexHelpers() {
    assertThat(Sha256.toHex(byteArrayOf(0, 15, 16, -1))).isEqualTo("000f10ff")
    assertThat(Sha256.isHex("a".repeat(64))).isTrue()
    assertThat(Sha256.isHex("A".repeat(64))).isTrue()
    assertThat(Sha256.isHex("a".repeat(63))).isFalse()
    assertThat(Sha256.isHex("g".repeat(64))).isFalse()
    assertThat(Sha256.isHex(null)).isFalse()
    assertThat(Sha256.sameDigest("AB".repeat(32), "ab".repeat(32))).isTrue()
    assertThat(Sha256.sameDigest("ab".repeat(32), "ac".repeat(32))).isFalse()
    assertThat(Sha256.sameDigest("ab", "ab")).isFalse()
  }

  /** Hands over at most [size] bytes per read, like a slow socket or a content provider. */
  private class SmallReads(input: InputStream, private val size: Int) : FilterInputStream(input) {
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, size))
  }
}
