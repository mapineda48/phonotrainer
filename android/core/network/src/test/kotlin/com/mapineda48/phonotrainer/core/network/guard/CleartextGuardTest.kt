package com.mapineda48.phonotrainer.core.network.guard

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.DownloadOutcome
import com.mapineda48.phonotrainer.core.network.api.DownloadRequest
import com.mapineda48.phonotrainer.core.network.http.GuardedException
import com.mapineda48.phonotrainer.core.network.peers.ComputerPeers
import com.mapineda48.phonotrainer.core.network.support.CountingDns
import com.mapineda48.phonotrainer.core.network.support.LoopbackPolicy
import com.mapineda48.phonotrainer.core.network.support.TestStack
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.testing.fake.network.FakeAddressPolicy
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Cleartext confinement (architecture 5.2, spec 11.6, X-AC6): every request is refused before a byte is sent (not
 * even a TCP connection is opened) unless it goes to the current address or the single candidate being checked, over
 * plain HTTP to an IPv4 literal the address policy accepts; and no name is ever resolved (architecture 5.1).
 */
class CleartextGuardTest {
  @get:Rule
  val folder = TemporaryFolder()

  private val computer = FakeComputer().start()
  private val dns = CountingDns()
  private val stack = TestStack(computer, dns = dns)

  /** A listener on another loopback port: a host the phone must never reach. */
  private val elsewhere = ServerSocket(0, 1, InetAddress.getByName(FakeComputer.HOST)).apply { soTimeout = 300 }
  private val elsewhereAddress = HostPort(FakeComputer.HOST, elsewhere.localPort)

  @After
  fun close() {
    elsewhere.close()
    stack.close()
    computer.close()
  }

  private fun assertNothingReachedElsewhere() {
    assertThrows(SocketTimeoutException::class.java) { elsewhere.accept().close() }
  }

  @Test
  fun withNoAddressEveryCallIsRefusedWithoutAnyConnection() = runBlocking<Unit> {
    assertThat(stack.client.library()).isEqualTo(ApiResult.Failed(AppError.Computer.Guarded))
    assertThat(stack.client.hello(elsewhereAddress)).isEqualTo(ApiResult.Failed(AppError.Computer.Guarded))
    assertThat(stack.client.legacyHealth(elsewhereAddress)).isEqualTo(ApiResult.Failed(AppError.Computer.Guarded))

    assertNothingReachedElsewhere()
    assertThat(computer.requests).isEmpty()
  }

  @Test
  fun anAddressThatIsNeitherCurrentNorTheCandidateIsNeverReached() = runBlocking<Unit> {
    stack.pair()

    val outcome = stack.peers.withCandidate(computer.address) { stack.client.hello(elsewhereAddress) }

    assertThat(outcome).isEqualTo(ApiResult.Failed(AppError.Computer.Guarded))
    assertNothingReachedElsewhere()
  }

  @Test
  fun aCandidateIsReachableOnlyWhileItIsBeingChecked() = runBlocking<Unit> {
    val inside = stack.peers.withCandidate(computer.address) { stack.client.hello(computer.address) }
    val after = stack.client.hello(computer.address)

    assertThat(inside).isInstanceOf(ApiResult.Ok::class.java)
    assertThat(after).isEqualTo(ApiResult.Failed(AppError.Computer.Guarded))
    assertThat(computer.requests).hasSize(1)
  }

  @Test
  fun anAddressThePolicyRefusesIsNeverReachedEvenAsTheCurrentOne() {
    val refusing = FakeAddressPolicy { false }
    TestStack(computer, policy = refusing).use { strict ->
      runBlocking {
        strict.peers.setCurrent(computer.address)
        val hello = strict.peers.withCandidate(computer.address) { strict.client.hello(computer.address) }
        val library = strict.client.library()

        assertThat(hello).isEqualTo(ApiResult.Failed(AppError.Computer.Guarded))
        assertThat(library).isEqualTo(ApiResult.Failed(AppError.Computer.Guarded))
        assertThat(computer.requests).isEmpty()
      }
    }
  }

