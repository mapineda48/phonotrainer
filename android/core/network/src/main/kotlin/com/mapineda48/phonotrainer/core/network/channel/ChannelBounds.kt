package com.mapineda48.phonotrainer.core.network.channel

import com.mapineda48.phonotrainer.core.network.http.Routes
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import okhttp3.Interceptor
import okhttp3.Response

/**
 * The job channel's upgrade, application half (architecture 5.4 and 5.5, security review M-3). OkHttp offers
 * `permessage-deflate` on every WebSocket upgrade and inflates each message without a limit, so a small compressed
 * frame could become a message of any size in memory. The offer is removed here (OkHttp refuses that header only on
 * the caller's own request), so the computer negotiates no compression; an accepted upgrade that names an extension
 * anyway is refused before OkHttp reads a frame, and the call is cancelled, which closes its socket. The length
 * [ChannelInput] reads on the wire is then the length of the message OkHttp holds.
 */
internal object ChannelUpgradeInterceptor : Interceptor {
  private const val EXTENSIONS = "Sec-WebSocket-Extensions"
  private const val SWITCHING_PROTOCOLS = 101

  override fun intercept(chain: Interceptor.Chain): Response {
    val response = chain.proceed(chain.request().newBuilder().removeHeader(EXTENSIONS).build())
    if (response.code == SWITCHING_PROTOCOLS && response.headers(EXTENSIONS).isNotEmpty()) {
      response.close()
      chain.call().cancel()
      throw ChannelLostException("The computer accepted the channel with an extension this app never offered.")
    }
    return response
  }
}

/**
 * The job channel's socket input, watched as OkHttp reads it (architecture 5.4 and 5.5, security review M-3): first the
 * HTTP answer to the upgrade, then, once the computer accepted it with `101`, the WebSocket frames (RFC 6455 5.2).
 * Each message's length is added up from its frame headers before OkHttp reads the payload, and a message longer than
 * [maxMessageBytes] fails the read with [ChannelLostException], so OkHttp never buffers it. Nothing is changed or held
 * back: the bytes reach OkHttp as they arrived. Informational answers before the `101` (100, 102 to 199) are read over,
 * as OkHttp does; any other status is a refused upgrade, whose body (capped by `BodyCapInterceptor`) passes unwatched,
 * and a status line this parser cannot read is watched like an accepted upgrade. Header lines are split as OkHttp
 * splits them (at LF, one trailing CR dropped) and the code is read where OkHttp reads it, so both agree on where the
 * frames start.
 */
internal class ChannelInput(input: InputStream, private val maxMessageBytes: Long) : FilterInputStream(input) {
  private enum class Phase { STATUS, HEADERS, FRAME_START, FRAME_LENGTH, EXTENDED_LENGTH, MASK, PAYLOAD, UNWATCHED }

  private var phase = Phase.STATUS
  private val statusLine = StringBuilder()
  private var lineBytes = 0
  private var lastWasCr = false
  private var statusCode = NO_STATUS
  private var opcode = 0
  private var masked = false
  private var lengthBytesLeft = 0
  private var frameLength = 0L
  private var maskBytesLeft = 0
  private var payloadLeft = 0L
  private var messageBytes = 0L
  private var failure: IOException? = null

  override fun read(): Int {
    failure?.let { throw it }
    val byte = super.read()
    if (byte >= 0) watch(byteArrayOf(byte.toByte()), 0, 1)
    return byte
  }

  override fun read(b: ByteArray, off: Int, len: Int): Int {
    failure?.let { throw it }
    val count = super.read(b, off, len)
    if (count > 0) watch(b, off, off + count)
    return count
  }

  /** Skipped bytes are read, so none escapes the watch. */
  override fun skip(n: Long): Long {
    if (n <= 0) return 0
    val count = read(ByteArray(minOf(n, SKIP_BYTES).toInt()))
    return if (count < 0) 0 else count.toLong()
  }

  override fun markSupported(): Boolean = false

  private fun watch(bytes: ByteArray, from: Int, until: Int) {
    var i = from
    while (i < until) {
      when (phase) {
        Phase.UNWATCHED -> return
        Phase.PAYLOAD -> {
          val step = minOf(payloadLeft, (until - i).toLong())
          payloadLeft -= step
          i += step.toInt()
          if (payloadLeft == 0L) phase = Phase.FRAME_START
        }
        else -> {
          next(bytes[i].toInt() and BYTE)
          i++
        }
      }
    }
  }

