package com.mapineda48.phonotrainer.core.network.binding

import android.net.Network
import android.system.ErrnoException
import android.system.OsConstants
import com.mapineda48.phonotrainer.core.network.api.ComputerNetworkBinder
import com.mapineda48.phonotrainer.core.network.http.NotOnWifiException
import com.mapineda48.phonotrainer.core.network.http.VpnBlocksException
import java.io.IOException
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** What the platform does with a network handle: rebuild the `Network` and bind a socket to it. */
internal interface NetworkPlatform {
  /** Binds [socket] (not connected yet) to the network of [networkHandle]. */
  @Throws(IOException::class)
  fun bindSocket(networkHandle: Long, socket: Socket)

  /** Whether [error], thrown by [bindSocket], is EPERM (a VPN that does not allow bypass, architecture 5.1). */
  fun isPermissionDenied(error: IOException): Boolean
}

/** Android's `Network.fromNetworkHandle` (API 28+) and `Network.bindSocket`. */
internal object AndroidNetworkPlatform : NetworkPlatform {
  override fun bindSocket(networkHandle: Long, socket: Socket) {
    Network.fromNetworkHandle(networkHandle).bindSocket(socket)
  }

  override fun isPermissionDenied(error: IOException): Boolean {
    val errno = generateSequence(error as Throwable) { it.cause }.filterIsInstance<ErrnoException>().firstOrNull()
    return errno?.errno == OsConstants.EPERM || "EPERM" in error.message.orEmpty()
  }
}

/**
 * The client's binding to the home network (architecture 5.1, NFR-05). `:core:connectivity` calls [bind] with the
 * current Wi-Fi or Ethernet network's handle, and with null when there is none. Every socket the client opens is bound
 * to the network current at that moment ([bindSocket]); before the first [bind] and while there is no network, a
 * socket fails with [NotOnWifiException], so the client never uses mobile data or the process default network. On
 * every change of network the listeners run at once: the connection pool is evicted, the job channel is closed and
 * the verified address is forgotten (5.3, 5.5).
 *
 * The end-to-end test set replaces the binder with one that is not a [NetworkBinding] (architecture 21.5): the
 * socket factory then binds nothing, which is what that test binder means.
 */
internal class NetworkBinding(private val platform: NetworkPlatform) : ComputerNetworkBinder {
  private val listeners = CopyOnWriteArrayList<() -> Unit>()

  @Volatile private var state: State = State.Unset

  override fun bind(networkHandle: Long?) {
    val next = if (networkHandle == null) State.None else State.Bound(networkHandle)
    val changed = synchronized(this) {
      val previous = state
      state = next
      previous != next
    }
    if (changed) listeners.forEach { it() }
  }

  /** Runs [listener] on every change of network (not when the same network is bound again). */
  fun addListener(listener: () -> Unit) {
    listeners += listener
  }

  /** The handle sockets are bound to now; null while unbound. */
  val networkHandle: Long? get() = (state as? State.Bound)?.handle

  /** Binds [socket] to the current network, or fails it: no network, or a VPN that blocks the binding. */
  fun bindSocket(socket: Socket) {
    val bound = state as? State.Bound ?: throw NotOnWifiException()
    try {
      platform.bindSocket(bound.handle, socket)
    } catch (e: IOException) {
      if (platform.isPermissionDenied(e)) throw VpnBlocksException(e)
      throw e
    }
  }

  private sealed interface State {
    data object Unset : State

    data object None : State

    data class Bound(val handle: Long) : State
  }
}