  @Test
  fun aHostNameIsNeverResolvedNorReached() = runBlocking<Unit> {
    stack.pair()
    val named = HostPort("localhost", computer.address.port)
    stack.peers.setCurrent(named)

    val outcome = stack.peers.withCandidate(named) { stack.client.hello(named) }
    val transfer = stack.transfer.download(
      DownloadRequest("http://localhost:${computer.address.port}/api/phone/library", folder.newFile(), 0, null, 0),
    ) {}

    assertThat(outcome).isEqualTo(ApiResult.Failed(AppError.Computer.Guarded))
    assertThat(transfer).isEqualTo(DownloadOutcome.Failed(AppError.Computer.Guarded))
    assertThat(dns.lookups).isEmpty()
  }

  @Test
  fun aWholeSessionNeverAsksForANameLookup() = runBlocking<Unit> {
    computer.addFinishedClip("Ordering coffee")
    stack.pair()
    stack.client.health()
    val library = stack.client.library() as ApiResult.Ok
    val artifact = library.value.jobs.single().artifacts!!.first { it.name == "analysis" }
    stack.transfer.download(DownloadRequest(artifact.url!!, File(folder.root, "part"), 0, null, artifact.bytes!!)) {}

    assertThat(dns.lookups).isEmpty()
  }

  @Test
  fun aTransferToAForeignAddressIsRefusedBeforeAByteIsSent() = runBlocking<Unit> {
    stack.pair()
    val foreign = "http://${elsewhereAddress.host}:${elsewhereAddress.port}/api/phone/jobs/20261010-091503-4be1/artifacts/analysis"

    val outcome = stack.transfer.download(DownloadRequest(foreign, folder.newFile(), 0, null, 10)) {}

    assertThat(outcome).isEqualTo(DownloadOutcome.Failed(AppError.Computer.Guarded))
    assertNothingReachedElsewhere()
  }

  @Test
  fun theChannelOpensOnlyAtTheCurrentAddress() = runBlocking<Unit> {
    val failure = runCatching { stack.channel.open().toList() }.exceptionOrNull()

    assertThat(failure).isNotNull()
    assertThat(computer.requests).isEmpty()
  }

  @Test
  fun theRuleReadsSchemeHostAndPort() {
    val peers = ComputerPeers().apply { setCurrent(HostPort(FakeComputer.HOST, 8000)) }
    val guard = CleartextGuard(peers, LoopbackPolicy)

    guard.check("http://127.0.0.1:8000/api/phone/library".toHttpUrl())
    listOf(
      "https://127.0.0.1:8000/api/phone/library",
      "http://127.0.0.1:8001/api/phone/library",
      "http://localhost:8000/api/phone/library",
      "http://[::1]:8000/api/phone/library",
      "http://127.0.0.2:8000/api/phone/library",
    ).forEach { url ->
      assertThrows(url, GuardedException::class.java) { guard.check(url.toHttpUrl()) }
    }
  }

  @Test
  fun aSocketRefusesToConnectWhereTheGuardRefuses() {
    val peers = ComputerPeers()
    val guard = CleartextGuard(peers, LoopbackPolicy)

    GuardedSocket(guard).use { socket ->
      assertThrows(GuardedException::class.java) { socket.connect(InetSocketAddress(FakeComputer.HOST, elsewhere.localPort)) }
    }
    assertNothingReachedElsewhere()
  }

  @Test
  fun onlyDottedQuadLiteralsAreAddresses() {
    assertThat(Ipv4.isLiteral("127.0.0.1")).isTrue()
    assertThat(Ipv4.isLiteral("192.0.2.10")).isTrue()
    listOf("localhost", "010.0.0.1", "10.0.0", "10.0.0.256", "1.2.3.4.5", "::1", "", "1.2.3.-4").forEach {
      assertThat(Ipv4.isLiteral(it)).isFalse()
    }
  }
}
