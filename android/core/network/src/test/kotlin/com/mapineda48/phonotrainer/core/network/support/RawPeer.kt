package com.mapineda48.phonotrainer.core.network.support

import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A one-connection peer on the loopback interface that writes exactly the bytes a test gives it, for answers neither
 * the fake computer nor MockWebServer can produce: a `101` to a plain request, a WebSocket acceptance that names an
 * extension, frames of any declared length. It reads the request head, then runs [script], counting in [written] every
 * byte that reached the socket; a failed write (the phone closed the connection) ends the script quietly.
 */
internal class RawPeer(private val script: Session.() -> Unit) : AutoCloseable {
  private val server = ServerSocket(0, 1, InetAddress.getByName(FakeComputer.HOST))
  private val done = CountDownLatch(1)

  val address = HostPort(FakeComputer.HOST, server.localPort)
  val written = AtomicLong()

  /** The request line and header lines the phone sent, as read. */
  @Volatile var request: List<String> = emptyList()
    private set

  /** Whether the phone closed the connection while or after the script ran ([Session.awaitPhoneClose]). */
  @Volatile var closedByPhone = false
    private set

  private val worker = thread(isDaemon = true, name = "raw-peer") {
    try {
      server.accept().use { socket ->
        request = readHead(socket.getInputStream())
        Session(socket).script()
      }
    } catch (_: IOException) {
      closedByPhone = true
    } finally {
      done.countDown()
    }
  }

  /** The value of the request header [name], if the phone sent it. */
  fun header(name: String): String? = request.drop(1)
    .firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
    ?.substringAfter(':')?.trim()

  /** Waits for the script to end; true if it did within [timeoutMs]. */
  fun awaitDone(timeoutMs: Long = WAIT_MS): Boolean = done.await(timeoutMs, TimeUnit.MILLISECONDS)

  override fun close() {
    worker.interrupt()
    server.close()
  }

  inner class Session(private val socket: Socket) {
    private val out: OutputStream = socket.getOutputStream()

    fun write(bytes: ByteArray) {
      out.write(bytes)
      out.flush()
      written.addAndGet(bytes.size.toLong())
    }

    fun writeText(text: String) = write(text.toByteArray(Charsets.ISO_8859_1))

    /** [total] bytes of spaces, written 64 KiB at a time. */
    fun stream(total: Long) {
      val chunk = ByteArray(CHUNK) { ' '.code.toByte() }
      var left = total
      while (left > 0) {
        val n = minOf(left, CHUNK.toLong()).toInt()
        out.write(chunk, 0, n)
        written.addAndGet(n.toLong())
        left -= n
      }
      out.flush()
    }

    /** Accepts the phone's WebSocket upgrade, with [extraHeaders] (each ending in CRLF) added to the answer. */
    fun acceptWebSocket(extraHeaders: String = "") {
      val key = header("Sec-WebSocket-Key").orEmpty()
      val accept = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).toByteArray(Charsets.ISO_8859_1)),
      )
      writeText(
        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
          "Sec-WebSocket-Accept: $accept\r\n$extraHeaders\r\n",
      )
    }

    /** Reads (and drops) what the phone sends until it closes the connection; false if it has not after [timeoutMs]. */
    fun awaitPhoneClose(timeoutMs: Long = WAIT_MS): Boolean {
      socket.soTimeout = timeoutMs.toInt()
      val input = socket.getInputStream()
      val buffer = ByteArray(CHUNK)
      return try {
        do {
          val count = input.read(buffer)
        } while (count >= 0)
        true
      } catch (_: SocketTimeoutException) {
        false
      } catch (_: IOException) {
        true
      }.also { closedByPhone = it }
    }
  }

  companion object {
    private const val CHUNK = 64 * 1024
    private const val WAIT_MS = 5_000L
    private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    const val TEXT = 0x1
    const val CONTINUATION = 0x0
    const val FIN = 0x80
    const val RSV1 = 0x40

    /** An unmasked server frame header (RFC 6455 5.2) for [first] (FIN, RSV and opcode bits) and [length]. */
    fun frameHeader(first: Int, length: Long): ByteArray {
      val header = ByteArrayOutputStream()
      header.write(first)
      when {
        length < 126 -> header.write(length.toInt())
        length <= 0xFFFF -> {
          header.write(126)
          header.write((length shr 8).toInt() and 0xFF)
          header.write(length.toInt() and 0xFF)
        }
        else -> {
          header.write(127)
          for (shift in 56 downTo 0 step 8) header.write((length shr shift).toInt() and 0xFF)
        }
      }
      return header.toByteArray()
    }

    private fun readHead(input: InputStream): List<String> {
      val head = ByteArrayOutputStream()
      var last4 = 0
      while (last4 != CRLF_CRLF) {
        val byte = input.read()
        if (byte < 0) break
        head.write(byte)
        last4 = (last4 shl 8) or byte
      }
      return head.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
    }

    private const val CRLF_CRLF = 0x0D0A0D0A
  }
}
