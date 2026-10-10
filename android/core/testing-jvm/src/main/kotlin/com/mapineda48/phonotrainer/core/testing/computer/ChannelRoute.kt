package com.mapineda48.phonotrainer.core.testing.computer

import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_BAD_REQUEST
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_FORBIDDEN
import mockwebserver3.MockResponse
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Locale

// The job channel for phones (server-contract 7): `hello`, then `snapshot`, then `job` and `deleted` events.

/** `GET /api/phone/events` with a signed upgrade; refused upgrades are an HTTP 403 before accept. */
internal fun ComputerCore.events(call: Call): MockResponse {
  if (call.header("Upgrade")?.lowercase(Locale.ROOT) != "websocket") {
    throw Refusal(HTTP_BAD_REQUEST, "invalid_request", "This route is a WebSocket.")
  }
  val deviceId = call.device!!.id
  if (channels.size >= ComputerCore.MAX_CHANNELS ||
    channels.count { it.deviceId == deviceId } >= ComputerCore.MAX_CHANNELS_PER_DEVICE
  ) {
    throw Refusal(HTTP_FORBIDDEN, null, "Too many open channels.") // an upgrade refusal has no body (dispatch)
  }
  val channel = Channel(deviceId, call.connection)
  channels += channel
  return MockResponse.Builder().webSocketUpgrade(ChannelListener(this, channel)).build()
}

/** The computer's side of one phone channel. Frames from the phone are only read to notice a close. */
private class ChannelListener(private val core: ComputerCore, private val channel: Channel) : WebSocketListener() {
  override fun onOpen(webSocket: WebSocket, response: Response) {
    synchronized(core.lock) {
      if (channel !in core.channels) {
        channel.drop()
        return
      }
      channel.socket = webSocket
      core.channelHello(channel).forEach(webSocket::send)
    }
  }

  override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
    webSocket.close(NORMAL_CLOSURE, null)
    forget()
  }

  override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = forget()

  override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = forget()

  private fun forget() {
    synchronized(core.lock) { core.channels.remove(channel) }
  }

  private companion object {
    const val NORMAL_CLOSURE = 1000
  }
}
