package com.mapineda48.phonotrainer.core.analysis.links

import com.mapineda48.phonotrainer.core.domain.api.LinkParser
import com.mapineda48.phonotrainer.core.domain.model.ParsedLink
import com.mapineda48.phonotrainer.core.domain.model.Site
import java.util.Locale
import javax.inject.Inject

/**
 * Link rules v1 (architecture 8.2, spec 7.7 and 8.2), the [LinkParser] the share sheet and "Add a clip" use (bound by
 * WP-09). Pure: no network, no DNS (a `t.co` link cannot be resolved without one, so it is its own key).
 *
 * 1. The first `http://` or `https://` URL of the shared text (of the subject when the text has none), up to white
 *    space or `<`, `>`, `"`, `'`; trailing `.`, `,`, `;`, `:`, `!`, `?` and an unbalanced `)` are trimmed. A URL
 *    longer than [MAX_URL_CHARS], with a user-info part (`user@host`), with an invalid port or without a host is
 *    refused: nothing is created ("There's no link in what you shared.").
 * 2. The site: YouTube (`youtube.com`, `www.`, `m.`, `music.`, `youtu.be`), YouTube Shorts (a `/shorts/` path on
 *    `youtube.com`, `www.` or `m.`), X (`x.com`, `twitter.com`, their `www.` forms, `mobile.twitter.com`, `t.co`),
 *    otherwise Other website. The host lists are the computer's `source.site` rule (server-contract 6.3), plus the
 *    `www.` forms of X.
 * 3. YouTube and X links lose the parameters `si`, `feature`, `utm_*`, `s` and `t` (privacy and de-duplication; `t`
 *    is a start time, and the analysis always covers the whole video); other links are sent as shared.
 * 4. The duplicate key: `yt:<video id>` for every YouTube form of one video (`watch?v=`, `youtu.be/`, `/shorts/`,
 *    `/embed/`, `/live/`, `/v/`), `x:<status id>` for every X form of one post (`/<user>/status/<id>`,
 *    `/i/web/status/<id>`), otherwise the sent URL.
 */
class LinksV1 @Inject constructor() : LinkParser {
  override fun parse(sharedText: String, subject: String?): ParsedLink? {
    val shared = firstUrl(sharedText) ?: subject?.let(::firstUrl) ?: return null
    if (shared.length > MAX_URL_CHARS) return null
    val url = Url.of(shared) ?: return null
    val site = siteOf(url)
    val sent = if (site == Site.OTHER) shared else url.withoutTracking()
    return ParsedLink(shared = shared, sent = sent, key = keyOf(url, site) ?: sent, site = site)
  }

  /**
   * The first http(s) URL of [text], trimmed, or null when there is none. Only the first is considered: when it is
   * refused, nothing is created (the subject is read only when the text holds no URL at all).
   */
  private fun firstUrl(text: String): String? {
    val match = SCHEME.find(text) ?: return null
    var end = match.range.last + 1
    while (end < text.length && !isStop(text[end])) end += 1
    var url = text.substring(match.range.first, end)
    while (url.isNotEmpty()) {
      val last = url.last()
      url = when {
        last in TRAILING_PUNCTUATION -> url.dropLast(1)
        last == ')' && url.count { it == ')' } > url.count { it == '(' } -> url.dropLast(1)
        else -> break
      }
    }
    return url
  }

  private fun isStop(char: Char): Boolean = char.isWhitespace() || char in "<>\"'"

  private fun siteOf(url: Url): Site = when (url.host) {
    in YOUTUBE_HOSTS -> if (url.host != MUSIC && url.path.startsWith("/shorts/")) Site.YOUTUBE_SHORTS else Site.YOUTUBE
    YOUTU_BE -> Site.YOUTUBE
    in X_HOSTS -> Site.X
    else -> Site.OTHER
  }

  private fun keyOf(url: Url, site: Site): String? = when (site) {
    Site.YOUTUBE, Site.YOUTUBE_SHORTS -> youtubeId(url)?.let { "yt:$it" }
    Site.X -> statusId(url)?.let { "x:$it" }
    Site.OTHER -> null
  }

