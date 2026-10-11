package com.mapineda48.phonotrainer.core.analysis.links

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.domain.api.LinkParser
import com.mapineda48.phonotrainer.core.domain.model.ParsedLink
import com.mapineda48.phonotrainer.core.domain.model.Site
import org.junit.Test

/**
 * The link rules of architecture 8.2 and spec 7.7 / 8.2: every YouTube and X form, tracking parameters, `t.co`, the
 * 2,048-character limit, user-info and text without a link (SH-AC2, SH-AC7, SH-AC8's duplicate keys).
 */
class LinksV1Test {
  private val parser: LinkParser = LinksV1()

  private fun parse(text: String, subject: String? = null): ParsedLink? = parser.parse(text, subject)

  private fun link(text: String): ParsedLink = checkNotNull(parse(text)) { "no link in: $text" }

  @Test
  fun aSharedYoutubeLinkLosesItsShareToken() {
    // SH-AC2
    val parsed = link("Great scene https://youtu.be/abc?si=xyz")
    val expected = ParsedLink(
      shared = "https://youtu.be/abc?si=xyz", sent = "https://youtu.be/abc", key = "yt:abc", site = Site.YOUTUBE,
    )
    assertThat(parsed).isEqualTo(expected)
  }

  @Test
  fun everyYoutubeFormOfOneVideoHasOneKey() {
    val id = "dQw4w9WgXcQ"
    val forms = mapOf(
      "https://www.youtube.com/watch?v=$id" to Site.YOUTUBE,
      "https://youtube.com/watch?v=$id&feature=share" to Site.YOUTUBE,
      "https://m.youtube.com/watch?v=$id&si=abc" to Site.YOUTUBE,
      "https://music.youtube.com/watch?v=$id" to Site.YOUTUBE,
      "https://youtu.be/$id?t=42" to Site.YOUTUBE,
      "http://youtu.be/$id" to Site.YOUTUBE,
      "https://www.youtube.com/embed/$id" to Site.YOUTUBE,
      "https://www.youtube.com/live/$id?si=x" to Site.YOUTUBE,
      "https://youtube.com/shorts/$id?si=q" to Site.YOUTUBE_SHORTS,
      "https://www.youtube.com/shorts/$id" to Site.YOUTUBE_SHORTS,
      "https://m.youtube.com/shorts/$id?feature=share" to Site.YOUTUBE_SHORTS,
    )
    for ((url, site) in forms) {
      val parsed = link("Look: $url")
      assertWithMessage(url).that(parsed.key).isEqualTo("yt:$id")
      assertWithMessage(url).that(parsed.site).isEqualTo(site)
    }
  }

  @Test
  fun youtubeAndXLinksLoseTheTrackingParametersOnly() {
    assertThat(link("https://youtube.com/watch?si=abc&v=VID&utm_source=x&utm_medium=y&feature=share&t=42s").sent)
      .isEqualTo("https://youtube.com/watch?v=VID")
    // a video that also names a playlist keeps it: the computer downloads the video (spec 8.2 rule 4)
    assertThat(link("https://www.youtube.com/watch?v=VID&list=PL123&index=2&si=z").sent)
      .isEqualTo("https://www.youtube.com/watch?v=VID&list=PL123&index=2")
    assertThat(link("https://youtu.be/VID?t=30#comments").sent).isEqualTo("https://youtu.be/VID#comments")
    assertThat(link("https://x.com/someone/status/1234567890?s=20&t=AbCdE").sent)
      .isEqualTo("https://x.com/someone/status/1234567890")
    assertThat(link("https://t.co/AbC123?amp=1&s=09").sent).isEqualTo("https://t.co/AbC123?amp=1")
  }

  @Test
  fun aYoutubeLinkWithoutAVideoIsItsOwnKey() {
    val playlist = link("https://www.youtube.com/playlist?list=PL123&si=abc")
    assertThat(playlist.site).isEqualTo(Site.YOUTUBE)
    assertThat(playlist.sent).isEqualTo("https://www.youtube.com/playlist?list=PL123")
    assertThat(playlist.key).isEqualTo(playlist.sent)
    val channel = link("https://www.youtube.com/@someone")
    assertThat(channel.key).isEqualTo("https://www.youtube.com/@someone")
    assertThat(link("https://youtu.be/").key).isEqualTo("https://youtu.be/")
  }

  @Test
  fun everyXFormOfOnePostHasOneKey() {
    val forms = listOf(
      "https://x.com/someone/status/1234567890",
      "https://x.com/someone/status/1234567890?s=20",
      "https://www.x.com/someone/status/1234567890",
      "https://twitter.com/someone/status/1234567890?t=abc&s=19",
      "https://www.twitter.com/someone/status/1234567890",
      "https://mobile.twitter.com/someone/status/1234567890",
      "https://x.com/i/web/status/1234567890",
      "https://x.com/someone/status/1234567890/photo/1",
    )
    for (url in forms) {
      val parsed = link("Wow $url")
      assertWithMessage(url).that(parsed.site).isEqualTo(Site.X)
      assertWithMessage(url).that(parsed.key).isEqualTo("x:1234567890")
    }
    // a profile is not a post
    assertThat(link("https://x.com/someone").key).isEqualTo("https://x.com/someone")
  }

