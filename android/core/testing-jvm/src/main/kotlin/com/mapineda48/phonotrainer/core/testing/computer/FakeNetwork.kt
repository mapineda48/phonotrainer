package com.mapineda48.phonotrainer.core.testing.computer

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.channels.SocketChannel
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ServerSocketFactory

/**
 * The fake computer's side of the network. `MockWebServer` reads a whole request body before its dispatcher sees
 * the request, which hides what the upload rules of server-contract 4.7 and 6.6 are about: a body that stops
 * arriving, a newer body for the same key, 30 s without a byte. So the fake hands `MockWebServer` this
 * [ServerSocketFactory]: every accepted socket is wrapped, the wrapper parses each request head as it streams by,
 * and upload bodies are reported to the [NetworkHost] byte by byte, can be stalled ([BodyWatch.stallAt]) and can be
 * aborted from another request (newest wins) or by idleness.
 *
 * The wrapper also implements reachability: while [NetworkHost.unreachable] is true no byte is delivered in either
 * direction, so a call gets no answer until its own timeout (the TCP handshake itself always completes on loopback;
 * the phone sees "no answer" either way).
 *
 * `MockWebServer` reads a connection's requests and calls the dispatcher on one thread per connection (HTTP/1.1),
 * so [current] tells the dispatcher which connection, and therefore which streamed body, a request came from.
 */
internal class FakeNetwork(private val host: NetworkHost) : ServerSocketFactory() {
  private val connections = ConcurrentHashMap.newKeySet<Connection>()

  override fun createServerSocket(): ServerSocket = TrackingServerSocket()

  override fun createServerSocket(port: Int): ServerSocket =
    TrackingServerSocket().apply { bind(InetSocketAddress(port)) }

  override fun createServerSocket(port: Int, backlog: Int): ServerSocket =
    TrackingServerSocket().apply { bind(InetSocketAddress(port), backlog) }

  override fun createServerSocket(port: Int, backlog: Int, ifAddress: InetAddress?): ServerSocket =
    TrackingServerSocket().apply { bind(InetSocketAddress(ifAddress, port), backlog) }

  /** Closes every connection (a restart, or the end of the test). */
  fun closeAll() {
    connections.forEach { it.abort() }
    connections.clear()
  }

  private inner class TrackingServerSocket : ServerSocket() {
    override fun accept(): Socket {
      val raw = super.accept()
      raw.soTimeout = POLL_MILLIS
      val connection = Connection(raw, host) { connections.remove(it) }
      connections += connection
      return ObservedSocket(raw, connection)
    }
  }

  companion object {
    /** The connection whose bytes the current thread last read: the request being dispatched (see class doc). */
    val current = ThreadLocal<Connection?>()

    /** How often a blocked read wakes up to look at the clock, aborts and reachability. */
    const val POLL_MILLIS = 20
  }
}

/**
 * Where a request came from: an accepted connection of the network layer, or `FakeComputerClient`'s in-memory
 * exchange. The computer aborts a peer to end an upload body (newest wins, idleness, a restart) or a channel.
 */
internal interface Peer {
  /** The source address, for the per-source rate limits. */
  val remoteAddress: String

  /** True for the in-memory exchange, which has no thread of its own to notice idleness (ComputerCore sweeps it). */
  val inMemory: Boolean

  fun abort()
}

/** What the network layer asks of the fake computer. Every method is called from a connection's own thread. */
internal interface NetworkHost {
  /** True while the computer must not answer (reachability `UNREACHABLE`). */
  val unreachable: Boolean

  /** A request head arrived; returns a watch when its body is an upload body the computer follows. */
  fun bodyStarting(peer: Peer, head: RequestHead): BodyWatch?

  /** Bytes of a watched body reached the computer. */
  fun bodyBytes(watch: BodyWatch, bytes: ByteArray, offset: Int, length: Int)

  /** The connection ended before the whole watched body arrived (connection lost). */
  fun bodyInterrupted(watch: BodyWatch)

