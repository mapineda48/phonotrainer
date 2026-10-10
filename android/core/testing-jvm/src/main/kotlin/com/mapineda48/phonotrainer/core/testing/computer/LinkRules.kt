package com.mapineda48.phonotrainer.core.testing.computer

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

/** Link checks of server-contract 4.5 and the site labels of 6.3, without any DNS. */
internal object LinkRules {
  const val MAX_URL_CHARS = 2_048

  /** The trimmed link, or null when it is not an acceptable http(s) URL (`400 invalid_url`). */
  fun accept(raw: String): String? {
    val url = raw.trim()
    if (url.isEmpty() || url.length > MAX_URL_CHARS) return null
    val scheme = url.substringBefore(':', "").lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return null
    val parsed = url.toHttpUrlOrNull() ?: return null
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
    if (url.substringAfter("://").substringBefore('/').contains('@')) return null
    return url
  }

  fun host(url: String): String? = url.toHttpUrlOrNull()?.host

  /** `source.site` (server-contract 6.3). */
  fun site(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return "other"
    val host = parsed.host.lowercase(Locale.ROOT)
    val youtubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com")
    return when {
      host in youtubeHosts && parsed.encodedPath.startsWith("/shorts/") -> "youtube_shorts"
      host in youtubeHosts || host == "music.youtube.com" || host == "youtu.be" -> "youtube"
      host in setOf("x.com", "twitter.com", "mobile.twitter.com", "t.co") -> "x"
      else -> "other"
    }
  }

  /**
   * How [host] would resolve. IP literals are classified by the rules of 4.5 (IPv4-mapped unwrapped, NAT64, 6to4 and
   * Teredo refused, everything not global unicast refused); names answer from [names] and are public otherwise.
   */
  fun classify(host: String, names: Map<String, LinkHostAnswer>): LinkHostAnswer {
    names[host.lowercase(Locale.ROOT)]?.let { return it }
    val literal = literal(host) ?: return LinkHostAnswer.PUBLIC
    return if (isGlobalUnicast(literal)) LinkHostAnswer.PUBLIC else LinkHostAnswer.REFUSED
  }

  private fun literal(host: String): InetAddress? {
    val bare = host.removePrefix("[").removeSuffix("]")
    val octets = bare.split('.').map { it.toIntOrNull() }
    // Literals are parsed by hand or by OkHttp's canonical IPv6 form, so no DNS lookup can ever happen.
    return when {
      octets.size == 4 && octets.all { it != null && it in 0..255 } ->
        InetAddress.getByAddress(ByteArray(4) { octets[it]!!.toByte() })
      bare.contains(':') && (bare[0] == ':' || Character.digit(bare[0], 16) >= 0) ->
        runCatching { InetAddress.getByName(bare) }.getOrNull()
      else -> null
    }
  }

  private fun isGlobalUnicast(address: InetAddress): Boolean = when (address) {
    is Inet4Address -> ipv4Global(address.address)
    is Inet6Address -> ipv6Global(address.address)
    else -> false
  }

  private fun ipv4Global(b: ByteArray): Boolean {
    val a0 = b[0].toInt() and 0xff
    val a1 = b[1].toInt() and 0xff
    val a2 = b[2].toInt() and 0xff
    val refused = a0 == 0 || a0 == 10 || a0 == 127 || a0 >= 224 ||
      (a0 == 100 && a1 in 64..127) || // CGNAT
      (a0 == 169 && a1 == 254) || (a0 == 172 && a1 in 16..31) || (a0 == 192 && a1 == 168) ||
      (a0 == 192 && a1 == 0 && (a2 == 0 || a2 == 2)) || (a0 == 198 && (a1 == 18 || a1 == 19)) ||
      (a0 == 198 && a1 == 51 && a2 == 100) || (a0 == 203 && a1 == 0 && a2 == 113) // documentation, benchmarking
    return !refused
  }

  private fun ipv6Global(b: ByteArray): Boolean {
    val words = IntArray(8) { ((b[it * 2].toInt() and 0xff) shl 8) or (b[it * 2 + 1].toInt() and 0xff) }
    val mapped = words.take(5).all { it == 0 } && words[5] == 0xffff
    if (mapped) return ipv4Global(b.copyOfRange(12, 16))
    val nat64 = (words[0] == 0x64 && words[1] == 0xff9b && (words[2] == 0 || words[2] == 1))
    val sixToFour = words[0] == 0x2002
    val teredo = words[0] == 0x2001 && words[1] == 0
    val documentation = words[0] == 0x2001 && words[1] == 0x0db8
    val local = words.all { it == 0 } || (words.take(7).all { it == 0 } && words[7] == 1) ||
      (words[0] and 0xfe00) == 0xfc00 || (words[0] and 0xffc0) == 0xfe80 || (words[0] and 0xff00) == 0xff00
    return !(nat64 || sixToFour || teredo || documentation || local) && (words[0] and 0xe000) == 0x2000
  }
}
