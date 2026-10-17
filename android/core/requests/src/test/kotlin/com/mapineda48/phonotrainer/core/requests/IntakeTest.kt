package com.mapineda48.phonotrainer.core.requests

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.common.hash.Sha256
import com.mapineda48.phonotrainer.core.domain.model.FileOptions
import com.mapineda48.phonotrainer.core.domain.model.FileRefusal
import com.mapineda48.phonotrainer.core.domain.model.FileRefusalReason
import com.mapineda48.phonotrainer.core.domain.model.FileSource
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.requests.intake.IntakeResult
import com.mapineda48.phonotrainer.core.testing.computer.FakeMediaBytes
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * File intake (architecture 8.3; SH-AC10, SH-AC13 to SH-AC15, F-19-AC3, F-19-AC4 logic, F-20-AC9, QT-13 logic, D-49):
 * checks before a request exists, the copy through a SHA-256 digest to `source.<ext>`, bytes counted against a
 * provider that lies, the copy failure states, and the sources a share sheet holds.
 */
@RunWith(RobolectricTestRunner::class)
class IntakeTest {
  private fun queueTest(block: suspend TestScope.(QueueHarness) -> Unit) = runTest {
    QueueHarness(backgroundScope).use { block(it) }
  }

  private val video = FileOptions(audioOnly = false)
  private val audioOnly = FileOptions(audioOnly = true)

  private fun shared(uri: String) = FileSource(uri, displayName = null, mimeType = null, declaredBytes = null, persisted = false)

  private fun picked(uri: String) = FileSource(uri, displayName = null, mimeType = null, declaredBytes = null, persisted = true)

  private suspend fun QueueHarness.addOne(source: FileSource, options: FileOptions = video): RequestId {
    val result = repository.addFiles(listOf(source), options)
    assertThat(result.refused).isEmpty()
    return result.added.single()
  }

  @Test
  fun aSharedFileIsCopiedThroughItsDigestToSourceWhateverItsName() = queueTest { queue ->
    val bytes = FakeMediaBytes.video(size = 50_000, salt = 1)
    val uri = queue.reader.add("content://media.example/1", FakeItem(bytes, name = "../../databases/library.db", mime = "video/mp4"))

    val id = queue.addOne(shared(uri))
    val preparing = queue.request(id)
    assertThat(preparing.state).isEqualTo(RequestState.PREPARING)
    assertThat(preparing.actions).containsExactly(RequestAction.CANCEL_PREPARING)
    assertThat(queue.work.shared).containsExactly(id)
    assertThat(queue.handles.get(id)).isNotNull()

    queue.runIntakes()

    val row = queue.row(id)!!
    assertThat(row.state).isEqualTo("WAITING")
    assertThat(row.copyFile).isEqualTo("requests/${id.value}/source.mp4")
    assertThat(row.fileSha256).isEqualTo(Sha256.hex(bytes))
    assertThat(row.fileBytes).isEqualTo(bytes.size.toLong())
    assertThat(row.fileName).isEqualTo("library.db")
    assertThat(queue.files.dir(id).list()!!.toList()).containsExactly("source.mp4")
    assertThat(queue.filesDir.list()!!.toList()).containsExactly("requests")
    assertThat(queue.request(id).title).isEqualTo("library.db")
    assertThat(queue.handles.get(id)).isNull()
    assertThat(queue.trigger.reasons).contains(SyncReason.REQUEST_CREATED)
    assertThat(queue.repository.intake(id).first().done).isTrue()
  }

  @Test
  fun aProviderThatLiesAboutTheSizeIsCountedNotBelieved() = queueTest { queue ->
    val uri = queue.reader.add("content://media.example/2", FakeItem(ByteArray(3 * 1024 * 1024) { 1 }, declaredSize = 1_000))
    val id = queue.addOne(shared(uri))

    queue.runIntakes()

    val request = queue.request(id)
    assertThat(request.state).isEqualTo(RequestState.COPY_FAILED)
    assertThat(request.lastError!!.code).isEqualTo("copy_no_space")
    assertThat(queue.files.part(id).exists()).isFalse()
    assertThat(queue.files.bytes(id)).isEqualTo(0)
  }