  @Test
  fun aTcoLinkCannotBeResolvedSoItIsItsOwnKey() {
    val parsed = link("A great clip https://t.co/AbCdEf123")
    assertThat(parsed.site).isEqualTo(Site.X)
    assertThat(parsed.sent).isEqualTo("https://t.co/AbCdEf123")
    assertThat(parsed.key).isEqualTo("https://t.co/AbCdEf123")
  }

  @Test
  fun otherWebsitesAreSentAsShared() {
    val parsed = link("Read https://example.com/page?utm_source=news&id=7&si=1#top")
    assertThat(parsed.site).isEqualTo(Site.OTHER)
    assertThat(parsed.sent).isEqualTo("https://example.com/page?utm_source=news&id=7&si=1#top")
    assertThat(parsed.key).isEqualTo(parsed.sent)
    assertThat(link("https://vimeo.com/123").site).isEqualTo(Site.OTHER)
    assertThat(link("https://notyoutube.com/watch?v=abc").site).isEqualTo(Site.OTHER)
  }

  @Test
  fun theFirstLinkIsTakenAndTrimmed() {
    assertThat(link("Watch this: https://youtu.be/abc.").shared).isEqualTo("https://youtu.be/abc")
    assertThat(link("Really?! https://youtu.be/abc?!").shared).isEqualTo("https://youtu.be/abc")
    assertThat(link("(see https://youtu.be/abc)").shared).isEqualTo("https://youtu.be/abc")
    assertThat(link("(https://en.wikipedia.org/wiki/Foo_(bar)), and more").shared)
      .isEqualTo("https://en.wikipedia.org/wiki/Foo_(bar)")
    assertThat(link("<https://youtu.be/abc>").shared).isEqualTo("https://youtu.be/abc")
    assertThat(link("\"https://youtu.be/abc\" said someone").shared).isEqualTo("https://youtu.be/abc")
    assertThat(link("'https://youtu.be/abc'").shared).isEqualTo("https://youtu.be/abc")
    assertThat(link("Title\nhttps://youtu.be/abc\nmore").shared).isEqualTo("https://youtu.be/abc")
    assertThat(link("https://youtu.be/abc more").shared).isEqualTo("https://youtu.be/abc")
    assertThat(link("https://youtu.be/first https://youtu.be/second").key).isEqualTo("yt:first")
    val upper = link("HTTPS://YOUTU.BE/abc?si=x")
    assertThat(upper.site).isEqualTo(Site.YOUTUBE)
    assertThat(upper.sent).isEqualTo("HTTPS://YOUTU.BE/abc")
    assertThat(upper.key).isEqualTo("yt:abc")
  }

  @Test
  fun textWithoutALinkCreatesNothing() {
    // SH-AC7
    assertThat(parse("Just some words")).isNull()
    assertThat(parse("")).isNull()
    assertThat(parse("ftp://example.com/file and mailto:someone@example.com")).isNull()
    assertThat(parse("youtu.be/abc without a scheme")).isNull()
    assertThat(parse("https://")).isNull()
    assertThat(parse("https:///path")).isNull()
    assertThat(parse("https://:443/path")).isNull()
    assertThat(parse("https://example.com:http/")).isNull()
  }

  @Test
  fun theSubjectIsReadOnlyWhenTheTextHasNoLink() {
    assertThat(parse("no link here", subject = "https://youtu.be/abc")?.key).isEqualTo("yt:abc")
    assertThat(parse("https://youtu.be/text", subject = "https://youtu.be/subject")?.key).isEqualTo("yt:text")
    assertThat(parse("no link here", subject = "nor here")).isNull()
    assertThat(parse("no link here", subject = null)).isNull()
  }

  @Test
  fun linksAreAtMost2048Characters() {
    val prefix = "https://example.com/"
    val longest = prefix + "a".repeat(LinksV1.MAX_URL_CHARS - prefix.length)
    assertThat(link("see $longest").shared).hasLength(LinksV1.MAX_URL_CHARS)
    assertThat(parse("see ${longest}b")).isNull()
    // trailing punctuation is trimmed before the limit applies
    assertThat(link("see $longest.").shared).isEqualTo(longest)
  }

  @Test
  fun aLinkWithUserInfoIsRefused() {
    assertThat(parse("https://user:secret@youtu.be/abc")).isNull()
    assertThat(parse("https://someone@example.com/page")).isNull()
    // an @ in the path is not user-info
    assertThat(link("https://www.youtube.com/@someone").site).isEqualTo(Site.YOUTUBE)
    assertThat(link("https://example.com/a@b").site).isEqualTo(Site.OTHER)
    // a refused first link is not replaced by a later one
    assertThat(parse("https://u@example.com https://youtu.be/abc")).isNull()
  }

  @Test
  fun portsAndAddressesAreKept() {
    assertThat(link("https://example.com:8080/x").sent).isEqualTo("https://example.com:8080/x")
    assertThat(link("http://[2001:db8::1]:8080/x").site).isEqualTo(Site.OTHER)
    assertThat(LinksV1.RULE_VERSION).isEqualTo(1)
  }
}