  /** Called while a watched body waits for bytes; true when it has been idle long enough to be aborted. */
  fun abortIfIdle(watch: BodyWatch): Boolean
}

/** One request head as it arrived, with header names in lower case. */
internal class RequestHead(val method: String, val target: String, val headers: Map<String, String>) {
  val path: String get() = target.substringBefore('?')

  fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]

  val contentLength: Long? get() = header("content-length")?.trim()?.toLongOrNull()
}

/** An upload body being received; created by [NetworkHost.bodyStarting], guarded by the computer's lock. */
internal class BodyWatch(
  val peer: Peer,
  val head: RequestHead,
  val key: String,
  val deviceId: String?,
  val contentLength: Long,
  val admitted: Boolean,
  val refusedBusy: Boolean,
  val stallAt: Long?,
  startedAtMillis: Long,
) {
  var receivedBytes = 0L
  var lastByteAtMillis = startedAtMillis

  /** Every byte arrived; the request is now waiting for (or in) the dispatcher. */
  @Volatile var complete = false

  /** Dispatched, interrupted or aborted: the body no longer holds the device's upload slot. */
  var ended = false

  /** v1-complete: the bytes go straight into the session's part as they arrive (they stay "on disk"). */
  var streamsToPart = false

  /** The session's offset when the body started. */
  var startOffset = 0L
}

/** One accepted connection. */
internal class Connection(
  private val raw: Socket,
  private val host: NetworkHost,
  private val onClosed: (Connection) -> Unit,
) : Peer {
  @Volatile var aborted = false
    private set

  /** The watched body of the request most recently read on this connection, once it is complete. */
  @Volatile var completedBody: BodyWatch? = null

  /** The head of the request most recently read on this connection. */
  @Volatile var head: RequestHead? = null

  override val remoteAddress: String get() = raw.inetAddress?.hostAddress ?: "unknown"

  override val inMemory: Boolean get() = false

  override fun abort() {
    aborted = true
    closeRaw()
  }

  fun closeRaw() {
    try {
      raw.close()
    } catch (_: IOException) {
      // already closed
    }
    onClosed(this)
  }

  fun hostOf(): NetworkHost = host
}

/** A socket that delegates to the accepted one and routes its streams through the fake network. */
private class ObservedSocket(private val raw: Socket, private val connection: Connection) : Socket() {
  private val input = ObservedInputStream(raw, connection)
  private val output = ObservedOutputStream(raw, connection)
  private var logicalSoTimeout = 0

  override fun getInputStream(): InputStream = input

  override fun getOutputStream(): OutputStream = output

  override fun close() = connection.closeRaw()

  override fun isClosed(): Boolean = raw.isClosed

  override fun isConnected(): Boolean = raw.isConnected

  override fun isBound(): Boolean = raw.isBound

  override fun isInputShutdown(): Boolean = raw.isInputShutdown

  override fun isOutputShutdown(): Boolean = raw.isOutputShutdown

  override fun shutdownInput() = raw.shutdownInput()

  override fun shutdownOutput() = raw.shutdownOutput()

  override fun getInetAddress(): InetAddress? = raw.inetAddress

  override fun getPort(): Int = raw.port

  override fun getLocalAddress(): InetAddress = raw.localAddress

  override fun getLocalPort(): Int = raw.localPort

  override fun getRemoteSocketAddress(): SocketAddress? = raw.remoteSocketAddress

  override fun getLocalSocketAddress(): SocketAddress? = raw.localSocketAddress

  override fun getChannel(): SocketChannel? = null

  // The raw socket keeps a short read timeout so a blocked read can watch the clock; the caller's timeout is kept
  // for show only (MockWebServer sets none).
  override fun setSoTimeout(timeout: Int) {
    logicalSoTimeout = timeout
  }

  override fun getSoTimeout(): Int = logicalSoTimeout

  override fun setTcpNoDelay(on: Boolean) {
    raw.tcpNoDelay = on
  }

  override fun getTcpNoDelay(): Boolean = raw.tcpNoDelay