  @Test
  fun aCopyLargerThanTheComputerAcceptsIsStoppedByItsCount() = queueTest { queue ->
    queue.pairing.state.value = QueueHarness.record(queue.computer.serverId, capabilities = QueueHarness.capabilities(maxUploadBytes = 100_000))
    val uri = queue.reader.add("content://media.example/3", FakeItem(ByteArray(300_000) { 2 }, declaredSize = null))
    val id = queue.addOne(shared(uri))

    queue.runIntakes()

    assertThat(queue.request(id).lastError!!.code).isEqualTo("copy_no_space")
  }

  @Test
  fun thisAppsOwnProvidersAndFileUrisAreRefusedBeforeARequestExists() = queueTest { queue ->
    queue.reader.own += "com.example.self"
    queue.reader.add("content://com.example.self/private", FakeItem(ByteArray(10)))
    queue.reader.add("file:///storage/emulated/0/clip.mp4", FakeItem(ByteArray(10)))

    val result = queue.repository.addFiles(
      listOf(shared("content://com.example.self/private"), shared("file:///storage/emulated/0/clip.mp4")),
      video,
    )

    assertThat(result.added).isEmpty()
    assertThat(result.refused.map { it.reason }).containsExactly(FileRefusalReason.OWN_PROVIDER, FileRefusalReason.UNREADABLE).inOrder()
    assertThat(queue.reader.opens).isEqualTo(0)
    assertThat(queue.repository.requests().first()).isEmpty()
  }

  @Test
  fun everySharedItemIsItsOwnRequestAndUnsupportedOnesAreNamed() = queueTest { queue ->
    val first = queue.reader.add("content://media.example/a", FakeItem(FakeMediaBytes.video(size = 2_000, salt = 2), name = "a.mp4"))
    val second = queue.reader.add("content://media.example/b", FakeItem(FakeMediaBytes.video(size = 3_000, salt = 3), name = "b.mp4"))
    val voice = queue.reader.add("content://media.example/c", FakeItem(ByteArray(100), name = "voice.amr", mime = "audio/amr"))

    val result = queue.repository.addFiles(listOf(shared(first), shared(voice), shared(second)), video)
    queue.runIntakes()

    assertThat(result.added).hasSize(2)
    assertThat(result.refused).containsExactly(FileRefusal("voice.amr", FileRefusalReason.UNSUPPORTED_TYPE, null))
    val requests = queue.repository.requests().first()
    assertThat(requests.map { it.fileName }).containsExactly("a.mp4", "b.mp4").inOrder()
    assertThat(requests.map { it.fileBytes }).containsExactly(2_000L, 3_000L).inOrder()
    assertThat(requests.map { it.state }).containsExactly(RequestState.WAITING, RequestState.WAITING)
  }

  @Test
  fun anItemListedInTheStreamAndInTheClipDataIsOneRequest() = queueTest { queue ->
    // A multiple share names its items in EXTRA_STREAM and again in its ClipData (architecture 8.3).
    val first = queue.reader.add("content://media.example/a", FakeItem(FakeMediaBytes.video(size = 2_000, salt = 4), name = "a.mp4"))
    val second = queue.reader.add("content://media.example/b", FakeItem(FakeMediaBytes.video(size = 3_000, salt = 5), name = "b.mp4"))

    val result = queue.repository.addFiles(listOf(shared(first), shared(second), shared(first), shared(second)), video)
    queue.runIntakes()

    assertThat(result.added).hasSize(2)
    assertThat(result.refused).isEmpty()
    assertThat(queue.repository.requests().first().map { it.fileName }).containsExactly("a.mp4", "b.mp4").inOrder()
  }

