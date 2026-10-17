package com.mapineda48.phonotrainer.core.connectivity.discovery

import com.mapineda48.phonotrainer.core.connectivity.ComputerNames
import com.mapineda48.phonotrainer.core.domain.model.FoundComputer
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.NetworkSnapshot
import com.mapineda48.phonotrainer.core.domain.model.SearchState
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.spi.DiscoveryTransport
import com.mapineda48.phonotrainer.core.network.api.AddressPolicy
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A reply and the address its `hello` goes to: the reply's source address and announced port (architecture 6.1). */
internal data class DiscoveredAddress(val reply: DiscoveryReply, val address: HostPort)

/**
 * One discovery search on the current home network (architecture 6.1): a fresh nonce, the probe to the limited and the
 * directed broadcast at 0, 1 and 3 s, replies until 10 s. Replies are matched by the nonce and kept as **one entry per
 * (server id, source address, port)**: the same computer answering the three probes and both broadcasts is one entry,
 * while one server id from two addresses stays two entries, the conflict the phone shows (security review H-1). Each
 * entry is passed on once; its confirmation with `hello` is the caller's.
 */
internal class DiscoveryClient @Inject constructor(
  private val transport: DiscoveryTransport,
  private val policy: AddressPolicy,
) {
  fun search(network: NetworkSnapshot): Flow<DiscoveredAddress> = flow {
    val nonce = DiscoveryProtocol.newNonce()
    val seen = HashSet<Pair<ServerId, HostPort>>()
    transport.probe(
      DiscoveryProtocol.probe(nonce),
      Broadcast.targets(network),
      DiscoveryProtocol.SCHEDULE,
      DiscoveryProtocol.LISTEN,
    ).collect { datagram ->
      if (!policy.allowsCandidate(datagram.sourceHost)) return@collect
      val reply = DiscoveryProtocol.parseReply(datagram.bytes, nonce) ?: return@collect
      val address = HostPort(datagram.sourceHost, reply.httpPort)
      if (seen.add(reply.serverId to address)) emit(DiscoveredAddress(reply, address))
    }
  }
}

/** Where the probe goes: the limited broadcast and the directed broadcast of the Wi-Fi network (architecture 6.1). */
internal object Broadcast {
  private const val OCTETS = 4
  private const val BITS = 32
  private const val MAX_OCTET = 255
  private const val BYTE_BITS = 8

  /** Prefixes with a directed broadcast address: /31 and /32 have none. */
  private val PREFIXES = 1..30

  fun targets(network: NetworkSnapshot): List<String> =
    listOfNotNull(DiscoveryProtocol.LIMITED_BROADCAST, directed(network.ipv4, network.prefixLength)).distinct()

  /** The directed broadcast of [ipv4]/[prefix] (`192.0.2.20`/24 → `192.0.2.255`), or null without one. */
  fun directed(ipv4: String?, prefix: Int?): String? {
    if (ipv4 == null || prefix == null || prefix !in PREFIXES) return null
    val octets = ipv4.split('.').map { it.toIntOrNull() ?: return null }
    if (octets.size != OCTETS || octets.any { it !in 0..MAX_OCTET }) return null
    val address = octets.fold(0L) { value, octet -> (value shl BYTE_BITS) or octet.toLong() }
    val hostBits = (1L shl (BITS - prefix)) - 1
    val broadcast = address or hostBits
    return (OCTETS - 1 downTo 0).joinToString(".") { shift -> ((broadcast shr (shift * BYTE_BITS)) and 0xff).toString() }
  }
}

/**
 * The list Connect shows (spec F-02 behaviour 3, D-39; architecture 6.1): every confirmed answer is its own row, in the
 * order they arrived. Names and server ids are public, so two answers with one name (compared on
 * [ComputerNames.conflictKey]: without invisible characters, case, white space or compatibility forms), or one server
 * id at two addresses, are a **conflict**, and so is a row already marked when it was confirmed (its `hello` gave
 * another name than its discovery reply): those rows are marked, the state carries the note's flag, and **nothing is
 * preselected**. Otherwise exactly one selectable computer (phone access on, not too old) is preselected.
 */
internal object FoundList {
  fun state(rows: List<FoundComputer>): SearchState.Found {
    val names = rows.groupingBy { ComputerNames.conflictKey(it.name) }.eachCount()
    val addressesById = rows.groupBy { it.serverId }.mapValues { (_, same) -> same.map { it.address }.toSet().size }
    val marked = rows.map { row ->
      val sameName = (names[ComputerNames.conflictKey(row.name)] ?: 0) > 1
      row.copy(conflict = row.conflict || sameName || (addressesById[row.serverId] ?: 0) > 1)
    }
    val conflict = marked.any { it.conflict }
    val preselected = marked.singleOrNull()?.takeIf { !conflict && it.phoneAccess && !it.tooOld }
    return SearchState.Found(marked, preselected, conflict)
  }
}
