package com.mapineda48.phonotrainer.core.connectivity.discovery

import com.mapineda48.phonotrainer.core.common.di.IoDispatcher
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.domain.model.Datagram
import com.mapineda48.phonotrainer.core.domain.spi.DiscoveryTransport
import com.mapineda48.phonotrainer.core.network.api.AddressPolicy
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Binds a datagram socket to the current Wi-Fi or Ethernet network before anything is sent (architecture 6.1). */
fun interface DatagramBinder {
  /** @throws IOException when there is no such network, or a VPN refuses the binding. */
  @Throws(IOException::class)
  fun bind(socket: DatagramSocket)
}

/**
 * The phone's half of discovery (architecture 6.1, server-contract 5): one `DatagramSocket` on an ephemeral port, bound
 * to the home network, sends the probe to each broadcast target at each offset of the schedule and collects unicast
 * replies until `listenFor` has passed since the first send. Only replies from addresses the [AddressPolicy] allows
 * (private or link-local in every release build) are passed on; the phone never listens for broadcast or multicast,
 * so it needs no multicast lock. A target is an IPv4 literal, optionally with `:port` (the fixed discovery port by
 * default). Nothing is resolved: a target that is not a literal is skipped. No discovery runs in the background: only
 * the foreground search and re-discovery call it (NFR-04).
 */
class UdpDiscoveryTransport @Inject constructor(
  private val binder: DatagramBinder,
  private val policy: AddressPolicy,
  @param:IoDispatcher private val io: CoroutineDispatcher,
  private val log: PhonoLog,
) : DiscoveryTransport {
  override fun probe(
    datagram: ByteArray,
    broadcastTargets: List<String>,
    schedule: List<Duration>,
    listenFor: Duration,
  ): Flow<Datagram> = channelFlow {
    val targets = broadcastTargets.mapNotNull(::target)
    val socket = try {
      open()
    } catch (e: IOException) {
      log.w("connectivity.discovery_unbound", e)
      return@channelFlow
    }
    val deadline = System.nanoTime() + listenFor.toNanos()
    try {
      val sender = launch {
        var elapsed = Duration.ZERO
        for (offset in schedule.sorted()) {
          delay(offset.minus(elapsed).toMillis().coerceAtLeast(0))
          elapsed = offset
          targets.forEach { address -> send(socket, datagram, address) }
        }
      }
      val buffer = ByteArray(DiscoveryProtocol.MAX_DATAGRAM + 1)
      while (isActive) {
        val remainingMs = (deadline - System.nanoTime()) / NANOS_PER_MILLI
        if (remainingMs <= 0) break
        socket.soTimeout = remainingMs.coerceIn(1, RECEIVE_SLICE_MS).toInt()
        val packet = DatagramPacket(buffer, buffer.size)
        try {
          socket.receive(packet)
        } catch (_: SocketTimeoutException) {
          continue
        } catch (e: SocketException) {
          if (socket.isClosed) break
          log.w("connectivity.discovery_receive", e)
          continue
        }
        if (packet.length > DiscoveryProtocol.MAX_DATAGRAM) continue
        val source = (packet.address as? Inet4Address)?.hostAddress ?: continue
        if (!policy.allowsCandidate(source)) continue
        send(Datagram(packet.data.copyOfRange(packet.offset, packet.offset + packet.length), source, packet.port))
      }
      sender.cancel()
    } finally {
      socket.close()
    }
  }.flowOn(io)

  private fun open(): DatagramSocket {
    val socket = DatagramSocket(null as java.net.SocketAddress?)
    try {
      socket.broadcast = true
      binder.bind(socket)
      socket.bind(InetSocketAddress(0))
    } catch (e: IOException) {
      socket.close()
      throw e
    }
    return socket
  }

  private fun send(socket: DatagramSocket, datagram: ByteArray, address: InetSocketAddress) {
    try {
      socket.send(DatagramPacket(datagram, datagram.size, address))
    } catch (e: IOException) {
      // A target the network refuses (no route to a directed broadcast) must not stop the others.
      log.w("connectivity.discovery_send", e)
    }
  }

  private companion object {
    const val NANOS_PER_MILLI = 1_000_000L
    const val RECEIVE_SLICE_MS = 200L
    const val MAX_PORT = 65_535
    const val OCTETS = 4
    const val MAX_OCTET = 255

    /** `a.b.c.d` or `a.b.c.d:port` as a socket address, built from the octets so nothing is ever resolved. */
    fun target(text: String): InetSocketAddress? {
      val host = text.substringBefore(':')
      val port = if (':' in text) text.substringAfter(':').toIntOrNull() ?: return null else DiscoveryProtocol.PORT
      if (port !in 1..MAX_PORT) return null
      val octets = host.split('.')
      if (octets.size != OCTETS) return null
      val bytes = ByteArray(OCTETS)
      octets.forEachIndexed { index, part ->
        if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
        val value = part.toInt()
        if (value > MAX_OCTET) return null
        bytes[index] = value.toByte()
      }
      return InetSocketAddress(InetAddress.getByAddress(bytes), port)
    }
  }
}
