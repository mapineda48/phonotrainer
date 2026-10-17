package com.mapineda48.phonotrainer.core.connectivity.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.connectivity.discovery.DatagramBinder
import com.mapineda48.phonotrainer.core.domain.model.NetworkKind
import com.mapineda48.phonotrainer.core.domain.model.NetworkSnapshot
import com.mapineda48.phonotrainer.core.domain.spi.NetworkMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The transports the snapshot cares about. */
internal enum class Transport { WIFI, ETHERNET }

/** What one Wi-Fi or Ethernet network looks like now: its handle, transport, validation and IPv4 address. */
internal data class HomeNetwork(
  val handle: Long,
  val transport: Transport,
  val validated: Boolean,
  val ipv4: String? = null,
  val prefixLength: Int? = null,
)

/**
 * The networks Android reports, reduced to one [NetworkSnapshot] (architecture 5.1, 6.1). Wi-Fi and Ethernet networks
 * count whether or not they reach the internet (a home Wi-Fi without internet still reaches the computer, spec 7.5
 * condition 1); a network with an IPv4 address is preferred, then Wi-Fi, then the newest. Without one, any cellular
 * network makes the snapshot "mobile only". A VPN is only reported (`vpnActive`), never used.
 */
internal class NetworkTracker {
  private val home = LinkedHashMap<Long, HomeNetwork>()
  private val cellular = LinkedHashSet<Long>()
  private val vpns = HashSet<Long>()

  @Synchronized
  fun homeCapabilities(handle: Long, transport: Transport, validated: Boolean): NetworkSnapshot {
    val known = home.remove(handle)
    home[handle] = known?.copy(transport = transport, validated = validated) ?: HomeNetwork(handle, transport, validated)
    return snapshot()
  }

  @Synchronized
  fun homeAddress(handle: Long, ipv4: String?, prefixLength: Int?): NetworkSnapshot {
    home[handle]?.let { home[handle] = it.copy(ipv4 = ipv4, prefixLength = prefixLength) }
    return snapshot()
  }

  @Synchronized
  fun homeLost(handle: Long): NetworkSnapshot {
    home.remove(handle)
    return snapshot()
  }

  @Synchronized
  fun cellular(handle: Long, available: Boolean): NetworkSnapshot {
    if (available) cellular += handle else cellular -= handle
    return snapshot()
  }

  @Synchronized
  fun vpn(handle: Long, available: Boolean): NetworkSnapshot {
    if (available) vpns += handle else vpns -= handle
    return snapshot()
  }

  @Synchronized
  fun snapshot(): NetworkSnapshot {
    val vpnActive = vpns.isNotEmpty()
    val chosen = home.values.reversed().sortedWith(
      compareByDescending<HomeNetwork> { it.ipv4 != null }.thenBy { it.transport != Transport.WIFI },
    ).firstOrNull()
    if (chosen != null) {
      val kind = if (chosen.transport == Transport.WIFI) NetworkKind.WIFI else NetworkKind.ETHERNET
      return NetworkSnapshot(kind, chosen.handle, chosen.ipv4, chosen.prefixLength, vpnActive, chosen.validated)
    }
    val mobile = cellular.lastOrNull()
    if (mobile != null) return NetworkSnapshot(NetworkKind.MOBILE_ONLY, mobile, null, null, vpnActive, validated = true)
    return NetworkSnapshot(NetworkKind.NONE, null, null, null, vpnActive, validated = false)
  }

  companion object {
    /** The first usable IPv4 address of a link (not loopback, not the wildcard) with its prefix length. */
    fun ipv4Of(addresses: List<Pair<InetAddress, Int>>): Pair<String, Int>? =
      addresses.firstOrNull { (address, _) -> address is Inet4Address && !address.isLoopbackAddress && !address.isAnyLocalAddress }
        ?.let { (address, prefix) -> address.hostAddress?.let { it to prefix } }
  }
}

