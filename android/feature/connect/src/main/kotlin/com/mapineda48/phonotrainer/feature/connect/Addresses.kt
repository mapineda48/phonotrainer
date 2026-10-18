package com.mapineda48.phonotrainer.feature.connect

import androidx.annotation.StringRes
import com.mapineda48.phonotrainer.core.domain.model.HostPort

/**
 * What the learner types in "Computer address and port" (spec F-02 behaviour 4; architecture 6.1: an IPv4 literal and
 * a port): the address and port exactly as the computer's terminal prints them, with spaces, an `http://` in front or
 * a `/` after forgiven. Without a port, PhonoTrainer's default HTTP port (a product constant, spec 7.2) is used.
 * Host names are refused: the phone never resolves a name (architecture 6.1).
 */
internal object AddressInput {
  /** `phonotrainer ui`'s default port (server-contract 2.1), the same on every installation. */
  const val DEFAULT_PORT = 8000

  /** The longest text the field keeps: "255.255.255.255:65535" with some room for what is forgiven. */
  const val MAX_LENGTH = 40

  fun parse(text: String): HostPort? {
    var rest = text.trim().filterNot { it.isWhitespace() }
    if (rest.startsWith("http://", ignoreCase = true)) rest = rest.substring("http://".length)
    rest = rest.trimEnd('/')
    if (rest.isEmpty()) return null
    val colon = rest.lastIndexOf(':')
    val host = if (colon >= 0) rest.substring(0, colon) else rest
    val port = if (colon >= 0) rest.substring(colon + 1).toPortOrNull() ?: return null else DEFAULT_PORT
    return if (isIpv4(host)) HostPort(host, port) else null
  }

  private fun String.toPortOrNull(): Int? {
    if (isEmpty() || length > 5 || !all { it in '0'..'9' }) return null
    return toInt().takeIf { it in 1..65_535 }
  }

  /** Four decimal octets 0-255, ASCII digits only, no leading zeros (an octal-looking octet is refused). */
  fun isIpv4(host: String): Boolean {
    val parts = host.split('.')
    return parts.size == 4 && parts.all { part ->
      part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } &&
        (part.length == 1 || part[0] != '0') && part.toInt() <= 255
    }
  }

  /** The octets of an IPv4 literal, or null. */
  fun octets(host: String): IntArray? = if (isIpv4(host)) host.split('.').map { it.toInt() }.toIntArray() else null
}

/**
 * The class of an address, never the address (architecture 20.3, security review L-7): what "Copy diagnostics" says
 * instead of the computer's address ("private 192.168/16", "link-local").
 */
internal enum class AddressClass(@StringRes val words: Int) {
  PRIVATE_10(R.string.address_class_private_10),
  PRIVATE_172(R.string.address_class_private_172),
  PRIVATE_192(R.string.address_class_private_192),
  LINK_LOCAL(R.string.address_class_link_local),
  LOOPBACK(R.string.address_class_loopback),
  OTHER(R.string.address_class_other),
  ;

  companion object {
    fun of(host: String): AddressClass {
      val o = AddressInput.octets(host) ?: return OTHER
      return when {
        o[0] == 10 -> PRIVATE_10
        o[0] == 172 && o[1] in 16..31 -> PRIVATE_172
        o[0] == 192 && o[1] == 168 -> PRIVATE_192
        o[0] == 169 && o[1] == 254 -> LINK_LOCAL
        o[0] == 127 -> LOOPBACK
        else -> OTHER
      }
    }
  }
}
