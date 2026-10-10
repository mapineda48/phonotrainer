package com.mapineda48.phonotrainer.core.testing.computer

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.JobId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Test
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The job channel for phones (server-contract 7). */
class ChannelTest {
  private val computer = FakeComputer(clock = FakeComputerClock.frozen()).start()
  private val credentials = computer.pairDevice()
  private val phone = TestPhone(computer, credentials)
  private val opened = mutableListOf<WebSocket>()

  @After
  fun tearDown() {
    opened.forEach { it.cancel() }
    computer.close()
  }

  @Test
  fun theChannelSaysHelloThenSendsALeanSnapshot() {
    val id = computer.addFinishedClip()
    val channel = Channel(phone)

    val hello = channel.next()
    val snapshot = channel.next()

    assertThat(hello.str("type")).isEqualTo("hello")
    assertThat(hello.str("server_id")).isEqualTo(computer.serverId.value)
    assertThat(hello.str("library_id")).isEqualTo(computer.libraryId.value)
    assertThat(hello["api_version"]!!.prim().int).isEqualTo(6)
    assertThat(snapshot.str("type")).isEqualTo("snapshot")
    val job = snapshot.arr("jobs").single().jsonObject
    assertThat(job.str("id")).isEqualTo(id.value)
    assertThat(job.containsKey("artifacts")).isFalse()
    assertThat(computer.openChannels).isEqualTo(1)
  }

  @Test
  fun jobEventsFollowALifecycleAndDeletedEventsFollowARerun() {
    val channel = Channel(phone).skipGreeting()
    val id = phone.createLink("https://youtu.be/abc123def45").jobId
    assertThat(channel.next().jobStatus()).isEqualTo("queued")

    computer.progress(JobId(id), JobStage.DOWNLOAD, 2)
    assertThat(channel.next().jobStatus()).isEqualTo("running")
    computer.failJob(JobId(id), "network")
    assertThat(channel.next().jobStatus()).isEqualTo("error")

    val rerun = phone.rerun(id).jobId
    val events = listOf(channel.next(), channel.next())
    assertThat(events.map { it.str("type") }).containsExactly("deleted", "job").inOrder()
    assertThat(events[0].str("id")).isEqualTo(id)
    assertThat(events[1].obj("job").str("id")).isEqualTo(rerun)

    phone.post("/api/phone/jobs/$rerun/cancel")
    assertThat(channel.next().jobStatus()).isEqualTo("cancelled")
    phone.delete("/api/phone/jobs/$rerun")
    assertThat(channel.next().str("type")).isEqualTo("deleted")
  }

  @Test
  fun eventsAreShapedForEachChannelsDevice() {
    val other = TestPhone(computer, computer.pairDevice("Other"))
    val mine = Channel(phone).skipGreeting()
    val theirs = Channel(other).skipGreeting()

    phone.createLink("https://youtu.be/abc123def45")

    val mineJob = mine.next().obj("job")
    val theirsJob = theirs.next().obj("job")
    assertThat(mineJob["own"]!!.prim().boolean).isTrue()
    assertThat(mineJob.str("device_id")).isEqualTo(credentials.deviceId.value)
    assertThat(theirsJob["own"]!!.prim().boolean).isFalse()
    assertThat(theirsJob["device_id"]).isEqualTo(JsonNull)
  }

  @Test
  fun refusedUpgradesAre403() {
    assertThat(Channel(TestPhone(computer)).failureCode()).isEqualTo(403)
    assertThat(Channel(phone, host = "attacker.example").failureCode()).isEqualTo(403)
    computer.revokeDevice()
    assertThat(Channel(phone).failureCode()).isEqualTo(403)
  }

  @Test
  fun aThirdChannelOfOneDeviceIsRefused() {
    Channel(phone).skipGreeting()
    Channel(phone).skipGreeting()

    assertThat(Channel(phone).failureCode()).isEqualTo(403)
  }

  @Test
  fun aNinthChannelIsRefused() {
    repeat(4) {
      val device = TestPhone(computer, computer.pairDevice("Phone $it"))
      Channel(device).skipGreeting()
      Channel(device).skipGreeting()
    }

    assertThat(Channel(phone).failureCode()).isEqualTo(403)
    assertThat(computer.openChannels).isEqualTo(8)
  }

  @Test
  fun pingsAreAnsweredUntilTheComputerBecomesUnreachable() {
    val client = phone.client.newBuilder().pingInterval(Duration.ofMillis(PING_MS)).build()
    val channel = Channel(phone, client = client).skipGreeting()

    assertThat(channel.awaitFailure(timeoutMs = PING_MS * 5)).isFalse()
    computer.reachability = Reachability.Unreachable

    assertThat(channel.awaitFailure(timeoutMs = PING_MS * 20)).isTrue()
  }

  @Test
  fun dropChannelClosesEveryChannel() {
    val channel = Channel(phone).skipGreeting()

    computer.dropChannel()

    assertThat(channel.awaitFailure()).isTrue()
    assertThat(computer.openChannels).isEqualTo(0)
  }

  private fun JsonObject.jobStatus(): String? = obj("job").str("status")

  /** A phone's channel with its frames in a queue. */
  private inner class Channel(
    phone: TestPhone,
    host: String? = null,
    client: OkHttpClient = phone.client,
  ) : WebSocketListener() {
    private val frames = LinkedBlockingQueue<JsonObject>()
    private val failures = LinkedBlockingQueue<Int>()

    init {
      val request = Request.Builder().url(computer.url("/api/phone/events"))
      host?.let { request.header("Host", it) }
      opened += client.newWebSocket(request.build(), this)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
      frames += Json.parseToJsonElement(text).jsonObject
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      failures += response?.code ?: 0
    }

    fun next(): JsonObject = frames.poll(TestPhone.TIMEOUT_S, TimeUnit.SECONDS) ?: error("no frame")

    fun skipGreeting(): Channel = apply {
      next()
      next()
    }

    fun failureCode(): Int = failures.poll(TestPhone.TIMEOUT_S, TimeUnit.SECONDS) ?: error("the upgrade was accepted")

    fun awaitFailure(timeoutMs: Long = TestPhone.TIMEOUT_S * 1000): Boolean =
      failures.poll(timeoutMs, TimeUnit.MILLISECONDS) != null
  }

  private companion object {
    const val PING_MS = 100L
  }
}
