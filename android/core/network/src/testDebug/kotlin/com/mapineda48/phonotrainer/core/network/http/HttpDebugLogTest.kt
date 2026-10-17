package com.mapineda48.phonotrainer.core.network.http

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.common.log.LeveledPhonoLog
import com.mapineda48.phonotrainer.core.common.log.LogSink
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.PairRequest
import com.mapineda48.phonotrainer.core.network.support.TestStack
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The request log of debug builds (architecture 20.4, security review M-7): a pairing exchange and signed requests go
 * through it, and neither the pairing code, nor the token of the answer, nor a signature ever reaches the log.
 */
class HttpDebugLogTest {
  @Test
  fun aPairingExchangeNeverPrintsTheCodeTheTokenOrASignature() = runBlocking<Unit> {
    val lines = CopyOnWriteArrayList<String>()
    val log = LeveledPhonoLog(LogSink { _, _, message, _ -> lines += message }, verbose = true)
    FakeComputer().start().use { computer ->
      TestStack(computer, debugLog = HttpDebugLog.interceptor(log)).use { stack ->
        val code = computer.newPairingCode()
        val grant = stack.peers.withCandidate(computer.address) {
          stack.client.pair(computer.address, PairRequest(code, "Test phone", 1, "1.0.0", null, null))
        } as ApiResult.Ok
        val token = String(grant.value.token)
        stack.store.save(grant.value, computer.address, "Test phone", stack.clock.now(), 0)
        stack.peers.setCurrent(computer.address)
        stack.client.hello(computer.address, grant.value.deviceId)
        stack.client.library()

        val text = lines.joinToString("\n")
        assertThat(text).contains("POST http://${computer.address.host}:${computer.address.port}/api/phone/pair")
        assertThat(text).contains("GET http://${computer.address.host}:${computer.address.port}/api/phone/library")
        assertThat(text).contains("Authorization: ██")
        assertThat(text).doesNotContain(code)
        assertThat(text).doesNotContain(token)
        assertThat(text).doesNotContain(token.removePrefix("pt1_"))
        assertThat(text).doesNotContain("mac=")
      }
    }
  }
}
