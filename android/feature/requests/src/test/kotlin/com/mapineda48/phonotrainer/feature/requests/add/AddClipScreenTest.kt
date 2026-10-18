package com.mapineda48.phonotrainer.feature.requests.add

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.domain.model.AddFilesResult
import com.mapineda48.phonotrainer.core.domain.model.AddResult
import com.mapineda48.phonotrainer.core.domain.model.Duplicate
import com.mapineda48.phonotrainer.core.domain.model.IntakeProgress
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.navigation.AddClipKey
import com.mapineda48.phonotrainer.core.navigation.AddClipStart
import com.mapineda48.phonotrainer.core.navigation.NavTarget
import com.mapineda48.phonotrainer.core.navigation.Navigator
import com.mapineda48.phonotrainer.core.navigation.RequestDetailKey
import com.mapineda48.phonotrainer.core.navigation.RequestsKey
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeNotificationPermissionAsker
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeComputerStatusRepository
import com.mapineda48.phonotrainer.core.testing.fake.requests.FakeLinkParser
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import com.mapineda48.phonotrainer.core.ui.component.RequestRowSamples
import com.mapineda48.phonotrainer.feature.requests.AddClipRoute
import com.mapineda48.phonotrainer.feature.requests.PICKER_TYPES
import com.mapineda48.phonotrainer.feature.requests.RequestsSamples
import com.mapineda48.phonotrainer.feature.requests.RequestsTags
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A clipboard that counts how often it is read (F-19-AC1: only when "Paste" is tapped). */
private class CountingClipboard(var text: String?) : Clipboard {
  var reads = 0
    private set

  override suspend fun getClipEntry(): ClipEntry? {
    reads++
    return text?.let { ClipEntry(ClipData.newPlainText("shared", it)) }
  }

  override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit

  override val nativeClipboard: ClipboardManager
    get() = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
}

/** The system picker, answered by the test: [launched] records each launch's input; the answer is [uris]. */
private class PickerRegistry(var uris: List<Uri>) : ActivityResultRegistry() {
  val launched = mutableListOf<Any?>()

  override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
    launched += input
    if (contract is ActivityResultContracts.OpenMultipleDocuments) dispatchResult(requestCode, uris)
  }
}

/** Records where the screen goes. */
private class RecordingNavigator : Navigator {
  val went = mutableListOf<NavKey>()
  var backs = 0

  override fun navigate(key: NavKey) {
    went += key
  }

  override fun back() {
    backs++
  }

  override fun switchTab(tab: Tab) = Unit

  override fun open(target: NavTarget) = Unit
}