  @Test
  fun moreThanTenItemsAreRefusedAsTooMany() = queueTest { queue ->
    val sources = (1..12).map { n -> shared(queue.reader.add("content://media.example/$n", FakeItem(ByteArray(100) { n.toByte() }, name = "$n.mp4"))) }

    val result = queue.repository.addFiles(sources, video)

    assertThat(result.added).hasSize(10)
    assertThat(result.refused.map { it.reason }).containsExactly(FileRefusalReason.TOO_MANY, FileRefusalReason.TOO_MANY)
  }

  @Test
  fun noSpaceBeforeTheCopyIsRefusedWithTheSpaceNeededAndNothingIsLeftHalfMade() = queueTest { queue ->
    queue.space.allocatable = { queue.space.floor + 500 }
    val uri = queue.reader.add("content://media.example/4", FakeItem(ByteArray(10_000)))

    val result = queue.repository.addFiles(listOf(shared(uri)), video)

    assertThat(result.added).isEmpty()
    assertThat(result.refused).containsExactly(FileRefusal("VID_0001.mp4", FileRefusalReason.NO_SPACE, 9_500))
    assertThat(queue.repository.requests().first()).isEmpty()
    // Audio only shrinks a video on the phone: only the copy's own count can refuse it then.
    assertThat(queue.repository.addFiles(listOf(shared(uri)), audioOnly).added).hasSize(1)
  }

  @Test
  fun noSpaceDuringTheCopyNamesTheSpaceNeededAndOffersAudioOnlyOnlyWhileTheSheetHoldsIt() = queueTest { queue ->
    var calls = 0
    queue.space.allocatable = { if (++calls <= 2) 50_000_000_000 else queue.space.floor + 1_000 }
    val uri = queue.reader.add("content://media.example/5", FakeItem(ByteArray(9 * 1024 * 1024) { 3 }))
    val id = queue.addOne(shared(uri))
    val sheet = queue.repository.holdSources(setOf(id))

    queue.runIntakes()

    val failed = queue.request(id)
    assertThat(failed.state).isEqualTo(RequestState.COPY_FAILED)
    assertThat(failed.lastError!!.code).isEqualTo("copy_no_space")
    assertThat(failed.lastError!!.neededBytes!!).isGreaterThan(0)
    assertThat(failed.actions).containsExactly(RequestAction.TRY_AUDIO_ONLY, RequestAction.REMOVE)
    assertThat(queue.files.part(id).exists()).isFalse()
    val progress = queue.repository.intake(id).first()
    assertThat(progress.failed!!.code).isEqualTo("copy_no_space")

    // "Try audio only" in the open sheet: the same request goes back to preparing the audio track.
    queue.repository.perform(id, RequestAction.TRY_AUDIO_ONLY)
    assertThat(queue.request(id).state).isEqualTo(RequestState.PREPARING)
    queue.runIntakes()
    val extracted = queue.row(id)!!
    assertThat(extracted.state).isEqualTo("WAITING")
    assertThat(extracted.audioExtracted).isTrue()
    assertThat(extracted.copyFile).endsWith("source.m4a")
    assertThat(queue.repository.requests().first()).hasSize(1)
    sheet.close()
  }

  @Test
  fun onceTheSheetClosedAFailedSharedCopyOffersRemoveOnly() = queueTest { queue ->
    var calls = 0
    queue.space.allocatable = { if (++calls <= 2) 50_000_000_000 else queue.space.floor + 1_000 }
    val uri = queue.reader.add("content://media.example/6", FakeItem(ByteArray(9 * 1024 * 1024) { 4 }))
    val id = queue.addOne(shared(uri))
    val sheet = queue.repository.holdSources(setOf(id))
    queue.runIntakes()
    assertThat(queue.request(id).actions).contains(RequestAction.TRY_AUDIO_ONLY)

    sheet.close()

    assertThat(queue.request(id).actions).containsExactly(RequestAction.REMOVE)
  }

