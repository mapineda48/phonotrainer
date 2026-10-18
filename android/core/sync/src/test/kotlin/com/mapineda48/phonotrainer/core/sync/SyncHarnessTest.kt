package com.mapineda48.phonotrainer.core.sync

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The harness's own ordering: a test's scope ends, with every coroutine in it, before the harness closes the in-memory
 * database. A coroutine still reading after the close throws where no test catches it, and kotlinx-coroutines-test then
 * fails whichever test runs next in the same JVM.
 */
@RunWith(RobolectricTestRunner::class)
class SyncHarnessTest {
  @Test
  fun aTestScopeEndsWithEveryCoroutineInItBeforeTheHarnessClosesTheDatabase() = runBlocking {
    val readAfterCancel = AtomicBoolean(false)
    SyncHarness().use { h ->
      h.withScope { scope ->
        val started = CompletableDeferred<Unit>()
        scope.launch {
          try {
            started.complete(Unit)
            awaitCancellation()
          } finally {
            withContext(NonCancellable) {
              delay(200)
              h.artifacts.observeAll().first()
              readAfterCancel.set(true)
            }
          }
        }
        started.await()
      }
      assertThat(readAfterCancel.get()).isTrue()
    }
  }
}