/**
 * [NetworkMonitor] over `ConnectivityManager` callbacks (architecture 5.1, 6.1, 6.2): Wi-Fi and Ethernet networks
 * without the internet capability requirement, cellular networks (for "Not on Wi-Fi") and VPNs (for the checklist).
 * The snapshot carries the chosen network's handle, which `:core:connectivity` gives the computer client's binder,
 * and its IPv4 address and prefix from `LinkProperties` (the directed broadcast, and "This phone on this Wi-Fi",
 * spec D-39). Needs `ACCESS_NETWORK_STATE` only. Replaced in end-to-end tests by a scripted monitor (21.5).
 */
@Singleton
class AndroidNetworkMonitor @Inject constructor(
  @param:ApplicationContext private val context: Context,
  private val log: PhonoLog,
) : NetworkMonitor {
  private val tracker = NetworkTracker()
  private val state = MutableStateFlow(tracker.snapshot())

  override val network: StateFlow<NetworkSnapshot> = state.asStateFlow()

  init {
    register()
  }

  private fun register() {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
    try {
      manager.registerNetworkCallback(homeRequest(), HomeCallback())
      manager.registerNetworkCallback(transportRequest(NetworkCapabilities.TRANSPORT_CELLULAR), CellularCallback())
      manager.registerNetworkCallback(vpnRequest(), VpnCallback())
    } catch (e: RuntimeException) {
      // A SecurityException without ACCESS_NETWORK_STATE, or the per-app callback limit: report no network.
      log.e("connectivity.monitor_unregistered", e)
    }
  }

  private fun publish(snapshot: NetworkSnapshot) {
    state.value = snapshot
  }

  private inner class HomeCallback : ConnectivityManager.NetworkCallback() {
    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
      val transport = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
        else -> return
      }
      val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
      publish(tracker.homeCapabilities(network.networkHandle, transport, validated))
    }

    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
      val address = NetworkTracker.ipv4Of(linkProperties.linkAddresses.map { it.address to it.prefixLength })
      publish(tracker.homeAddress(network.networkHandle, address?.first, address?.second))
    }

    override fun onLost(network: Network) {
      publish(tracker.homeLost(network.networkHandle))
    }
  }

  private inner class CellularCallback : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
      publish(tracker.cellular(network.networkHandle, available = true))
    }

    override fun onLost(network: Network) {
      publish(tracker.cellular(network.networkHandle, available = false))
    }
  }

  private inner class VpnCallback : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
      publish(tracker.vpn(network.networkHandle, available = true))
    }

    override fun onLost(network: Network) {
      publish(tracker.vpn(network.networkHandle, available = false))
    }
  }

  private companion object {
    /** Wi-Fi or Ethernet, with or without internet access; a VPN is never one of them (NOT_VPN stays). */
    fun homeRequest(): NetworkRequest = NetworkRequest.Builder()
      .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
      .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
      .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      .build()

    fun transportRequest(transport: Int): NetworkRequest = NetworkRequest.Builder().addTransportType(transport).build()

    fun vpnRequest(): NetworkRequest = NetworkRequest.Builder()
      .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
      .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
      .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      .build()
  }
}

/**
 * Binds discovery's socket to the network of the current snapshot (`Network.fromNetworkHandle`, API 28+), so the probe
 * leaves on the Wi-Fi even when Android routes the rest of the phone's traffic over mobile data (NFR-05).
 */
internal class NetworkDatagramBinder @Inject constructor(private val monitor: NetworkMonitor) : DatagramBinder {
  override fun bind(socket: DatagramSocket) {
    val snapshot = monitor.network.value
    val handle = snapshot.networkHandle
    if (handle == null || (snapshot.kind != NetworkKind.WIFI && snapshot.kind != NetworkKind.ETHERNET)) {
      throw IOException("No Wi-Fi or Ethernet network to search on.")
    }
    Network.fromNetworkHandle(handle).bindSocket(socket)
  }
}
