package com.mapineda48.phonotrainer.e2e.di

import android.os.Looper
import com.mapineda48.phonotrainer.core.domain.model.Datagram
import com.mapineda48.phonotrainer.core.domain.spi.DiscoveryTransport
import com.mapineda48.phonotrainer.core.testing.TestClock
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputerClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * `FakeComputerModule` of architecture 21.5 (integrator-owned): every test gets its own [FakeComputer] (WP-05a's,
 * `server-contract.md` on a loopback port), started on first use and closed when the test ends. Each test builds its
 * own Hilt component, so the `@Singleton` here means "one per test" (`PerTestIsolation` closes it). Inject
 * [FakeComputer] in a test to seed it and
 * to turn its failure knobs; the app reaches it through the real network stack at [FakeComputer.address] (the
 * global [TestNetworkModule] lets loopback through) and through [FakeDiscovery].
 *
 * The computer's clock is **frozen** at the phone's [TestClock] instant, so the phone and the computer agree on the
 * time (signature windows, code expiry, upload idleness) and only move together, through `TestTime` (e2e/support).
 */
@Module
@InstallIn(SingletonComponent::class)
object FakeComputerModule {
  @Provides
  @Singleton
  fun fakeComputer(holder: FakeComputerHolder): FakeComputer = holder.get()
}

/** Starts this test's computer once, off the main thread, and registers it to be closed when the test ends. */
@Singleton
class FakeComputerHolder @Inject constructor(private val clock: TestClock) {
  @Volatile private var computer: FakeComputer? = null

  /** This test's computer, started now if it was not yet. */
  @Synchronized
  fun get(): FakeComputer = computer ?: start().also { computer = it }

  /** This test's computer if something already started it. */
  fun startedOrNull(): FakeComputer? = computer

  private fun start(): FakeComputer {
    val created = FakeComputer(clock = FakeComputerClock.frozen(clock.now().epochSecond))
    // Binding a socket on the main thread would trip the platform's network-on-main-thread check.
    if (Looper.myLooper() == Looper.getMainLooper()) {
      var failure: Throwable? = null
      thread(name = "fake-computer-start") { failure = runCatching { created.start() }.exceptionOrNull() }.join()
      failure?.let { throw it }
    } else {
      created.start()
    }
    return created.also(FakeComputers::register)
  }
}

/**
 * The `FakeDiscovery` of architecture 21.3 and 21.5: replaces the UDP transport. Each probe is recorded and answered
 * by this test's [FakeComputer] exactly as its real responder would (same rules: silent with phone access off, not
 * approved, too old, unreachable), from the loopback address and the discovery port; extra responders (an
 * impersonator, a second computer) answer after it. Set [computerAnswers] to false for a network where the computer
 * is not reachable by broadcast.
 */
@Singleton
class FakeDiscovery @Inject constructor(private val computers: FakeComputerHolder) : DiscoveryTransport {
  private val recorded = CopyOnWriteArrayList<ByteArray>()
  private val responders = CopyOnWriteArrayList<Responder>()

  @Volatile var computerAnswers: Boolean = true

  /** Every probe datagram sent, in order. */
  val probes: List<ByteArray> get() = recorded.toList()

  /** Another responder on the network: [answer] returns its reply to a probe, or null to stay silent. */
  fun addResponder(sourceHost: String, sourcePort: Int = DISCOVERY_PORT, answer: (ByteArray) -> ByteArray?) {
    responders += Responder(sourceHost, sourcePort, answer)
  }

  override fun probe(
    datagram: ByteArray,
    broadcastTargets: List<String>,
    schedule: List<Duration>,
    listenFor: Duration,
  ): Flow<Datagram> = flow {
    recorded += datagram.copyOf()
    if (computerAnswers) {
      computers.get().answerProbe(datagram, FakeComputer.HOST)?.let { emit(Datagram(it, FakeComputer.HOST, DISCOVERY_PORT)) }
    }
    responders.forEach { responder ->
      responder.answer(datagram)?.let { emit(Datagram(it, responder.host, responder.port)) }
    }
  }

  private class Responder(val host: String, val port: Int, val answer: (ByteArray) -> ByteArray?)

  companion object {
    /** The computer's UDP discovery port (server-contract 5.1). */
    const val DISCOVERY_PORT = 45317
  }
}

/** Every fake computer started in this process and not closed yet. */
object FakeComputers {
  private val open = CopyOnWriteArrayList<FakeComputer>()

  /** How many are open right now (a test can assert that the previous test's computer was closed). */
  val openCount: Int get() = open.size

  fun register(computer: FakeComputer) {
    open += computer
  }

  fun closeAll() {
    open.forEach { runCatching { it.close() } }
    open.clear()
  }
}

