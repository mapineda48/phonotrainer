package com.mapineda48.phonotrainer.core.sync

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.common.files.FileLayout
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.spi.StartupOrder
import com.mapineda48.phonotrainer.core.domain.spi.StartupTask
import com.mapineda48.phonotrainer.core.sync.startup.StartupCheck
import com.mapineda48.phonotrainer.core.sync.startup.SyncDirs
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The start-up check (architecture 4.6; Android review M6; wave-1 review G-3 and finding 8). */
@RunWith(RobolectricTestRunner::class)
class StartupCheckTest {
  private val ran: MutableList<String> = Collections.synchronizedList(mutableListOf())
  private val seen: MutableList<Instant> = Collections.synchronizedList(mutableListOf())

  @StartupOrder(20)
  inner class Queue : StartupTask {
    override suspend fun run(processStart: Instant) {
      ran += "queue"
      seen += processStart
    }
  }

  @StartupOrder(5)
  inner class Early : StartupTask {
    override suspend fun run(processStart: Instant) {
      ran += "early"
    }
  }

  @StartupOrder(10)
  inner class Failing : StartupTask {
    override suspend fun run(processStart: Instant) {
      ran += "failing"
      error("this task fails")
    }
  }

  inner class Unordered : StartupTask {
    override suspend fun run(processStart: Instant) {
      ran += "unordered"
    }
  }

  private fun sync(block: suspend TestScope.(SyncHarness) -> Unit) = runTest { SyncHarness().use { block(it) } }

  private fun SyncHarness.check(processStart: Instant, tasks: Set<StartupTask> = emptySet()) = StartupCheck(
    processStart, { tasks }, db, clips, artifacts, files, SyncDirs(filesDir, cacheDir), refresh, backgroundScopeForChecks,
    Dispatchers.IO, log,
  )

  private val backgroundScopeForChecks = kotlinx.coroutines.CoroutineScope(Dispatchers.Default)

  private fun File.aged(millis: Long): File = apply { setLastModified(millis) }

  @Test
  fun filesAndRowsOlderThanTheProcessAreCleanedAndNewerOnesAreLeftAlone() = sync { h ->
    val id = h.computer.addFinishedClip("Ordering coffee", video = false)
    h.syncAndDownload()
    val start = Instant.ofEpochMilli(System.currentTimeMillis())
    val before = start.toEpochMilli() - 60_000
    val after = start.toEpochMilli() + 60_000
    val dir = h.files.clipDir(id.value)!!
    val inUse = dir.listFiles()!!.filter { it.isFile }.onEach { it.aged(before) }
    val orphanPart = File(dir, ".audio_mix.ffffffffffffffff.part").apply { writeBytes(ByteArray(3)) }.aged(before)
    val strayFile = File(dir, "notes.txt").apply { writeText("x") }.aged(before)
    val freshPart = File(dir, ".analysis.eeeeeeeeeeeeeeee.part").apply { writeBytes(ByteArray(3)) }.aged(after)
    val goneClip = File(h.files.root, "20261001-000000-dead").apply { mkdirs() }
    File(goneClip, "analysis.0000.json").writeText("{}")
    goneClip.aged(before)
    val newClip = File(h.files.root, "20261002-000000-beef").apply { mkdirs() }.aged(after)
    val referencePart = File(File(h.filesDir, FileLayout.REFERENCE).apply { mkdirs() }, StartupCheck.REFERENCE_PART)
      .apply { writeText("{") }.aged(before)
    val takes = FileLayout.takesDir(h.cacheDir).apply { mkdirs() }
    val oldTake = File(takes, "old.wav").apply { writeBytes(ByteArray(4)) }.aged(before)
    val newTake = File(takes, "new.wav").apply { writeBytes(ByteArray(4)) }.aged(after)

    h.check(start).run()

    inUse.forEach { assertThat(it.exists()).isTrue() }
    assertThat(orphanPart.exists()).isFalse()
    assertThat(strayFile.exists()).isFalse()
    assertThat(freshPart.exists()).isTrue()
    assertThat(goneClip.exists()).isFalse()
    assertThat(newClip.exists()).isTrue()
    assertThat(referencePart.exists()).isFalse()
    assertThat(oldTake.exists()).isFalse()
    assertThat(newTake.exists()).isTrue()
  }

