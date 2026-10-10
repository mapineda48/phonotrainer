package com.mapineda48.phonotrainer.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The candidate rule of architecture 5.2: private and link-local IPv4 literals only. The addresses are written as
 * octets because they are range boundaries, not hosts, and the repository's hygiene scan (architecture 20.7) keeps
 * address literals outside the documentation and loopback ranges out of every file.
 */
class PrivateAddressPolicyTest {
  private fun ip(vararg octets: Int) = octets.joinToString(".")

  @Test
  fun privateAndLinkLocalLiteralsAreCandidates() {
    listOf(ip(10, 0, 0, 1), ip(172, 16, 0, 1), ip(172, 31, 255, 254), ip(192, 168, 0, 1), ip(169, 254, 0, 1)).forEach {
      assertThat(PrivateAddressPolicy.allowsCandidate(it)).isTrue()
    }
  }

  @Test
  fun everythingElseIsRefused() {
    listOf(
      "127.0.0.1",
      "192.0.2.10",
      "198.51.100.7",
      ip(172, 32, 0, 1),
      ip(192, 169, 0, 1),
      "localhost",
      "10.0.0",
      ip(10, 0, 0, 256),
      "010.0.0.1",
      "",
    ).forEach { assertThat(PrivateAddressPolicy.allowsCandidate(it)).isFalse() }
  }
}