  override fun setKeepAlive(on: Boolean) {
    raw.keepAlive = on
  }

  override fun getKeepAlive(): Boolean = raw.keepAlive

  override fun setSoLinger(on: Boolean, linger: Int) = raw.setSoLinger(on, linger)

  override fun getSoLinger(): Int = raw.soLinger

  override fun setReceiveBufferSize(size: Int) {
    raw.receiveBufferSize = size
  }

  override fun getReceiveBufferSize(): Int = raw.receiveBufferSize

  override fun setSendBufferSize(size: Int) {
    raw.sendBufferSize = size
  }

  override fun getSendBufferSize(): Int = raw.sendBufferSize

  override fun toString(): String = "FakeComputerSocket(${raw.localPort})"
}

/**
 * The request side of a connection. A small state machine follows HTTP/1.1 request framing: a head up to the
 * blank line, then `Content-Length` body bytes, then the next head. Chunked bodies and upgraded connections
 * (WebSocket) are passed through untouched for the rest of the connection.
 */
private class ObservedInputStream(private val raw: Socket, private val connection: Connection) : InputStream() {
  private val host = connection.hostOf()
  private val source = raw.getInputStream()
  private val buffer = ByteArray(BUFFER_SIZE)
  private var start = 0
  private var end = 0
  private var eof = false

  private var state = State.HEAD
  private val headBytes = ByteArrayOutputStream()
  private var headTail = 0 // the last four head bytes, to find the blank line
  private var bodyRemaining = 0L
  private var watch: BodyWatch? = null

  override fun read(): Int {
    val one = ByteArray(1)
    val n = read(one, 0, 1)
    return if (n <= 0) -1 else one[0].toInt() and 0xff
  }

  override fun read(b: ByteArray, off: Int, len: Int): Int {
    FakeNetwork.current.set(connection)
    if (len == 0) return 0
    while (true) {
      if (connection.aborted) {
        interrupted()
        throw SocketException("closed by the fake computer")
      }
      if (host.unreachable) {
        pause()
        continue
      }
      val currentWatch = watch
      var allowed = len.toLong()
      if (state == State.BODY) {
        allowed = minOf(allowed, bodyRemaining)
        val stallAt = currentWatch?.stallAt
        if (currentWatch != null && stallAt != null) allowed = minOf(allowed, stallAt - currentWatch.receivedBytes)
      }
      if (allowed <= 0L) {
        // Stalled: the phone's body "stops arriving" until a newer body replaces it or idleness aborts it.
        if (currentWatch != null && host.abortIfIdle(currentWatch)) throw SocketException("idle upload aborted")
        pause()
        continue
      }
      if (start == end) {
        if (eof) return endOfStream()
        if (!fill() && state == State.BODY && currentWatch != null && host.abortIfIdle(currentWatch)) {
          throw SocketException("idle upload aborted")
        }
        continue
      }
      val n = deliver(b, off, minOf(allowed, (end - start).toLong()).toInt())
      if (n > 0) return n
    }
  }

  /** Reads what the client sent into the buffer; false when nothing arrived within the poll interval. */
  private fun fill(): Boolean = try {
    val n = source.read(buffer, 0, buffer.size)
    if (n < 0) {
      eof = true
    } else {
      start = 0
      end = n
    }
    true
  } catch (_: SocketTimeoutException) {
    false
  } catch (e: IOException) {
    interrupted()
    throw e
  }

  private fun endOfStream(): Int {
    interrupted()
    return -1
  }

  private fun interrupted() {
    val current = watch ?: return
    if (!current.complete) host.bodyInterrupted(current)
    watch = null
  }

  private fun deliver(b: ByteArray, off: Int, max: Int): Int = when (state) {
    State.PASSTHROUGH -> copy(b, off, max)
    State.BODY -> {
      val n = copy(b, off, max)
      bodyRemaining -= n
      watch?.let { host.bodyBytes(it, b, off, n) }
      if (bodyRemaining == 0L) {
        watch?.let {
          it.complete = true
          connection.completedBody = it
        }
        watch = null
        state = State.HEAD
      }
      n
    }
    State.HEAD -> deliverHead(b, off, max)
  }