  private fun youtubeId(url: Url): String? {
    val segments = url.segments
    val id = when {
      url.host == YOUTU_BE -> segments.firstOrNull()
      segments.firstOrNull() == "watch" -> url.parameter("v")
      segments.size >= 2 && segments[0] in VIDEO_PATHS -> segments[1]
      else -> null
    }
    return id?.takeIf { it.isNotEmpty() && it.all(::isVideoIdChar) }
  }

  private fun statusId(url: Url): String? {
    if (url.host == T_CO) return null
    val segments = url.segments
    val at = segments.indexOfFirst { it == "status" || it == "statuses" }
    val id = segments.getOrNull(at + 1).takeIf { at >= 0 } ?: return null
    return id.takeIf { it.isNotEmpty() && it.all(Char::isAsciiDigit) }
  }

  private fun isVideoIdChar(char: Char): Boolean = char.isAsciiLetterOrDigit() || char == '-' || char == '_'

  /** The parts of an http(s) URL these rules need, split by hand: no URI class rejects what a browser accepts. */
  private class Url(
    /** Lower-case host, without a trailing dot. */
    val host: String,
    /** The path as written ("" or starting with "/"). */
    val path: String,
    /** The text before "?" or "#" (scheme, authority and path, as written). */
    val base: String,
    /** The query without "?", or null when there is none. */
    val query: String?,
    /** The fragment with its "#", or "". */
    val fragment: String,
  ) {
    val segments: List<String> get() = path.split('/').filter { it.isNotEmpty() }

    /** The value of the first query parameter [name], as written. */
    fun parameter(name: String): String? =
      query?.split('&')?.firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")

    /** The URL without the tracking parameters of rule 3; "?" goes too when nothing is left. */
    fun withoutTracking(): String {
      val kept = query?.split('&')?.filter { part -> part.isNotEmpty() && !isTracking(part.substringBefore('=')) }
      val rebuilt = if (kept.isNullOrEmpty()) base else base + "?" + kept.joinToString("&")
      return rebuilt + fragment
    }

    companion object {
      fun of(raw: String): Url? {
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd < 0) return null
        val scheme = raw.substring(0, schemeEnd).lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        val rest = raw.substring(schemeEnd + 3)
        val authorityEnd = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        if (authority.contains('@')) return null // user-info: refused (spec 8.2 rule 1)
        val host = hostOf(authority) ?: return null
        val beforeFragment = raw.substringBefore('#')
        val fragment = raw.substring(beforeFragment.length)
        val base = beforeFragment.substringBefore('?')
        val query = if (beforeFragment.length > base.length) beforeFragment.substring(base.length + 1) else null
        val path = rest.substring(authorityEnd).substringBefore('#').substringBefore('?')
        return Url(host, path, base, query, fragment)
      }

      /** The lower-case host of [authority] ("host", "host:port", "[v6]:port"), or null when it is not valid. */
      private fun hostOf(authority: String): String? {
        val (host, port) = if (authority.startsWith("[")) {
          val close = authority.indexOf(']')
          if (close < 0) return null
          authority.substring(0, close + 1) to authority.substring(close + 1).removePrefix(":")
        } else {
          authority.substringBefore(':') to authority.substringAfter(':', "")
        }
        if (host.isEmpty() || host == "[]") return null
        if (port.isNotEmpty() && (port.length > MAX_PORT_DIGITS || !port.all(Char::isAsciiDigit))) return null
        return host.lowercase(Locale.ROOT).removeSuffix(".")
      }

      private fun isTracking(name: String): Boolean = name in TRACKING || name.startsWith("utm_")

      private val TRACKING = setOf("si", "feature", "s", "t")
      private const val MAX_PORT_DIGITS = 5
    }
  }

  companion object {
    /** The version of these rules (a change of rule changes `link_key`s, which only matters for duplicates). */
    const val RULE_VERSION: Int = 1

    /** The longest link accepted (architecture 8.2). */
    const val MAX_URL_CHARS: Int = 2_048

    private val SCHEME = Regex("https?://", RegexOption.IGNORE_CASE)
    private const val TRAILING_PUNCTUATION = ".,;:!?"

    private const val MUSIC = "music.youtube.com"
    private const val YOUTU_BE = "youtu.be"
    private const val T_CO = "t.co"
    private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", MUSIC)
    private val X_HOSTS = setOf("x.com", "www.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com", T_CO)
    private val VIDEO_PATHS = setOf("shorts", "embed", "live", "v")
  }
}

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || isAsciiDigit()
