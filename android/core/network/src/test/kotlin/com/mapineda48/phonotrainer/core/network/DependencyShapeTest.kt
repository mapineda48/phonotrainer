package com.mapineda48.phonotrainer.core.network

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.network.api.AllowedPeers
import com.mapineda48.phonotrainer.core.network.api.VerifiedPeers
import com.mapineda48.phonotrainer.core.testing.fake.network.FakeAllowedPeers
import com.mapineda48.phonotrainer.core.testing.fake.network.FakeVerifiedPeers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The dependency-shape spike of delivery review D-18 (work-plan WP-02 M1): an Android library's unit tests use the
 * JVM fixtures of `:core:testing-jvm`, whose fakes implement the `:core:network-api` interfaces this library
 * implements, and the build resolves with no variant or task cycle. The Robolectric case also proves the convention
 * plugin's generated `robolectric.properties` (sdk 35, architecture 21.2).
 */
@RunWith(RobolectricTestRunner::class)
class DependencyShapeTest {
  private val address = HostPort("192.0.2.10", 8000)

  @Test
  fun fakesFromTestingJvmStandInForTheNetworkApi() = runTest {
    val allowed: AllowedPeers = FakeAllowedPeers()
    val verified: VerifiedPeers = FakeVerifiedPeers()

    allowed.withCandidate(address) { verified.markVerified(address, ServerId("server-a")) }
    allowed.setCurrent(address)

    assertThat(verified.isVerified(address)).isTrue()
    assertThat(allowed.current.value).isEqualTo(address)
  }

  @Test
  fun robolectricRunsOnTheGeneratedSdkLevel() {
    val context = ApplicationProvider.getApplicationContext<Context>()

    assertThat(Build.VERSION.SDK_INT).isEqualTo(35)
    assertThat(context.applicationInfo.minSdkVersion).isEqualTo(29)
  }
}
