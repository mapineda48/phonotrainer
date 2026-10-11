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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** The job channel ended: the computer closed it, a ping got no pong, or the network changed (architecture 5.5). */
internal class ChannelLostException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The job channel of architecture 5.5 and server-contract 7: `ws://<current address>/api/phone/events` with a signed
 * upgrade, on the channel profile (OkHttp pings every 7 s, so a missing pong fails it within 14 s). The flow emits
 * `hello`, `snapshot`, `job` and `deleted` events; frames of an unknown type, and frames that fail validation, are
 * skipped. It fails with [ChannelLostException] (or the upgrade's failure) when the channel is lost or the network
 * changes, completes when the computer closes it, and closes the socket when the collector stops. Reconnecting is the
 * caller's (the sync engine's backoff, 5.5).
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

  override fun open(): Flow<ChannelEvent> = callbackFlow {
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
        event?.let { trySend(it) }
      }

      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(NORMAL_CLOSURE, null)
        close()
      }

      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        close()
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
  }.buffer(Channel.UNLIMITED)

  private companion object {
    const val EVENTS = "/api/phone/events"
    const val NORMAL_CLOSURE = 1000
  }
}
