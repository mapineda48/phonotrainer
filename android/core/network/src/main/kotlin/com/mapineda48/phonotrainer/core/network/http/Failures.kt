package com.mapineda48.phonotrainer.core.network.http

import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.UnknownHostException
import kotlinx.serialization.SerializationException

// The failures of the HTTP stack, each an IOException so OkHttp delivers it to the caller (an interceptor or a socket
// factory must never throw anything else), and their one mapping to the error model of architecture 19.

/** The cleartext guard refused the request before a byte was sent (architecture 5.2, X-AC6). */
internal class GuardedException(message: String) : IOException(message)

/** A signed route was asked of an address whose identity proof has not verified (architecture 5.3). */
internal class UnverifiedPeerException : IOException("The address has not proved it is the paired computer.")

/** No computer is paired, so nothing can be signed. */
internal class NotPairedException : IOException("No computer is paired.")

/** The stored credential cannot be decrypted: the pairing reads as revoked (architecture 20.3). */
internal class CredentialUnreadableException : IOException("The stored credential cannot be decrypted.")

/** No Wi-Fi or Ethernet network to bind the socket to (architecture 5.1, NFR-05). */
internal class NotOnWifiException : IOException("No Wi-Fi or Ethernet network is available.")

/** Binding the socket to the network failed with EPERM: a VPN that does not allow bypass (architecture 5.1). */
internal class VpnBlocksException(cause: Throwable) : IOException("A VPN blocks the home network.", cause)

/** An answer the client refuses to read: over its size cap, not the documented shape, or failing validation (M-3). */
internal class MalformedAnswerException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A request was about to be written on a connection opened on an earlier network (architecture 5.1, Android review
 * M11). Its socket is closed first, so the call is tried once more on a fresh connection; nothing was sent.
 */
internal class RetiredConnectionException : IOException("The connection belongs to an earlier network.")

/**
 * A `hello` proof arrived after the network or the address changed (architecture 5.3): it says nothing about the
 * address now, so it does not verify it and the probe is answered as a reset connection, to be made again.
 */
internal class StaleProofException : IOException("The network or address changed while the proof was on its way.")

/** A host name was asked of the resolver: the client never resolves names (architecture 5.1). */
internal class NoDnsException(host: String) : UnknownHostException("Host names are never resolved ($host).")

/** Maps a failure of a call to the error model (architecture 19); [noAnswerAfterS] is the call's timeout. */
internal object Failures {
  fun toError(error: Throwable, noAnswerAfterS: Int): AppError.Computer = when (error) {
    is GuardedException, is UnverifiedPeerException, is NotPairedException, is NoDnsException -> AppError.Computer.Guarded
    is CredentialUnreadableException -> AppError.Computer.CredentialRejected
    is NotOnWifiException -> AppError.Computer.NotOnWifi
    is VpnBlocksException -> AppError.Computer.Unreachable(ProbeOutcome.VpnBlocks)
    is RetiredConnectionException, is StaleProofException -> AppError.Computer.Unreachable(ProbeOutcome.Reset)
    is MalformedAnswerException, is SerializationException -> AppError.Computer.Malformed
    is IllegalArgumentException -> AppError.Computer.Malformed
    is InterruptedIOException -> AppError.Computer.Unreachable(ProbeOutcome.NoAnswer(noAnswerAfterS))
    is ConnectException -> AppError.Computer.Unreachable(connectOutcome(error, noAnswerAfterS))
    is NoRouteToHostException, is PortUnreachableException -> AppError.Computer.Unreachable(ProbeOutcome.NoRoute)
    is EOFException, is SocketException -> AppError.Computer.Unreachable(ProbeOutcome.Reset)
    is IOException -> causeOrReset(error, noAnswerAfterS)
    else -> AppError.Computer.Unreachable(ProbeOutcome.Reset)
  }

  /** A connect failure: "No route to host" and "Network is unreachable" say there is no route; the rest is refused. */
  private fun connectOutcome(error: ConnectException, noAnswerAfterS: Int): ProbeOutcome {
    val text = error.message.orEmpty()
    return when {
      "EHOSTUNREACH" in text || "ENETUNREACH" in text || "No route" in text || "unreachable" in text ->
        ProbeOutcome.NoRoute
      "ETIMEDOUT" in text || "timed out" in text -> ProbeOutcome.NoAnswer(noAnswerAfterS)
      else -> ProbeOutcome.Refused
    }
  }

  /** OkHttp wraps some failures (a route failure keeps its first cause); the cause decides when it is one of ours. */
  private fun causeOrReset(error: IOException, noAnswerAfterS: Int): AppError.Computer {
    val cause = error.cause
    return if (cause != null && cause !== error && cause is IOException) {
      toError(cause, noAnswerAfterS)
    } else {
      AppError.Computer.Unreachable(ProbeOutcome.Reset)
    }
  }
}
