package com.mapineda48.phonotrainer.core.network.support

import com.mapineda48.phonotrainer.core.domain.model.HostPort
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A TCP proxy in front of a computer that loses one answer on its way back to the phone: it forwards each HTTP/1.1
 * request of a connection upstream and the answer back, except for the [nth] request whose path starts with
 * [pathPrefix]. That one is forwarded and its whole answer read, then the phone's connection is reset instead, as
 * when Wi-Fi drops just after the computer answered. The PT1 nonce of every matching request is recorded in [nonces].
 * Answers must carry `Content-Length` (the server's JSON and file answers do).
 */
internal class LostAnswerProxy(
  private val upstream: HostPort,
  private val pathPrefix: String,
  private val nth: Int,
) : AutoCloseable {
  private val server = ServerSocket(0, BACKLOG, InetAddress.getByName(upstream.host))
  private val matching = AtomicInteger()

  /** Where the phone reaches the computer through the proxy. */
  val address = HostPort(upstream.host, server.localPort)

  /** The nonce of each request whose path matched, in order (`-` when it carried none). */
  val nonces = CopyOnWriteArrayList<String>()

  init {
    thread(isDaemon = true, name = "lost-answer-proxy") {
      while (true) {
        val phone = runCatching { server.accept() }.getOrNull() ?: break
        thread(isDaemon = true, name = "lost-answer-proxy-connection") { serve(phone) }
      }
    }
  }

  private fun serve(phone: Socket) {
    runCatching {
      phone.use {
        Socket(upstream.host, upstream.port).use { computer ->
          val fromPhone = phone.getInputStream().buffered()
          val fromComputer = computer.getInputStream().buffered()
          while (true) {
            val request = readMessage(fromPhone) ?: return
            val head = request.toString(Charsets.ISO_8859_1)
            val path = head.substringBefore("\r\n").split(' ').getOrNull(1).orEmpty()
            val drop = path.startsWith(pathPrefix) && run {
              nonces += NONCE.find(head)?.groupValues?.get(1) ?: "-"
              matching.incrementAndGet() == nth
            }
            computer.getOutputStream().apply { write(request); flush() }
            val answer = readMessage(fromComputer) ?: return
            if (drop) {
              phone.setSoLinger(true, 0) // a reset, not an orderly close
              return
            }
            phone.getOutputStream().apply { write(answer); flush() }
          }
        }
      }
    }
  }

  /** One HTTP/1.1 message: its head up to the blank line and a body of `Content-Length` bytes; null at the end. */
  private fun readMessage(input: InputStream): ByteArray? {
    val message = ByteArrayOutputStream()
    var matched = 0
    while (matched < HEAD_END.size) {
      val next = input.read()
      if (next < 0) return null
      message.write(next)
      matched = if (next.toByte() == HEAD_END[matched]) matched + 1 else if (next.toByte() == HEAD_END[0]) 1 else 0
    }
    val head = message.toString(Charsets.ISO_8859_1)
    val length = CONTENT_LENGTH.find(head)?.groupValues?.get(1)?.toInt() ?: 0
    message.write(input.readNBytes(length))
    return message.toByteArray()
  }

  override fun close() {
    server.close()
  }

  private companion object {
    const val BACKLOG = 16
    val HEAD_END = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    val CONTENT_LENGTH = Regex("(?im)^content-length:\\s*(\\d+)\\s*$")
    val NONCE = Regex("nonce=([0-9a-f]+)")
  }
}