  @Test
  fun anUnreadableSourceIsRefusedAndOneThatStopsAnsweringFailsTheCopy() = queueTest { queue ->
    val gone = queue.reader.add("content://media.example/7", FakeItem(ByteArray(100), unreadable = true))
    assertThat(queue.repository.addFiles(listOf(shared(gone)), video).refused.single().reason).isEqualTo(FileRefusalReason.UNREADABLE)

    val flaky = queue.reader.add("content://media.example/8", FakeItem(ByteArray(200_000) { 5 }, failAfter = 70_000))
    val id = queue.addOne(shared(flaky))
    queue.runIntakes()

    assertThat(queue.request(id).state).isEqualTo(RequestState.COPY_FAILED)
    assertThat(queue.request(id).lastError!!.code).isEqualTo("copy_unreadable")
    assertThat(queue.request(id).actions).containsExactly(RequestAction.REMOVE)
  }

  @Test
  fun aSharedCopyCutShortByProcessDeathFailsAtTheNextStartAndLeavesNothing() = queueTest { queue ->
    val uri = queue.reader.add("content://media.example/9", FakeItem(FakeMediaBytes.video(size = 40_000, salt = 9)))
    val id = queue.addOne(shared(uri))
    queue.files.dir(id).mkdirs()
    queue.files.part(id).writeBytes(ByteArray(20_000))
    // The process dies: the descriptor and the running work go with it.
    queue.handles.drop(id)
    queue.clock.advanceBy(Duration.ofSeconds(5))

    queue.startup.run(queue.clock.now())

    val request = queue.request(id)
    assertThat(request.state).isEqualTo(RequestState.COPY_FAILED)
    assertThat(request.lastError!!.code).isEqualTo("copy_interrupted")
    assertThat(request.actions).containsExactly(RequestAction.REMOVE)
    assertThat(queue.files.bytes(id)).isEqualTo(0)
    // The work WorkManager kept from the old process finds nothing left to do.
    assertThat(queue.intake.copy(id)).isEqualTo(IntakeResult.DONE)
  }

  @Test
  fun aStoppedCopyOfAPipeFailsWhileOneOfAFileStartsAgainFromTheFirstByte() = queueTest { queue ->
    val pipe = FakeItem(ByteArray(300_000) { 6 }, seekable = false, blockAfter = 65_536)
    val pipeId = queue.addOne(shared(queue.reader.add("content://media.example/pipe", pipe)))
    val stopped = launch(Dispatchers.IO) { queue.intake.copy(pipeId) }
    withContext(Dispatchers.IO) { pipe.reached.await() }
    stopped.cancel()
    pipe.release.countDown()
    stopped.join()

    assertThat(queue.request(pipeId).state).isEqualTo(RequestState.COPY_FAILED)
    assertThat(queue.request(pipeId).lastError!!.code).isEqualTo("copy_interrupted")

    val bytes = ByteArray(300_000) { (it % 251).toByte() }
    val file = FakeItem(bytes, blockAfter = 65_536)
    val fileId = queue.addOne(shared(queue.reader.add("content://media.example/file", file)))
    val first = launch(Dispatchers.IO) { queue.intake.copy(fileId) }
    withContext(Dispatchers.IO) { file.reached.await() }
    first.cancel()
    file.release.countDown()
    first.join()
    assertThat(queue.request(fileId).state).isEqualTo(RequestState.PREPARING)

    // WorkManager runs the stopped work again.
    queue.intake.copy(fileId)

    assertThat(queue.row(fileId)!!.fileSha256).isEqualTo(Sha256.hex(bytes))
    assertThat(queue.request(fileId).state).isEqualTo(RequestState.WAITING)
  }