  @Test
  fun aPartAnArtifactRowStillResumesIsKept() = sync { h ->
    val id = h.computer.addFinishedClip("Ordering coffee", video = false)
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    h.computer.truncate(Names.MIX, atBytes = 100)
    h.downloader.run {}
    val row = h.artifact(id, Names.MIX)
    val part = h.files.part(id.value, Names.MIX, row.partRevision!!)!!.aged(System.currentTimeMillis() - 60_000)

    h.check(Instant.now()).run()

    assertThat(part.exists()).isTrue()
  }

  @Test
  fun downloadsAProcessLeftDownloadingGoBackToTheQueueButNotThoseOfThisProcess() = sync { h ->
    val id = h.computer.addFinishedClip("Ordering coffee", video = false)
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    val mix = h.artifact(id, Names.MIX)
    val analysis = h.artifact(id, Names.ANALYSIS)
    h.artifacts.update(mix.copy(localState = "DOWNLOADING", updatedAt = h.clock.now().toEpochMilli()))
    h.clock.advanceBy(Duration.ofSeconds(5))
    val start = h.clock.now()
    h.artifacts.update(analysis.copy(localState = "DOWNLOADING", updatedAt = start.plusSeconds(1).toEpochMilli()))

    h.check(start).run()

    assertThat(h.artifact(id, Names.MIX).localState).isEqualTo("QUEUED")
    assertThat(h.artifact(id, Names.ANALYSIS).localState).isEqualTo("DOWNLOADING")
  }

  @Test
  fun everyStartupTaskRunsOnceInItsOrderWithTheProcessStartAndAFailureStopsNoOther() = sync { h ->
    val start = h.clock.now()
    val check = h.check(start, setOf(Unordered(), Queue(), Failing(), Early()))
    val ticks = h.refresh.ticks.value

    check.awaitDone()
    check.awaitDone()

    assertThat(ran).containsExactly("early", "failing", "queue", "unordered").inOrder()
    assertThat(seen).containsExactly(start)
    assertThat(h.refresh.ticks.value).isEqualTo(ticks + 1)
    assertThat(h.logged).contains("sync.startup_task_failed (java.lang.IllegalStateException)")
  }

  @Test
  fun aVerifiedFileGoneFromThePhoneIsDownloadedAgain() = sync { h ->
    val id = h.computer.addFinishedClip("Ordering coffee", video = false)
    h.syncAndDownload()
    h.files.resolve(h.artifact(id, Names.MIX).localFile)!!.delete()

    h.check(h.clock.now()).run()

    assertThat(h.artifact(id, Names.MIX).localState).isEqualTo("ABSENT")
    assertThat(h.clipRepository.clips(ClipQuery()).first().single().availability)
      .isEqualTo(ClipAvailability.NeedsDownloadingAgain)

    h.syncAndDownload()
    assertThat(h.clipRepository.clips(ClipQuery()).first().single().availability).isEqualTo(ClipAvailability.OnThisPhone)
  }

  @Test
  fun theDeviceQueuesCopiesAreNeverTheSyncSweepsBusiness() = sync { h ->
    // A share that cold-starts the app keeps its copy: only the queue's own start-up task judges requests/.
    val copy = File(FileLayout.requestsRoot(h.filesDir), "00000000-0000-4000-8000-000000000001/source.mp4").apply {
      parentFile!!.mkdirs()
      writeBytes(ByteArray(8))
    }.aged(0)

    h.check(Instant.now()).run()

    assertThat(copy.exists()).isTrue()
  }

  @Test
  fun theOrderIsReadFromEachTasksAnnotation() {
    val ordered = StartupCheck.ordered(setOf(Unordered(), Queue(), Early()))

    assertThat(ordered.map { it.javaClass.simpleName }).containsExactly("Early", "Queue", "Unordered").inOrder()
  }
}
