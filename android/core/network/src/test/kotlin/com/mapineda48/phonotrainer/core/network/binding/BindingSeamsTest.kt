package com.mapineda48.phonotrainer.core.network.binding

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.network.NetworkPolicyModule
import com.mapineda48.phonotrainer.core.testing.fake.network.FakeComputerNetworkBinder
import java.net.SocketException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The seams around the binding: the release binder is the client's own [NetworkBinding] (so every socket is bound and
 * a change evicts the pool), Android's EPERM reads as a VPN that blocks the binding, and the settable fake of
 * `:core:testing-jvm` records what `:core:connectivity` binds.
 */
@RunWith(RobolectricTestRunner::class)
class BindingSeamsTest {
  @Test
  fun theReleaseBinderIsTheClientsOwnBinding() {
    assertThat(NetworkPolicyModule.networkBinder()).isInstanceOf(NetworkBinding::class.java)
  }

  @Test
  fun anEpermBindingFailureIsAVpnThatBlocksTheNetwork() {
    val eperm = SocketException("Binding socket to network 101 failed: EPERM (Operation not permitted)")
    val other = SocketException("Binding socket to network 101 failed: ENONET (Machine is not on the network)")

    assertThat(AndroidNetworkPlatform.isPermissionDenied(eperm)).isTrue()
    assertThat(AndroidNetworkPlatform.isPermissionDenied(other)).isFalse()
  }

  @Test
  fun theSettableBinderRecordsEveryHandle() {
    val binder = FakeComputerNetworkBinder()

    binder.bind(101)
    binder.bind(null)
    binder.bind(102)

    assertThat(binder.handles).containsExactly(101L, null, 102L).inOrder()
    assertThat(binder.current).isEqualTo(102L)
  }
}
