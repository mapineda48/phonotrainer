package com.mapineda48.phonotrainer.core.network.channel

import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.domain.model.remote.ChannelEvent
import com.mapineda48.phonotrainer.core.network.api.ComputerNetworkBinder
import com.mapineda48.phonotrainer.core.network.api.JobChannel
import com.mapineda48.phonotrainer.core.network.binding.NetworkBinding
import com.mapineda48.phonotrainer.core.network.dto.ChannelFrameDto
import com.mapineda48.phonotrainer.core.network.dto.ComputerJson
import com.mapineda48.phonotrainer.core.network.dto.toEvent
import com.mapineda48.phonotrainer.core.network.http.ComputerHttp
import com.mapineda48.phonotrainer.core.network.http.Profile
import com.mapineda48.phonotrainer.core.network.peers.ComputerPeers
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The job channel ended: the upgrade was refused, the computer closed the channel, a ping got no pong, or the network
 * changed (architecture 5.5).
 */
internal class ChannelLostException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The job channel of architecture 5.5 and server-contract 7: `ws://<current address>/api/phone/events` with a signed
 * upgrade, on the channel profile (OkHttp pings every 7 s, so a missing pong fails it within 14 s). The flow emits
 * `hello`, `snapshot`, `job` and `deleted` events; frames of an unknown type, and frames that fail validation, are
 * skipped. It fails with [ChannelLostException] (or the upgrade's failure) when the channel is refused, lost or closed
 * by the computer, or the network changes, so a consumer sees every end of the channel as a failure, as the fake
 * computer's client shows it; it closes the socket when the collector stops. Reconnecting is the caller's (the sync
 * engine's backoff, 5.5).
 *
 * Nothing the computer sends can exhaust memory (architecture 5.4): each message is bounded on the socket before
 * OkHttp reads it, uncompressed ([ChannelInput], [ChannelUpgradeInterceptor]), and the events waiting for the collector
 * are bounded in number ([MAX_PENDING_EVENTS]) and in size ([MAX_PENDING_CHARS], the text of their frames). A
 * computer that sends faster than the collector takes loses the channel; the sync engine catches up from the
 * snapshot of the next one.
 */
@Singleton
internal class WebSocketJobChannel @Inject constructor(
  private val http: ComputerHttp,
  private val peers: ComputerPeers,
  binder: ComputerNetworkBinder,
  private val log: PhonoLog,
) : JobChannel {
  private val networkGeneration = MutableStateFlow(0L)

  init {
    (binder as? NetworkBinding)?.addListener { networkGeneration.update { it + 1 } }
  }

  override fun open(): Flow<ChannelEvent> = flow {
    val pending = AtomicLong()
    val waiting = events(pending).buffer(MAX_PENDING_EVENTS)
    emitAll(waiting.map { next -> next.event.also { pending.addAndGet(-next.chars) } })
  }

  /** The channel's events, each with the length of its frame's text, which [pending] adds up until it is collected. */
  private fun events(pending: AtomicLong): Flow<Pending> = callbackFlow {
    val generation = networkGeneration.value
    val at = peers.current.value
    if (at == null) {
      close(ChannelLostException("No computer to open the channel with."))
      awaitClose()
      return@callbackFlow
    }
    val listener = object : WebSocketListener() {
      override fun onMessage(webSocket: WebSocket, text: String) {
        val event = try {
          ComputerJson.decodeFromString(ChannelFrameDto.serializer(), text).toEvent()
        } catch (e: IOException) {
          log.d { "channel: skipped a frame that failed validation (${e.message})" }
          null
        } catch (e: IllegalArgumentException) {
          log.d { "channel: skipped a frame that is not JSON (${e.javaClass.simpleName})" }
          null
        }
        if (event == null) return
        val chars = text.length.toLong()
        if (pending.addAndGet(chars) > MAX_PENDING_CHARS || trySend(Pending(event, chars)).isFailure) {
          webSocket.cancel()
          close(ChannelLostException("The computer sent events faster than this app takes them."))
        }
      }

      // The phone never closes the channel itself (stopping the collector cancels the socket), so a close always
      // comes from the computer: it revoked the device, turned phone access off or restarted (code 1008 after an
      // accepted upgrade), and the channel is lost like any other (server-contract 7, architecture 5.5).
      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(NORMAL_CLOSURE, null)
        close(ChannelLostException("The computer closed the channel (code $code)."))
      }

      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        close(ChannelLostException("The computer closed the channel (code $code)."))
      }

      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        val why = response?.let { "The channel was refused with HTTP ${it.code}." } ?: "The channel was lost."
        close(ChannelLostException(why, t))
      }
    }
    val request = Request.Builder().url("ws://${at.host}:${at.port}$EVENTS").build()
    val socket = http.client(Profile.CHANNEL).newWebSocket(request, listener)
    val networkWatch = launch {
      networkGeneration.first { it != generation }
      socket.cancel()
      close(ChannelLostException("The network changed."))
    }
    awaitClose {
      networkWatch.cancel()
      socket.cancel()
    }
  }

  private class Pending(val event: ChannelEvent, val chars: Long)

  internal companion object {
    /** The events that may wait for the collector; one more loses the channel. */
    const val MAX_PENDING_EVENTS = 256

    /** The text of the frames whose events wait for the collector, at most one listing's worth (architecture 5.4). */
    const val MAX_PENDING_CHARS: Long = ChannelInput.MAX_MESSAGE_BYTES

    private const val EVENTS = "/api/phone/events"
    private const val NORMAL_CLOSURE = 1000
  }
}