  @Test
  fun aPickedFileRestartsItsCopyAfterProcessDeathAndThenReleasesItsPermission() = queueTest { queue ->
    val bytes = FakeMediaBytes.video(size = 30_000, salt = 10)
    val uri = queue.reader.add("content://docs.example/10", FakeItem(bytes, name = "Holiday.mov", mime = "video/quicktime"))
    val id = queue.addOne(picked(uri))
    assertThat(queue.work.picked).containsExactly(id)
    assertThat(queue.reader.kept).containsExactly(uri)
    // The process dies before the copy ran.
    queue.work.intakes.clear()
    queue.clock.advanceBy(Duration.ofSeconds(5))

    queue.startup.run(queue.clock.now())
    queue.runIntakes()

    assertThat(queue.work.picked).containsExactly(id, id)
    val row = queue.row(id)!!
    assertThat(row.state).isEqualTo("WAITING")
    assertThat(row.copyFile).endsWith("source.mov")
    assertThat(row.fileSha256).isEqualTo(Sha256.hex(bytes))
    assertThat(queue.reader.released).containsExactly(uri)
  }

  @Test
  fun aPickedFileKeepsItsPermissionWhileOutOfSpaceAndReleasesItOnRemove() = queueTest { queue ->
    var calls = 0
    queue.space.allocatable = { if (++calls <= 1) 50_000_000_000 else queue.space.floor + 1_000 }
    val uri = queue.reader.add("content://docs.example/11", FakeItem(ByteArray(9 * 1024 * 1024) { 7 }, declaredSize = null))
    val id = queue.addOne(picked(uri))
    queue.runIntakes()
    val failed = queue.request(id)
    assertThat(failed.lastError!!.code).isEqualTo("copy_no_space")
    assertThat(failed.actions).containsExactly(RequestAction.TRY_AUDIO_ONLY, RequestAction.REMOVE)
    assertThat(queue.reader.released).isEmpty()

    queue.repository.perform(id, RequestAction.REMOVE)

    assertThat(queue.reader.released).containsExactly(uri)
    assertThat(queue.repository.request(id).first()).isNull()
  }

  @Test
  fun aPickedFileThatCannotBeReadReleasesItsPermissionAtOnce() = queueTest { queue ->
    val item = FakeItem(ByteArray(200_000) { 8 }, failAfter = 70_000)
    val uri = queue.reader.add("content://docs.example/12", item)
    val id = queue.addOne(picked(uri))

    queue.runIntakes()

    assertThat(queue.request(id).lastError!!.code).isEqualTo("copy_unreadable")
    assertThat(queue.reader.released).containsExactly(uri)
  }

  @Test
  fun audioOnlyKeepsTheAudioTrackOrTheWholeFileWhenItCannotBeSeparated() = queueTest { queue ->
    val bytes = FakeMediaBytes.video(size = 60_000, salt = 13)
    val extracted = queue.addOne(shared(queue.reader.add("content://media.example/13", FakeItem(bytes))), audioOnly)
    queue.runIntakes()
    val audio = queue.row(extracted)!!
    assertThat(audio.copyFile).endsWith("source.m4a")
    assertThat(audio.audioExtracted).isTrue()
    assertThat(audio.fileBytes).isEqualTo(queue.extraction.audio.size.toLong())

    queue.extraction.extension = null
    val whole = queue.addOne(shared(queue.reader.add("content://media.example/14", FakeItem(bytes))), audioOnly)
    queue.runIntakes()
    val kept = queue.row(whole)!!
    assertThat(kept.copyFile).endsWith("source.mp4")
    assertThat(kept.audioExtracted).isFalse()
    assertThat(kept.wholeFileFallback).isTrue()
    assertThat(kept.fileSha256).isEqualTo(Sha256.hex(bytes))
  }

  @Test
  fun stopAddingDeletesTheRecordAndThePartialCopy() = queueTest { queue ->
    val id = queue.addOne(shared(queue.reader.add("content://media.example/15", FakeItem(ByteArray(1_000)))))
    queue.files.dir(id).mkdirs()
    queue.files.part(id).writeBytes(ByteArray(500))

    queue.repository.stopIntake(id)

    assertThat(queue.repository.request(id).first()).isNull()
    assertThat(queue.files.dir(id).exists()).isFalse()
    assertThat(queue.work.stopped).containsExactly(id)
    assertThat(queue.handles.get(id)).isNull()
  }
}
