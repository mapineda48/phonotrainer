package com.mapineda48.phonotrainer.core.testing.computer

import com.mapineda48.phonotrainer.core.domain.model.HostPort
import kotlinx.coroutines.delay
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.Sink
import okio.Source
import java.util.Locale

/** One request as [FakeComputerClient] sends it. */
internal class Outgoing(val method: String, val url: HttpUrl, val headers: Headers, val body: ByteArray? = null) {
  /** The request target as sent: path and query, percent-encoded (what the signature covers, server-contract 4.3). */
  val target: String get() = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
}

/** What one in-memory exchange gave back. */
internal sealed interface Answer {
  /** An answer; [cut] when the body stopped early and the connection closed (a `truncate` knob). */
  class Response(
    val status: Int,
    val headers: Headers,
    val body: ByteArray,
    val cut: Boolean = false,
    val webSocket: WebSocketListener? = null,
  ) : Answer

  /** Nothing answered within the phone's timeout: the computer is unreachable, or nothing listens at the address. */
  data object NoAnswer : Answer

  /** The connection closed without an answer: the request was applied and its answer lost, or the computer restarted. */
  data object Dropped : Answer
}

/** The phone end of an in-memory exchange; the computer aborts it to end an upload body or a channel. */
internal class InMemoryPeer : Peer {
  @Volatile var aborted = false
    private set

  /** Called once when the computer aborts this peer (a channel uses it to fail its flow). */
  @Volatile var onAbort: (() -> Unit)? = null

  override val remoteAddress: String get() = FakeComputer.HOST

  override val inMemory: Boolean get() = true

  override fun abort() {
    if (aborted) return
    aborted = true
    onAbort?.invoke()
  }
}

/**
 * The in-memory network between [FakeComputerClient] and the fake computers it knows: a request is handed to the
 * computer at the address it names (or to one of that computer's impersonators) through the same
 * [ComputerCore.exchange] the HTTP dispatcher uses, so every route, rule, knob and observation is shared.
 *
 * Network-level knobs are played here: `Unreachable` answers nothing and records nothing; `Slow` delays the answer on
 * the caller's coroutine clock (virtual time under `runTest`); an upload body goes through
 * [ComputerCore.bodyStarting] and [ComputerCore.bodyBytes] chunk by chunk, so `stallUpload`, "newest wins" and the
 * idle abort apply exactly as to a streamed HTTP body.
 */
internal class InMemoryWire(private val lan: () -> List<FakeComputer>) {
  /** Sends [request] to [at]. [peer] is the phone end; [onBodyProgress] receives the body bytes sent so far. */
  suspend fun exchange(
    at: HostPort,
    request: Outgoing,
    peer: InMemoryPeer = InMemoryPeer(),
    onBodyProgress: ((Long) -> Unit)? = null,
  ): Answer {
    for (computer in lan()) {
      if (computer.address == at) return toComputer(computer, request, peer, onBodyProgress)
      val impersonator = computer.impersonatorsInMemory.firstOrNull { it.address == at }
      if (impersonator != null) return respond(impersonator.answer(recorded(request)))
    }
    return Answer.NoAnswer
  }

  private suspend fun toComputer(
    computer: FakeComputer,
    request: Outgoing,
    peer: InMemoryPeer,
    onBodyProgress: ((Long) -> Unit)?,
  ): Answer {
    if (computer.reachability == Reachability.Unreachable) return Answer.NoAnswer
    val core = computer.inMemoryCore
    val watch = when (val streamed = stream(core, peer, request, onBodyProgress)) {
      Streamed.Stalled -> return Answer.NoAnswer
      Streamed.Aborted -> return Answer.Dropped
      is Streamed.Answered -> return respond(core.refusalResponse(streamed.answer))
      is Streamed.Whole -> streamed.watch
    }
    val response = core.exchange(Call(recorded(request), peer, watch))
    (computer.reachability as? Reachability.Slow)?.let { delay(it.latency.toMillis()) }
    return respond(response)
  }

  private sealed interface Streamed {
    class Whole(val watch: BodyWatch?) : Streamed

    /** The body stopped arriving (`stallUpload`): the phone hears nothing until its own timeout. */
    data object Stalled : Streamed

    /** The computer went away while the body arrived (a restart). */
    data object Aborted : Streamed

    /** The computer ended the body and answered it (a newer body, "Remove", the idle abort; server-contract 6.6). */
    class Answered(val answer: Refusal) : Streamed
  }

  /** Hands an upload body to the computer chunk by chunk, as the network layer does for a streamed one. */
  private fun stream(
    core: ComputerCore,
    peer: InMemoryPeer,
    request: Outgoing,
    onProgress: ((Long) -> Unit)?,
  ): Streamed {
    val body = request.body ?: return Streamed.Whole(null)
    if (body.isEmpty()) return Streamed.Whole(null)
    val head = RequestHead(
      request.method,
      request.target,
      request.headers.toMultimap().mapKeys { it.key.lowercase(Locale.ROOT) }.mapValues { it.value.first() },
    )
    val watch = core.bodyStarting(peer, head)
    var sent = 0
    while (sent < body.size) {
      watch?.takeIf { it.ended }?.answer?.let { return Streamed.Answered(it) }
      if (peer.aborted) return Streamed.Aborted
      var length = minOf(CHUNK_BYTES, body.size - sent)
      val stallAt = watch?.stallAt
      if (watch != null && stallAt != null) length = minOf(length.toLong(), stallAt - watch.receivedBytes).toInt()
      if (length <= 0) return Streamed.Stalled
      if (watch != null) core.bodyBytes(watch, body, sent, length)
      sent += length
      onProgress?.invoke(sent.toLong())
    }
    watch?.takeIf { it.ended }?.answer?.let { return Streamed.Answered(it) }
    if (peer.aborted) return Streamed.Aborted
    watch?.complete = true
    return Streamed.Whole(watch)
  }

  private fun recorded(request: Outgoing): RecordedRequest = RecordedRequest(
    0, 0, null, emptyList(), request.method, request.target, "HTTP/1.1", request.url, request.headers,
    request.body?.toByteString(), request.body?.size?.toLong() ?: 0L, null, null,
  )

  /** Reads a dispatcher's answer the way a client would see it on the wire. */
  private fun respond(response: MockResponse): Answer {
    if (response.onResponseStart != null) return Answer.Dropped
    response.socketHandler?.let { handler ->
      // A truncated artifact: the handler writes part of the body and closes the socket.
      val socket = CapturingSocket()
      handler.handle(socket)
      return Answer.Response(response.code, response.headers, socket.written.readByteArray(), cut = true)
    }
    val body = Buffer().also { buffer -> response.body?.writeTo(buffer) }.readByteArray()
    return Answer.Response(response.code, response.headers, body, webSocket = response.webSocketListener)
  }

  /** A socket for a dispatcher's socket handler that keeps what the handler writes. */
  private class CapturingSocket : okio.Socket {
    val written = Buffer()

    override val source: Source = Buffer()

    override val sink: Sink = written

    override fun cancel() = Unit
  }

  private companion object {
    const val CHUNK_BYTES = 64 * 1024
  }
}
