package com.mapineda48.phonotrainer.core.network.channel

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.network.support.RawPeer
import com.mapineda48.phonotrainer.core.network.support.RawPeer.Companion.CONTINUATION
import com.mapineda48.phonotrainer.core.network.support.RawPeer.Companion.FIN
import com.mapineda48.phonotrainer.core.network.support.RawPeer.Companion.TEXT
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The job channel's socket input (architecture 5.4 and 5.5): it reads the HTTP answer as OkHttp reads it, then adds up
 * each message's length from its frame headers and fails before OkHttp is handed a header that would take the message
 * over the bound. The bytes themselves pass unchanged.
 */
class ChannelInputTest {
  @Test
  fun bytesPassUnchangedAndAMessageAtTheBoundIsAccepted() {
    for (length in listOf(BOUND_7, BOUND_16, BOUND_64)) {
      val wire = ACCEPTED + frame(FIN or TEXT, length) + frame(FIN or PING, 4)
      assertThat(readAll(ChannelInput(ByteArrayInputStream(wire), length))).isEqualTo(wire)
    }
  }

  @Test
  fun aMessageOneByteOverTheBoundFailsBeforeItsHeaderIsHandedOn() {
    val input = ChannelInput(ByteArrayInputStream(ACCEPTED + frame(FIN or TEXT, BOUND_16 + 1)), BOUND_16)
    val head = ByteArray(ACCEPTED.size)
    assertThat(input.read(head)).isEqualTo(ACCEPTED.size)

    val failure = assertThrows(ChannelLostException::class.java) { input.read(ByteArray(BUFFER)) }

    assertThat(failure.message).contains("larger than $BOUND_16 bytes")
    // The stream stays failed.
    assertThrows(ChannelLostException::class.java) { input.read() }
  }

  @Test
  fun aMessageSplitIntoFramesIsCountedAsAWholeAcrossControlFrames() {
    val half = BOUND_16 / 2
    val atBound = ACCEPTED + frame(TEXT, half) + frame(FIN or PING, 2) + frame(FIN or CONTINUATION, BOUND_16 - half)
    assertThat(readAll(ChannelInput(ByteArrayInputStream(atBound), BOUND_16))).isEqualTo(atBound)

    val over = ACCEPTED + frame(TEXT, half) + frame(FIN or PING, 2) + frame(FIN or CONTINUATION, BOUND_16 - half + 1)
    assertThrows(ChannelLostException::class.java) { readAll(ChannelInput(ByteArrayInputStream(over), BOUND_16)) }

    // A new message starts the count again.
    val two = ACCEPTED + frame(FIN or TEXT, BOUND_16) + frame(FIN or TEXT, BOUND_16)
    assertThat(readAll(ChannelInput(ByteArrayInputStream(two), BOUND_16))).isEqualTo(two)
  }

  @Test
  fun maskedFramesAndSixtyFourBitLengthsAreReadAsTheyAre() {
    val masked = ACCEPTED + frame(FIN or TEXT, BOUND_7, masked = true) + frame(FIN or TEXT, 3)
    assertThat(readAll(ChannelInput(ByteArrayInputStream(masked), BOUND_7))).isEqualTo(masked)

    val negative = ACCEPTED + byteArrayOf((FIN or TEXT).toByte(), 127, 0x80.toByte(), 0, 0, 0, 0, 0, 0, 1)
    assertThrows(ChannelLostException::class.java) { readAll(ChannelInput(ByteArrayInputStream(negative), BOUND_16)) }
  }

  @Test
  fun aControlFrameLongerThanTheProtocolAllowsFails() {
    val wire = ACCEPTED + frame(FIN or PING, 126)
    assertThrows(ChannelLostException::class.java) { readAll(ChannelInput(ByteArrayInputStream(wire), BOUND_16)) }
  }

