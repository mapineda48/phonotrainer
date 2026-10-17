package com.mapineda48.phonotrainer.core.connectivity.platform

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.NetworkKind
import com.mapineda48.phonotrainer.core.domain.model.NetworkSnapshot
import java.net.InetAddress
import org.junit.Test

/** The callbacks of `ConnectivityManager` reduced to one snapshot (architecture 5.1, 6.1). */
class NetworkTrackerTest {
  private fun literal(vararg octets: Int): InetAddress = InetAddress.getByAddress(octets.map { it.toByte() }.toByteArray())

  @Test
  fun aWifiWithoutInternetIsStillTheHomeNetworkWithItsAddressAndPrefix() {
    val tracker = NetworkTracker()
    tracker.homeCapabilities(handle = 100, transport = Transport.WIFI, validated = false)
    val snapshot = tracker.homeAddress(100, "192.0.2.20", 24)

    assertThat(snapshot).isEqualTo(NetworkSnapshot(NetworkKind.WIFI, 100, "192.0.2.20", 24, vpnActive = false, validated = false))
  }

  @Test
  fun withoutWifiOrEthernetAnyCellularNetworkIsMobileOnlyAndNothingIsNone() {
    val tracker = NetworkTracker()
    assertThat(tracker.snapshot().kind).isEqualTo(NetworkKind.NONE)
    assertThat(tracker.cellular(200, available = true).kind).isEqualTo(NetworkKind.MOBILE_ONLY)
    assertThat(tracker.homeCapabilities(100, Transport.WIFI, true).kind).isEqualTo(NetworkKind.WIFI)
    assertThat(tracker.homeLost(100).kind).isEqualTo(NetworkKind.MOBILE_ONLY)
    assertThat(tracker.cellular(200, available = false).kind).isEqualTo(NetworkKind.NONE)
  }

  @Test
  fun aNetworkWithAnAddressIsPreferredThenWifiThenTheNewest() {
    val tracker = NetworkTracker()
    tracker.homeCapabilities(100, Transport.ETHERNET, true)
    tracker.homeAddress(100, "192.0.2.30", 24)
    tracker.homeCapabilities(101, Transport.WIFI, true)
    assertThat(tracker.snapshot().networkHandle).isEqualTo(100)

    tracker.homeAddress(101, "192.0.2.20", 24)
    assertThat(tracker.snapshot().networkHandle).isEqualTo(101)
    assertThat(tracker.snapshot().kind).isEqualTo(NetworkKind.WIFI)

    tracker.homeLost(101)
    assertThat(tracker.snapshot().kind).isEqualTo(NetworkKind.ETHERNET)
  }

  @Test
  fun aVpnIsReportedButNeverUsed() {
    val tracker = NetworkTracker()
    tracker.homeCapabilities(100, Transport.WIFI, true)
    val snapshot = tracker.vpn(300, available = true)

    assertThat(snapshot.vpnActive).isTrue()
    assertThat(snapshot.networkHandle).isEqualTo(100)
    assertThat(tracker.vpn(300, available = false).vpnActive).isFalse()
  }

  @Test
  fun addressesOfAnUnknownNetworkAreIgnored() {
    val tracker = NetworkTracker()

    assertThat(tracker.homeAddress(100, "192.0.2.20", 24).kind).isEqualTo(NetworkKind.NONE)
  }

  @Test
  fun theFirstUsableAddressOfTheLinkIsTakenWhenItIsVersionFour() {
    val v6 = InetAddress.getByAddress(ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[2] = 0x0d; it[3] = 0xb8.toByte() })

    assertThat(NetworkTracker.ipv4Of(listOf(v6 to 64, literal(127, 0, 0, 1) to 8, literal(192, 0, 2, 20) to 24)))
      .isEqualTo("192.0.2.20" to 24)
    assertThat(NetworkTracker.ipv4Of(listOf(literal(0, 0, 0, 0) to 0, v6 to 64))).isNull()
  }
}
