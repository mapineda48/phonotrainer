package com.mapineda48.phonotrainer.feature.requests.add

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.RequestRepository
import com.mapineda48.phonotrainer.core.domain.model.AddFilesResult
import com.mapineda48.phonotrainer.core.domain.model.AddResult
import com.mapineda48.phonotrainer.core.domain.model.Duplicate
import com.mapineda48.phonotrainer.core.domain.model.FileOptions
import com.mapineda48.phonotrainer.core.domain.model.FileRefusal
import com.mapineda48.phonotrainer.core.domain.model.FileRefusalReason
import com.mapineda48.phonotrainer.core.domain.model.FileSource
import com.mapineda48.phonotrainer.core.domain.model.IntakeProgress
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.navigation.AddClipKey
import com.mapineda48.phonotrainer.core.navigation.AddClipStart
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeNotificationPermissionAsker
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeComputerStatusRepository
import com.mapineda48.phonotrainer.core.testing.fake.requests.FakeLinkParser
import com.mapineda48.phonotrainer.core.testing.fake.requests.FakeRequestRepository
import com.mapineda48.phonotrainer.core.ui.component.RequestRowSamples
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** [FakeRequestRepository] that also records each `addFiles` call with its options. */
internal class RecordingRequests(val fake: FakeRequestRepository = FakeRequestRepository()) : RequestRepository by fake {
  val files = mutableListOf<Pair<List<FileSource>, FileOptions>>()

  override suspend fun addFiles(files: List<FileSource>, options: FileOptions): AddFilesResult {
    this.files += files to options
    return fake.addFiles(files, options)
  }
}

/** Picked files the test lists; [inspected] records each call. */
internal class FakePickedFiles(var answer: (List<String>) -> List<PickedFile> = { uris -> uris.mapIndexed(::video) }) : PickedFiles {
  val inspected = mutableListOf<List<String>>()

  override suspend fun inspect(uris: List<String>): List<PickedFile> {
    inspected += uris
    return answer(uris)
  }

  companion object {
    fun video(index: Int, uri: String) = PickedFile(uri, "clip-$index.mp4", "video/mp4", 40L * 1024 * 1024, 42_000, PickedKind.VIDEO)
  }
}