/**
 * "Add a clip" (spec F-19; design 10.2 P-05): every state at font scale 2.0 in light and dark with the audit; "Paste"
 * reads the clipboard only on tap (F-19-AC1, AC2); the picker contract with 3 files, each its own request read "Added"
 * only once its copy is complete (F-19-AC3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h3000dp")
class AddClipScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val requests = RecordingRequests()
  private val parser = FakeLinkParser()
  private val status = FakeComputerStatusRepository(RequestRowSamples.unreachable)
  private val asker = FakeNotificationPermissionAsker(shouldAsk = false)
  private val picked = FakePickedFiles()
  private val navigator = RecordingNavigator()
  private val clipboard = CountingClipboard(null)
  private val registry = PickerRegistry(emptyList())

  private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

  private fun route(start: AddClipStart = AddClipStart.CHOOSER) {
    val vm = AddClipViewModel(AddClipKey(start), requests, parser, status, asker, picked, androidx.lifecycle.SavedStateHandle())
    val owner = object : ActivityResultRegistryOwner {
      override val activityResultRegistry: ActivityResultRegistry = registry
    }
    screen.setContent(ScreenVariant()) {
      CompositionLocalProvider(LocalClipboard provides clipboard, LocalActivityResultRegistryOwner provides owner) {
        AddClipRoute(AddClipKey(start), navigator, vm)
      }
    }
  }

  private fun field() = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(RequestsTags.ADD_LINK)))

  @Test
  fun everyStateRendersAndPassesTheAudit() {
    var state by mutableStateOf(RequestsSamples.addStates.first().second)
    screen.setContent(ScreenVariant.REQUIRED.first()) { AddClipScreen(state, AddClipActions()) }
    RequestsSamples.addStates.forEach { (name, next) ->
      state = next
      ScreenVariant.REQUIRED.forEach { variant ->
        screen.show(variant)
        try {
          when (name) {
            "files", "files refused" -> assertThat(exists(hasTestTag(RequestsTags.ADD_FILES))).isTrue()
            "duplicate" -> assertThat(exists(hasTestTag(RequestsTags.ADD_DUPLICATE))).isTrue()
            "link sent", "link queued", "files added" -> assertThat(exists(hasTestTag(RequestsTags.ADD_DONE))).isTrue()
            else -> assertThat(exists(hasTestTag(RequestsTags.ADD_SUBMIT))).isTrue()
          }
          screen.audit()
        } catch (failure: Throwable) {
          throw AssertionError("In state \"$name\" at $variant: ${failure.message}", failure)
        }
      }
    }
  }

  @Test
  fun pasteReadsTheClipboardOnlyWhenTappedAndFillsTheFirstLink() {
    // F-19-AC1: text on the clipboard is not read when the screen opens (nor in its "Paste a link" start mode).
    clipboard.text = "Great scene https://youtu.be/abc?si=xyz"
    route(AddClipStart.PASTE)
    compose.waitForIdle()
    assertThat(clipboard.reads).isEqualTo(0)
    assertThat(exists(hasTestTag(RequestsTags.ADD_SITE))).isFalse()

    compose.onNodeWithTag(RequestsTags.ADD_PASTE).performClick()
    compose.waitForIdle()
    assertThat(clipboard.reads).isEqualTo(1)
    field().assertTextContains("https://youtu.be/abc?si=xyz")
    compose.onNodeWithTag(RequestsTags.ADD_SITE).assertTextContains("YouTube")
    compose.onNodeWithTag(RequestsTags.ADD_SUBMIT).assertIsEnabled().assertTextContains("Add to queue")
  }

  @Test
  fun aClipboardWithoutALinkSaysSoAndNothingCanBeAdded() {
    // F-19-AC2.
    clipboard.text = "Meet me at noon"
    route()
    compose.onNodeWithTag(RequestsTags.ADD_PASTE).performClick()
    compose.waitForIdle()

    assertThat(exists(hasText("That doesn't look like a link."))).isTrue()
    compose.onNodeWithTag(RequestsTags.ADD_SUBMIT).assertIsNotEnabled()
  }

  @Test
  fun thePickerContractWithThreeFilesGivesThreeRequestsEachAddedAfterItsOwnCopy() {
    // F-19-AC3.
    val uris = (1..3).map { Uri.parse("content://picker.example/document/$it") }
    registry.uris = uris
    val ids = listOf("one", "two", "three").map(::RequestId)
    requests.fake.addFilesResult = { AddFilesResult(ids, emptyList()) }
    val copies = ids.associateWith { MutableStateFlow(IntakeProgress(it, 10L * 1024 * 1024, 40L * 1024 * 1024, done = false, failed = null)) }
    copies.forEach { (id, flow) -> requests.fake.intakes[id] = flow }
    requests.fake.all.value = ids.map { RequestRowSamples.request(it.value, RequestState.PREPARING, kind = RequestKind.FILE) }
    route()

    compose.onNodeWithTag(RequestsTags.ADD_CHOOSE).performClick()
    compose.waitForIdle()
    assertThat(registry.launched.single() as Array<*>).asList().containsExactlyElementsIn(PICKER_TYPES.toList())
    assertThat(picked.inspected.single()).containsExactlyElementsIn(uris.map(Uri::toString)).inOrder()
    (0..2).forEach { assertWithMessage("file $it").that(exists(hasTestTag(RequestsTags.file(it)))).isTrue() }

    compose.onNodeWithTag(RequestsTags.ADD_SUBMIT).performScrollTo().performClick()
    compose.waitForIdle()
    val sources = requests.files.single().first
    assertThat(sources.map { it.uri }).containsExactlyElementsIn(uris.map(Uri::toString)).inOrder()
    assertThat(sources.all { it.persisted }).isTrue()
    ids.forEach { id ->
      assertWithMessage("${id.value} preparing").that(exists(hasText("Preparing the file… 25 %", substring = true) and hasAnyAncestor(hasTestTag(RequestsTags.added(id))))).isTrue()
    }

    copies.getValue(ids[0]).value = IntakeProgress(ids[0], 40L * 1024 * 1024, 40L * 1024 * 1024, done = true, failed = null)
    requests.fake.all.value = requests.fake.all.value.map { if (it.id == ids[0]) it.copy(state = RequestState.WAITING) else it }
    compose.waitForIdle()
    assertThat(exists(hasText("Added", substring = true) and hasAnyAncestor(hasTestTag(RequestsTags.added(ids[0]))))).isTrue()
    assertThat(exists(hasText("Added", substring = true) and hasAnyAncestor(hasTestTag(RequestsTags.added(ids[1]))))).isFalse()
    assertThat(exists(hasText("Added", substring = true) and hasAnyAncestor(hasTestTag(RequestsTags.added(ids[2]))))).isFalse()
  }

  @Test
  fun choosingFilesFromTheDestinationOpensThePickerOnce() {
    registry.uris = listOf(Uri.parse("content://picker.example/document/9"))
    route(AddClipStart.FILES)
    compose.waitForIdle()
    assertThat(registry.launched).hasSize(1)
    assertThat(picked.inspected).hasSize(1)
  }

  @Test
  fun aDuplicateOpensItsRequestAndAfterAddingSeeRequestsOpensRequests() {
    val duplicate = Duplicate(RequestId("waiting"), null, "Coffee")
    requests.fake.addLinkResult = { if (it.force) AddResult.Added(RequestId("again")) else AddResult.DuplicateFound(duplicate) }
    clipboard.text = "https://youtu.be/abc"
    route()
    compose.onNodeWithTag(RequestsTags.ADD_PASTE).performClick()
    compose.onNodeWithTag(RequestsTags.ADD_SUBMIT).performScrollTo().performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(RequestsTags.ADD_OPEN_IT).performScrollTo().performClick()
    compose.onNodeWithTag(RequestsTags.ADD_AGAIN).performScrollTo().performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(RequestsTags.ADD_DONE).assertTextContains("Added to your queue.")
    compose.onNodeWithTag(RequestsTags.ADD_SEE_REQUESTS).performScrollTo().performClick()

    assertThat(navigator.went).containsExactly(RequestDetailKey(RequestId("waiting")), RequestsKey).inOrder()
    assertThat(requests.fake.links.map { it.force }).containsExactly(false, true).inOrder()
  }
}
