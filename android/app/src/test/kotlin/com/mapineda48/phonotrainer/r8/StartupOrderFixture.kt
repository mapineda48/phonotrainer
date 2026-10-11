package com.mapineda48.phonotrainer.r8

import com.mapineda48.phonotrainer.core.domain.spi.StartupOrder
import com.mapineda48.phonotrainer.core.domain.spi.StartupTask
import java.time.Instant

// The program StartupOrderR8Test shrinks: two startup tasks reached only the way the app reaches its own (through
// generated code that creates them, Hilt's factories), and the read `StartupCheck` (WP-08) makes of each one's order.

@StartupOrder(20)
class LateFixtureTask : StartupTask {
  override suspend fun run(processStart: Instant) = Unit
}

@StartupOrder(10)
class EarlyFixtureTask : StartupTask {
  override suspend fun run(processStart: Instant) = Unit
}

/** Creates the tasks and reads each one's [StartupOrder] from its class, as `StartupCheck` will. */
object StartupOrderProbe {
  @JvmStatic
  fun orders(): String = listOf<StartupTask>(LateFixtureTask(), EarlyFixtureTask())
    .joinToString(",") { task -> task.javaClass.getAnnotation(StartupOrder::class.java)?.value.toString() }
}
