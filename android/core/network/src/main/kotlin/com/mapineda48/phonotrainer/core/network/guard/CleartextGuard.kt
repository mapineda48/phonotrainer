package com.mapineda48.phonotrainer.core.network.guard

import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.network.api.AddressPolicy
import com.mapineda48.phonotrainer.core.network.api.ComputerNetworkBinder
import com.mapineda48.phonotrainer.core.network.binding.CurrentNetworkInterceptor
import com.mapineda48.phonotrainer.core.network.binding.NetworkBinding
import com.mapineda48.phonotrainer.core.network.http.GuardedException
import com.mapineda48.phonotrainer.core.network.http.NoDnsException
import com.mapineda48.phonotrainer.core.network.peers.ComputerPeers
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Cleartext confinement (architecture 5.2, spec 11.6, X-AC6). A request may leave the phone only when its scheme is
 * `http` (a WebSocket's `ws` reaches OkHttp as `http`), its host is an IPv4 literal, and `host:port` is the paired
 * computer's current address or the single candidate being checked, and that host passes the [AddressPolicy]
 * (private and link-local ranges; tests add loopback). Everything else fails with [GuardedException] before a byte is
 * sent. The rule is checked three times: as the first application interceptor (the URL), by the event listener before
 * a connection is made, and by every socket the client opens (the address it connects to), so no path through OkHttp
 * (a retry, a follow-up, a pooled route) can reach another host.
 */
internal class CleartextGuard(private val peers: ComputerPeers, private val policy: AddressPolicy) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    check(chain.request().url)
    return chain.proceed(chain.request())
  }

  /** Throws [GuardedException] unless [url] may be requested now. */
  fun check(url: HttpUrl) {
    if (url.scheme != "http") throw GuardedException("Only plain HTTP to the computer is allowed.")
    if (!allows(url.host, url.port)) throw GuardedException("The address is not the computer's.")
  }

  /** Whether a socket may connect to [address] now. */
  fun allows(address: SocketAddress?): Boolean {
    val inet = address as? InetSocketAddress ?: return false
    val ip = inet.address?.takeIf { it.address.size == IPV4_BYTES } ?: return false
    return allows(ip.hostAddress.orEmpty(), inet.port)
  }

  /** Whether [host]:[port] is the current address or the candidate, and [host] an allowed IPv4 literal. */
  fun allows(host: String, port: Int): Boolean {
    if (!Ipv4.isLiteral(host) || !policy.allowsCandidate(host)) return false
    val target = HostPort(host, port)
    return peers.reachable().any { it == target }
  }

  private companion object {
    const val IPV4_BYTES = 4
  }
}

/** Dotted-quad IPv4 literals as the computer's addresses are written: four decimal octets, no leading zeros. */
internal object Ipv4 {
  fun isLiteral(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != OCTETS) return false
    return parts.all { part -> part.toIntOrNull()?.let { it in 0..MAX_OCTET && part == it.toString() } == true }
  }

  private const val OCTETS = 4
  private const val MAX_OCTET = 255
}

/** The client never resolves a name (architecture 5.1): addresses are IPv4 literals, so no DNS traffic leaves. */
internal object NoDns : Dns {
  override fun lookup(hostname: String): List<InetAddress> = throw NoDnsException(hostname)
}

/**
 * The guard's check before a connection is made (architecture 5.2): a connection to an address the guard refuses, or
 * any name lookup, fails the call there with an `IOException`, which OkHttp reports as that call's failure before the
 * socket connects. The sockets refuse such a connection too ([GuardedSocket]).
 */
internal class GuardEventListener(private val guard: CleartextGuard) : EventListener() {
  override fun dnsStart(call: Call, domainName: String) {
    throw NoDnsException(domainName)
  }

  override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
    if (proxy.type() != Proxy.Type.DIRECT || !guard.allows(inetSocketAddress)) {
      throw GuardedException("The address is not the computer's.")
    }
  }
}

/**
 * The client's socket factory (architecture 5.1): every socket is bound to the network current at the moment it is
 * created, when the binder is the client's own [NetworkBinding] (the end-to-end binder binds nothing), and refuses to
 * connect anywhere the guard does not allow.
 */
internal class ComputerSocketFactory(
  private val binder: ComputerNetworkBinder,
  private val guard: CleartextGuard,
) : SocketFactory() {
  override fun createSocket(): Socket = GuardedSocket(guard).also { socket ->
    try {
      socket.networkHandle = (binder as? NetworkBinding)?.bindSocket(socket)
    } catch (e: java.io.IOException) {
      socket.close()
      throw e
    }
  }

  override fun createSocket(host: String?, port: Int): Socket = throw NoDnsException(host.orEmpty())

  override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
    throw NoDnsException(host.orEmpty())

  override fun createSocket(host: InetAddress?, port: Int): Socket =
    createSocket().apply { connect(InetSocketAddress(host, port)) }

  override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
    throw GuardedException("Sockets are never bound to a chosen local address.")
}

/**
 * A socket that connects only where the guard allows, and remembers the network it was bound to (null when the binder
 * binds nothing), so a connection from an earlier network is never reused ([CurrentNetworkInterceptor]).
 */
internal class GuardedSocket(private val guard: CleartextGuard) : Socket() {
  @Volatile var networkHandle: Long? = null

  override fun connect(endpoint: SocketAddress?, timeout: Int) {
    if (!guard.allows(endpoint)) throw GuardedException("The address is not the computer's.")
    super.connect(endpoint, timeout)
  }
}
