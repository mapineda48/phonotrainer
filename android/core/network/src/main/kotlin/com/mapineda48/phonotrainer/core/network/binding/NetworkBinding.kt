package com.mapineda48.phonotrainer.core.network.binding

import android.net.Network
import android.system.ErrnoException
import android.system.OsConstants
import com.mapineda48.phonotrainer.core.network.api.ComputerNetworkBinder
import com.mapineda48.phonotrainer.core.network.guard.GuardedSocket
import com.mapineda48.phonotrainer.core.network.http.NotOnWifiException
import com.mapineda48.phonotrainer.core.network.http.RetiredConnectionException
import com.mapineda48.phonotrainer.core.network.http.VpnBlocksException
import java.io.IOException
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.Response

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
 * the verified address is forgotten (5.3, 5.5). A connection that was in use during the change is retired when it is
 * next taken from the pool ([CurrentNetworkInterceptor]).
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

  /**
   * Binds [socket] to the current network and answers that network's handle, or fails it: no network, or a VPN that
   * blocks the binding.
   */
  fun bindSocket(socket: Socket): Long {
    val bound = state as? State.Bound ?: throw NotOnWifiException()
    try {
      platform.bindSocket(bound.handle, socket)
    } catch (e: IOException) {
      if (platform.isPermissionDenied(e)) throw VpnBlocksException(e)
      throw e
    }
    return bound.handle
  }

  private sealed interface State {
    data object Unset : State

    data object None : State

    data class Bound(val handle: Long) : State
  }
}

/**
 * Retires connections from an earlier network (architecture 5.1, Android review M11), once per network attempt,
 * OkHttp's own retries included. The binder's eviction closes only idle connections: one in use during a change of
 * network goes back to the pool afterwards. Before a request is written on a connection whose socket was bound to a
 * network other than the current one, that socket is closed, the idle connections are evicted and the attempt fails
 * with [RetiredConnectionException] (nothing was written), which [RetiredConnectionRetry] turns into one more attempt
 * on a fresh connection, bound to the current network. Installed only with the client's own [NetworkBinding].
 */
internal class CurrentNetworkInterceptor(
  private val binding: NetworkBinding,
  private val pool: ConnectionPool,
) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val socket = chain.connection()?.socket()
    if (socket !is GuardedSocket || socket.networkHandle == null || socket.networkHandle != binding.networkHandle) {
      socket?.close()
      pool.evictAll()
      throw RetiredConnectionException()
    }
    return chain.proceed(chain.request())
  }
}

/**
 * Tries a call once more when its connection was retired ([CurrentNetworkInterceptor]): the retired socket is closed,
 * so the pool never hands it out again and the second attempt opens a connection on the current network. Once only.
 */
internal object RetiredConnectionRetry : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response = try {
    chain.proceed(chain.request())
  } catch (_: RetiredConnectionException) {
    chain.proceed(chain.request())
  }
}