/** "Add a clip"'s ViewModel over the settable fakes of work-plan 4.6 (spec F-19, 8.4). */
@OptIn(ExperimentalCoroutinesApi::class)
class AddClipViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  private val requests = RecordingRequests()
  private val parser = FakeLinkParser()
  private val status = FakeComputerStatusRepository(RequestRowSamples.connected)
  private val asker = FakeNotificationPermissionAsker(shouldAsk = false)
  private val picked = FakePickedFiles()

  private fun viewModel(start: AddClipStart = AddClipStart.CHOOSER, saved: SavedStateHandle = SavedStateHandle()) =
    AddClipViewModel(AddClipKey(start), requests, parser, status, asker, picked, saved)

  @Test
  fun pastingTakesTheFirstLinkOfTheClipboardAndNamesItsSite() = runTest(main.dispatcher) {
    // F-19-AC1: the screen reads the clipboard only on "Paste" and hands its text here.
    val vm = viewModel()
    vm.paste("Great scene https://youtu.be/abc?si=xyz and more https://x.com/a/status/1")

    assertThat(vm.state.value.link).isEqualTo("https://youtu.be/abc?si=xyz")
    assertThat(vm.state.value.parsed?.site).isEqualTo(Site.YOUTUBE)
    assertThat(vm.state.value.linkError).isFalse()
    assertThat(vm.state.value.canSubmit).isTrue()
    assertThat(vm.state.value.analyzeNow).isTrue()
  }

  @Test
  fun pastingTextWithoutALinkSaysSoAndNothingCanBeAdded() = runTest(main.dispatcher) {
    // F-19-AC2.
    val vm = viewModel()
    vm.paste("Meet me at noon")

    assertThat(vm.state.value.linkError).isTrue()
    assertThat(vm.state.value.canSubmit).isFalse()
    vm.submit()
    assertThat(requests.fake.links).isEmpty()
    vm.paste(null)
    assertThat(vm.state.value.linkError).isTrue()
    assertThat(vm.state.value.canSubmit).isFalse()
  }

  @Test
  fun typingShowsTheErrorOnlyOnceTheTextCannotBecomeALink() = runTest(main.dispatcher) {
    val vm = viewModel()
    listOf("h", "http", "https://").forEach {
      vm.setLink(it)
      assertThat(vm.state.value.linkError).isFalse()
    }
    vm.setLink("hello")
    assertThat(vm.state.value.linkError).isTrue()
    vm.setLink("https://example.org/talk")
    assertThat(vm.state.value.parsed?.site).isEqualTo(Site.OTHER)
    assertThat(vm.state.value.linkError).isFalse()
  }

  @Test
  fun aLinkIsStoredFirstThenItsMessageFollowsItToSent() = runTest(main.dispatcher) {
    requests.fake.addLinkResult = { AddResult.Added(RequestId("new")) }
    requests.fake.all.value = listOf(RequestRowSamples.request("new", RequestState.WAITING))
    val vm = viewModel()
    vm.setLink("https://youtu.be/abc")
    vm.setAudioOnly(true)
    vm.submit()
    runCurrent()

    assertThat(requests.fake.links.single().audioOnly).isTrue()
    assertThat(requests.fake.links.single().force).isFalse()
    assertThat(vm.state.value.phase).isEqualTo(AddPhase.LinkAdded(RequestId("new"), DoneMessage.SENDING))

    status.statusState.value = RequestRowSamples.unreachable
    assertThat(vm.state.value.phase).isEqualTo(AddPhase.LinkAdded(RequestId("new"), DoneMessage.ADDED))
    requests.fake.all.value = listOf(RequestRowSamples.request("new", RequestState.IN_LINE))
    assertThat(vm.state.value.phase).isEqualTo(AddPhase.LinkAdded(RequestId("new"), DoneMessage.SENT))
  }

  @Test
  fun aDuplicateOffersOpenItOrAnalyzeAgainAnyway() = runTest(main.dispatcher) {
    val duplicate = Duplicate(RequestId("waiting"), null, "Coffee")
    requests.fake.addLinkResult = { if (it.force) AddResult.Added(RequestId("again")) else AddResult.DuplicateFound(duplicate) }
    val vm = viewModel()
    vm.setLink("https://youtu.be/abc")
    vm.submit()
    runCurrent()
    assertThat(vm.state.value.phase).isEqualTo(AddPhase.DuplicateFound(duplicate))

    vm.submit(force = true)
    runCurrent()
    assertThat(requests.fake.links.map { it.force }).containsExactly(false, true).inOrder()
    assertThat((vm.state.value.phase as AddPhase.LinkAdded).id).isEqualTo(RequestId("again"))
  }

  @Test
  fun aRecordThatCannotBeSavedIsSaidAndNothingIsPretended() = runTest(main.dispatcher) {
    requests.fake.addLinkResult = { AddResult.OutOfSpace }
    val vm = viewModel()
    vm.setLink("https://youtu.be/abc")
    vm.submit()
    runCurrent()

    assertThat(vm.state.value.phase).isEqualTo(AddPhase.Problem(AddProblem.OutOfSpace))
  }

  @Test
  fun threePickedFilesBecomeThreeRequestsEachAddedOnlyOnceItsOwnCopyIsComplete() = runTest(main.dispatcher) {
    // F-19-AC3.
    val ids = listOf("one", "two", "three").map(::RequestId)
    requests.fake.addFilesResult = { AddFilesResult(ids, emptyList()) }
    val copies = ids.associateWith { MutableStateFlow(IntakeProgress(it, 0, 40L * 1024 * 1024, done = false, failed = null)) }
    copies.forEach { (id, flow) -> requests.fake.intakes[id] = flow }
    requests.fake.all.value = ids.map { RequestRowSamples.request(it.value, RequestState.PREPARING, kind = RequestKind.FILE) }
    val vm = viewModel()

    vm.pick(listOf("content://picker/1", "content://picker/2", "content://picker/3"))
    runCurrent()
    assertThat(vm.state.value.files).hasSize(3)
    assertThat(vm.state.value.canSubmit).isTrue()
    vm.setSeparateDialogue(false)
    vm.submit()
    runCurrent()

    val (sources, options) = requests.files.single()
    assertThat(sources.map { it.uri }).containsExactly("content://picker/1", "content://picker/2", "content://picker/3").inOrder()
    assertThat(sources.all { it.persisted }).isTrue()
    assertThat(sources.map { it.displayName }).containsExactly("clip-0.mp4", "clip-1.mp4", "clip-2.mp4").inOrder()
    assertThat(options).isEqualTo(FileOptions(audioOnly = false, separateDialogue = false))
    fun added() = (vm.state.value.phase as AddPhase.FilesAdded).files.map { it.added }
    assertThat(added()).containsExactly(false, false, false)

    copies.getValue(ids[1]).value = IntakeProgress(ids[1], 40L * 1024 * 1024, 40L * 1024 * 1024, done = true, failed = null)
    requests.fake.all.value = requests.fake.all.value.map { if (it.id == ids[1]) it.copy(state = RequestState.WAITING) else it }
    assertThat(added()).containsExactly(false, true, false).inOrder()
    assertThat((vm.state.value.phase as AddPhase.FilesAdded).files.map { it.name }).containsExactly("clip-0.mp4", "clip-1.mp4", "clip-2.mp4").inOrder()
  }

  @Test
  fun aLargeVideoPreselectsAudioOnlyAndRefusedFilesAreNamed() = runTest(main.dispatcher) {
    picked.answer = { uris -> uris.map { PickedFile(it, "long.mp4", "video/mp4", 600L * 1024 * 1024, null, PickedKind.VIDEO) } }
    requests.fake.addFilesResult = { AddFilesResult(emptyList(), listOf(FileRefusal("long.mp4", FileRefusalReason.TOO_LARGE, null))) }
    val vm = viewModel()
    vm.pick(listOf("content://picker/9"))
    runCurrent()
    assertThat(vm.state.value.audioOnly).isTrue()
    assertThat(vm.state.value.largeFile).isTrue()

    vm.submit()
    runCurrent()
    assertThat(vm.state.value.phase)
      .isEqualTo(AddPhase.Problem(AddProblem.Refused(1, listOf(FileRefusal("long.mp4", FileRefusalReason.TOO_LARGE, null)))))
    assertThat(vm.state.value.canSubmit).isTrue()
  }

  @Test
  fun theNotificationQuestionComesAfterTheFirstStoredRequestOnly() = runTest(main.dispatcher) {
    asker.state.value = true
    requests.fake.addLinkResult = { AddResult.OutOfSpace }
    val vm = viewModel()
    vm.setLink("https://youtu.be/abc")
    vm.submit()
    runCurrent()
    assertThat(vm.state.value.question).isEqualTo(NotificationQuestion.NONE)

    requests.fake.addLinkResult = { AddResult.Added(RequestId("new")) }
    vm.submit()
    runCurrent()
    assertThat(vm.state.value.question).isEqualTo(NotificationQuestion.ASKING)
    assertThat(vm.notifyMe()).isTrue()
    assertThat(vm.state.value.question).isEqualTo(NotificationQuestion.SYSTEM_PROMPT)
    vm.onSystemPromptResult(granted = true)
    assertThat(vm.state.value.question).isEqualTo(NotificationQuestion.NONE)
    assertThat(asker.answers).containsExactly("notify_me")
    assertThat(asker.promptResults).containsExactly(true)
  }

  @Test
  fun theStartModesOpenThePickerOnceOrFocusTheField() = runTest(main.dispatcher) {
    val saved = SavedStateHandle()
    val files = viewModel(AddClipStart.FILES, saved)
    assertThat(files.opensPickerFirst).isTrue()
    files.pickerOpened()
    assertThat(viewModel(AddClipStart.FILES, saved).opensPickerFirst).isFalse()
    assertThat(viewModel(AddClipStart.PASTE).focusesLink).isTrue()
    assertThat(viewModel(AddClipStart.CHOOSER).opensPickerFirst).isFalse()
  }

  @Test
  fun theLinkAndTheOptionsSurviveAProcessDeathAndAnotherClipStartsEmpty() = runTest(main.dispatcher) {
    val saved = SavedStateHandle()
    val vm = viewModel(saved = saved)
    vm.setLink("https://youtu.be/abc")
    vm.setAudioOnly(true)

    val again = viewModel(saved = saved)
    assertThat(again.state.value.link).isEqualTo("https://youtu.be/abc")
    assertThat(again.state.value.parsed).isNotNull()
    assertThat(again.state.value.audioOnly).isTrue()

    again.another()
    assertThat(again.state.value.link).isEmpty()
    assertThat(again.state.value.phase).isEqualTo(AddPhase.Editing)
  }

  @Test
  fun anAddedFilesFailureCardActionGoesToTheQueue() = runTest(main.dispatcher) {
    val vm = viewModel()
    vm.perform(RequestId("copy-no-space"), RequestAction.TRY_AUDIO_ONLY)
    runCurrent()
    assertThat(requests.fake.performed).containsExactly(RequestId("copy-no-space") to RequestAction.TRY_AUDIO_ONLY)
  }
}
