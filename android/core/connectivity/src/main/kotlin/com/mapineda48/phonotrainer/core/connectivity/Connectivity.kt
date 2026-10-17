package com.mapineda48.phonotrainer.core.connectivity

import com.mapineda48.phonotrainer.core.domain.model.NetworkKind
import com.mapineda48.phonotrainer.core.domain.model.NetworkSnapshot
import java.time.Duration
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/** The product constants of the connectivity model (architecture 5.4, 6.2; spec 7.5). */
object ConnectivityRules {
  /** The oldest computer API this app talks to (`MIN_SERVER_API`, architecture 5.4). */
  const val MIN_SERVER_API: Int = 6

  /** A probe's timeout; [SLOW_PROBE_TIMEOUT] while the computer was last known to be analyzing (spec 7.5). */
  val PROBE_TIMEOUT: Duration = Duration.ofSeconds(3)
  val SLOW_PROBE_TIMEOUT: Duration = Duration.ofSeconds(10)

  /** "Checking your computer…" re-probes this long after the first failure (spec 7.5, SC-10). */
  val RE_PROBE: Duration = Duration.ofSeconds(5)

  /** "Looking for your computer…" lasts at most this long after a network change (spec 7.5). */
  val LOOKING: Duration = Duration.ofSeconds(10)

  /** Foreground probe intervals: while anything is pending, and otherwise (spec 7.5). */
  val PENDING_INTERVAL: Duration = Duration.ofSeconds(30)
  val IDLE_INTERVAL: Duration = Duration.ofMinutes(2)

  /** "Forget this computer" waits this long for the unpair, then forgets anyway (architecture 6.3). */
  val UNPAIR_LIMIT: Duration = Duration.ofSeconds(5)

  /** The short server id of Connection details: `srv_` and its first 8 characters, as the terminal prints ids. */
  const val SHORT_ID_CHARS: Int = 12
}

/** The phone has a Wi-Fi or Ethernet network, the only kind traffic to the computer may use (NFR-05). */
internal val NetworkSnapshot.onHomeNetwork: Boolean
  get() = kind == NetworkKind.WIFI || kind == NetworkKind.ETHERNET

/**
 * One exchange with the computer at a time that changes which address is current: probes, pairing and a new address
 * (the guard's current address and the identity proof belong together, architecture 5.2, 5.3).
 */
@Singleton
internal class ConnectivitySession @Inject constructor() {
  val lock: Mutex = Mutex()
}