  private fun next(byte: Int) {
    when (phase) {
      Phase.STATUS, Phase.HEADERS -> headLine(byte)
      Phase.FRAME_START -> {
        opcode = byte and OPCODE
        phase = Phase.FRAME_LENGTH
      }
      Phase.FRAME_LENGTH -> {
        masked = byte and MASK_BIT != 0
        when (val length = byte and LENGTH_7) {
          LENGTH_16 -> startExtendedLength(2)
          LENGTH_64 -> startExtendedLength(8)
          else -> frameHeaderRead(length.toLong())
        }
      }
      Phase.EXTENDED_LENGTH -> {
        frameLength = (frameLength shl Byte.SIZE_BITS) or byte.toLong()
        if (--lengthBytesLeft == 0) frameHeaderRead(frameLength)
      }
      Phase.MASK -> if (--maskBytesLeft == 0) startPayload()
      Phase.PAYLOAD, Phase.UNWATCHED -> Unit
    }
  }

  /** One byte of the HTTP answer: a line ends at LF, with one trailing CR dropped, as OkHttp reads it. */
  private fun headLine(byte: Int) {
    if (byte != LF) {
      if (phase == Phase.STATUS && statusLine.length < STATUS_CHARS) statusLine.append(byte.toChar())
      lineBytes++
      lastWasCr = byte == CR
      return
    }
    val empty = lineBytes == 0 || (lineBytes == 1 && lastWasCr)
    lineBytes = 0
    lastWasCr = false
    if (phase == Phase.STATUS) {
      statusCode = statusCodeOf(statusLine.toString().removeSuffix("\r"))
      statusLine.setLength(0)
      phase = Phase.HEADERS
      return
    }
    if (!empty) return
    // Only an answer that is certainly not an accepted upgrade goes unwatched; one this parser cannot read is watched.
    phase = when (statusCode) {
      CONTINUE, in PROCESSING..LAST_INFORMATIONAL -> Phase.STATUS
      SWITCHING_PROTOCOLS, NO_STATUS -> Phase.FRAME_START
      else -> Phase.UNWATCHED
    }
  }

  private fun startExtendedLength(bytes: Int) {
    frameLength = 0
    lengthBytesLeft = bytes
    phase = Phase.EXTENDED_LENGTH
  }

  /** The frame's length is known, before any of its payload is read: the message it belongs to must stay bounded. */
  private fun frameHeaderRead(length: Long) {
    val control = opcode and CONTROL_BIT != 0
    if (control) {
      if (length !in 0..CONTROL_MAX) fail("A channel control frame is longer than $CONTROL_MAX bytes.")
    } else {
      val before = if (opcode == CONTINUATION) messageBytes else 0L
      if (length < 0 || length > maxMessageBytes - before) {
        fail("A channel message is larger than $maxMessageBytes bytes.")
      }
      messageBytes = before + length
    }
    payloadLeft = length
    maskBytesLeft = if (masked) MASK_KEY_BYTES else 0
    if (maskBytesLeft > 0) phase = Phase.MASK else startPayload()
  }

  private fun startPayload() {
    phase = if (payloadLeft > 0) Phase.PAYLOAD else Phase.FRAME_START
  }

  private fun fail(message: String): Nothing {
    val error = ChannelLostException(message)
    failure = error
    throw error
  }

  companion object {
    /** The largest channel message, the listing's cap (architecture 5.4): a snapshot is a lean listing. */
    const val MAX_MESSAGE_BYTES: Long = Routes.LISTING_CAP

    private const val NO_STATUS = -1
    private const val CONTINUE = 100
    private const val SWITCHING_PROTOCOLS = 101
    private const val PROCESSING = 102
    private const val LAST_INFORMATIONAL = 199
    private const val STATUS_CHARS = 32
    private const val LF = '\n'.code
    private const val CR = '\r'.code
    private const val BYTE = 0xFF
    private const val OPCODE = 0x0F
    private const val CONTROL_BIT = 0x08
    private const val CONTINUATION = 0x0
    private const val MASK_BIT = 0x80
    private const val LENGTH_7 = 0x7F
    private const val LENGTH_16 = 126
    private const val LENGTH_64 = 127
    private const val CONTROL_MAX = 125L
    private const val MASK_KEY_BYTES = 4
    private const val SKIP_BYTES = 8L * 1024L

    /**
     * The status code where OkHttp's status line parser reads it (three characters after `HTTP/1.x `, `ICY ` or
     * `SOURCETABLE `), or [NO_STATUS].
     */
    private fun statusCodeOf(line: String): Int {
      val start = when {
        line.startsWith("HTTP/1.") -> HTTP_CODE_START
        line.startsWith("ICY ") -> ICY_CODE_START
        line.startsWith("SOURCETABLE ") -> SOURCETABLE_CODE_START
        else -> return NO_STATUS
      }
      if (line.length < start + CODE_DIGITS) return NO_STATUS
      return line.substring(start, start + CODE_DIGITS).toIntOrNull() ?: NO_STATUS
    }

    private const val HTTP_CODE_START = 9
    private const val ICY_CODE_START = 4
    private const val SOURCETABLE_CODE_START = 12
    private const val CODE_DIGITS = 3
  }
}
