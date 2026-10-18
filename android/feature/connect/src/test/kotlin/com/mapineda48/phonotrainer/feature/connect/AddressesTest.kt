package com.mapineda48.phonotrainer.feature.connect

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import org.junit.Test

/**
 * "Computer address and port" (spec F-02 behaviour 4): an IPv4 literal and a port as the computer prints them, the
 * default port when none is typed, never a host name; and the class that "Copy diagnostics" says instead of an address
 * (architecture 20.3). Addresses here are documentation addresses or the network addresses of the private blocks.
 */
class AddressesTest {
  @Test
  fun theAddressAndPortAsTheComputerPrintsThem() {
    assertThat(AddressInput.parse("192.0.2.10:8000")).isEqualTo(HostPort("192.0.2.10", 8000))
    assertThat(AddressInput.parse(" 192.0.2.10 : 8123 ")).isEqualTo(HostPort("192.0.2.10", 8123))
    assertThat(AddressInput.parse("http://192.0.2.10:8000/")).isEqualTo(HostPort("192.0.2.10", 8000))
  }

  @Test
  fun withoutAPortTheDefaultOneIsUsed() {
    assertThat(AddressInput.parse("192.0.2.10")).isEqualTo(HostPort("192.0.2.10", AddressInput.DEFAULT_PORT))
  }

  @Test
  fun anythingElseIsNotAnAddress() {
    listOf(
      "", "   ", "computer.local:8000", "192.0.2:8000", "192.0.2.256:8000", "192.0.2.10:0", "192.0.2.10:65536",
      "192.0.2.10:", "192.0.2.010:8000", "192.0.2.10:80a", "[2001:db8::1]:8000", "192.0.2.10.5",
      "\u0661\u0669\u0662.0.2.10:8000",
    ).forEach { assertThat(AddressInput.parse(it)).isNull() }
  }

  @Test
  fun anAddressIsSaidAsItsClassOnly() {
    assertThat(AddressClass.of("10.0.0.0")).isEqualTo(AddressClass.PRIVATE_10)
    assertThat(AddressClass.of("172.16.0.0")).isEqualTo(AddressClass.PRIVATE_172)
    assertThat(AddressClass.of("192.168.0.0")).isEqualTo(AddressClass.PRIVATE_192)
    assertThat(AddressClass.of("169.254.0.0")).isEqualTo(AddressClass.LINK_LOCAL)
    assertThat(AddressClass.of("127.0.0.1")).isEqualTo(AddressClass.LOOPBACK)
    assertThat(AddressClass.of("192.0.2.10")).isEqualTo(AddressClass.OTHER)
    // Just past 172.16/12 (built from its octets: the repository holds no literal host address outside the
    // documentation and special blocks).
    assertThat(AddressClass.of(listOf(172, 32, 0, 0).joinToString("."))).isEqualTo(AddressClass.OTHER)
    assertThat(AddressClass.of("not an address")).isEqualTo(AddressClass.OTHER)
  }
}