  /** Delivers head bytes up to (and including) the blank line, then switches state. */
  private fun deliverHead(b: ByteArray, off: Int, max: Int): Int {
    var n = 0
    while (n < max) {
      val byte = buffer[start + n].toInt() and 0xff
      headBytes.write(byte)
      headTail = (headTail shl 8) or byte
      n++
      if (headBytes.size() > MAX_HEAD_BYTES) {
        System.arraycopy(buffer, start, b, off, n)
        start += n
        state = State.PASSTHROUGH
        return n
      }
      if (headTail == BLANK_LINE) {
        System.arraycopy(buffer, start, b, off, n)
        start += n
        onHead(parseHead(headBytes.toByteArray()))
        headBytes.reset()
        headTail = 0
        return n
      }
    }
    System.arraycopy(buffer, start, b, off, n)
    start += n
    return n
  }

  private fun onHead(head: RequestHead?) {
    if (head == null) {
      state = State.PASSTHROUGH
      return
    }
    connection.head = head
    connection.completedBody = null
    val chunked = head.header("transfer-encoding")?.lowercase(Locale.ROOT)?.contains("chunked") == true
    val upgrade = head.header("upgrade") != null
    val length = head.contentLength
    state = when {
      upgrade || chunked -> State.PASSTHROUGH
      length != null && length > 0 -> {
        bodyRemaining = length
        watch = host.bodyStarting(connection, head)
        State.BODY
      }
      else -> State.HEAD
    }
  }

  private fun copy(b: ByteArray, off: Int, n: Int): Int {
    System.arraycopy(buffer, start, b, off, n)
    start += n
    return n
  }

  private fun pause() {
    try {
      Thread.sleep(FakeNetwork.POLL_MILLIS.toLong())
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw SocketException("interrupted").apply { initCause(e) }
    }
    if (raw.isClosed) throw SocketException("closed")
  }

  override fun available(): Int = if (host.unreachable) 0 else end - start

  override fun close() = connection.closeRaw()

  private enum class State { HEAD, BODY, PASSTHROUGH }

  private companion object {
    const val BUFFER_SIZE = 16 * 1024
    const val MAX_HEAD_BYTES = 64 * 1024
    const val BLANK_LINE = 0x0d0a0d0a // "\r\n\r\n"

    fun parseHead(bytes: ByteArray): RequestHead? {
      val lines = String(bytes, Charsets.ISO_8859_1).split("\r\n")
      val requestLine = lines.firstOrNull()?.split(' ') ?: return null
      if (requestLine.size < 3) return null
      val headers = LinkedHashMap<String, String>()
      for (line in lines.drop(1)) {
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        headers.putIfAbsent(line.substring(0, colon).trim().lowercase(Locale.ROOT), line.substring(colon + 1).trim())
      }
      return RequestHead(requestLine[0], requestLine[1], headers)
    }
  }
}

/** The response side: passed through, except that nothing leaves while the computer is unreachable. */
private class ObservedOutputStream(private val raw: Socket, private val connection: Connection) : OutputStream() {
  private val host = connection.hostOf()
  private val sink = raw.getOutputStream()

  override fun write(b: Int) {
    awaitReachable()
    sink.write(b)
  }

  override fun write(b: ByteArray, off: Int, len: Int) {
    awaitReachable()
    sink.write(b, off, len)
  }

  override fun flush() {
    awaitReachable()
    sink.flush()
  }

  override fun close() = connection.closeRaw()

  private fun awaitReachable() {
    while (host.unreachable) {
      if (connection.aborted || raw.isClosed) throw SocketException("closed")
      try {
        Thread.sleep(FakeNetwork.POLL_MILLIS.toLong())
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw SocketException("interrupted").apply { initCause(e) }
      }
    }
    if (connection.aborted) throw SocketException("closed by the fake computer")
  }
}