  @Test
  fun informationalAnswersBeforeTheSwitchAreReadOverAsOkHttpDoes() {
    for (before in listOf("HTTP/1.1 100 Continue\r\n\r\n", "HTTP/1.1 103 Early Hints\r\nLink: </a>\r\n\r\n")) {
      val wire = before.toByteArray(Charsets.ISO_8859_1) + ACCEPTED + frame(FIN or TEXT, BOUND_16 + 1)
      assertThrows(ChannelLostException::class.java) { readAll(ChannelInput(ByteArrayInputStream(wire), BOUND_16)) }
    }
  }

  @Test
  fun aRefusedUpgradePassesItsBodyUnwatched() {
    // A body whose bytes would read as a frame header far over the bound.
    val body = byteArrayOf((FIN or TEXT).toByte(), 127, 0x7F, -1, -1, -1, -1, -1, -1, -1)
    val wire = "HTTP/1.1 403 Forbidden\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.ISO_8859_1) + body
    assertThat(readAll(ChannelInput(ByteArrayInputStream(wire), BOUND_16))).isEqualTo(wire)
  }

  @Test
  fun headerLinesEndAsOkHttpEndsThem() {
    // Bare LF line ends are accepted, and a line that is only CR before CRLF is not the end of the headers.
    val bareLf = "HTTP/1.1 101 Switching Protocols\nUpgrade: websocket\n\n".toByteArray(Charsets.ISO_8859_1)
    assertThrows(ChannelLostException::class.java) {
      readAll(ChannelInput(ByteArrayInputStream(bareLf + frame(FIN or TEXT, BOUND_16 + 1)), BOUND_16))
    }
    val crLine = "HTTP/1.1 101 Switching Protocols\r\n\r\r\nX: y\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    assertThrows(ChannelLostException::class.java) {
      readAll(ChannelInput(ByteArrayInputStream(crLine + frame(FIN or TEXT, BOUND_16 + 1)), BOUND_16))
    }
  }

  @Test
  fun aStatusLineThisParserCannotReadIsWatched() {
    val odd = "HTTP/1.1 1x1 Odd\r\n\r\n".toByteArray(Charsets.ISO_8859_1) + frame(FIN or TEXT, BOUND_16 + 1)
    assertThrows(ChannelLostException::class.java) { readAll(ChannelInput(ByteArrayInputStream(odd), BOUND_16)) }
  }

  @Test
  fun singleBytesAndSkipsAreWatchedToo() {
    val wire = ACCEPTED + frame(FIN or TEXT, BOUND_16 + 1)
    val oneByOne = ChannelInput(ByteArrayInputStream(wire), BOUND_16)
    assertThrows(ChannelLostException::class.java) {
      do {
        val byte = oneByOne.read()
      } while (byte >= 0)
    }

    val skipping = ChannelInput(ByteArrayInputStream(wire), BOUND_16)
    assertThrows(ChannelLostException::class.java) {
      do {
        val skipped = skipping.skip(3)
      } while (skipped > 0)
    }
  }

  private fun readAll(input: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER)
    while (true) {
      val count = input.read(buffer)
      if (count < 0) return out.toByteArray()
      out.write(buffer, 0, count)
    }
  }

  /** A frame header for [length] followed by [length] payload bytes (and a mask key first when [masked]). */
  private fun frame(first: Int, length: Long, masked: Boolean = false): ByteArray {
    val header = RawPeer.frameHeader(first, length)
    if (masked) header[1] = (header[1].toInt() or MASK).toByte()
    val mask = if (masked) byteArrayOf(1, 2, 3, 4) else ByteArray(0)
    return header + mask + ByteArray(length.toInt()) { 'x'.code.toByte() }
  }

  private companion object {
    const val PING = 0x9
    const val MASK = 0x80
    const val BUFFER = 997
    const val BOUND_7 = 100L
    const val BOUND_16 = 4_000L
    const val BOUND_64 = 70_000L
    val ACCEPTED = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"
      .toByteArray(Charsets.ISO_8859_1)
  }
}
