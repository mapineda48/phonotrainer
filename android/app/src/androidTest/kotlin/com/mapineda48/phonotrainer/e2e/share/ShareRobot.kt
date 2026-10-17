package com.mapineda48.phonotrainer.e2e.share

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.mapineda48.phonotrainer.core.domain.api.RequestRepository
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.feature.share.R
import com.mapineda48.phonotrainer.feature.share.ShareActivity
import com.mapineda48.phonotrainer.feature.share.ShareTags
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The share area's robot (work-plan WP-23; architecture 21.5): a real other app shares with PhonoTrainer through the
 * system ([SharingAppActivity] and [TestMediaProvider], in the test package's own process), and the robot reads the
 * sheet, the device queue and which app is on top. Every action returns after its effect is visible; waits have
 * timeouts and never sleep. WP-19's cross-area flows use it with the real computer stack once that is merged
 * ([shareText] then [addToQueue]: "the link sent by send-now after the sheet closed", SH-AC3, SH-AC5).
 */
@OptIn(ExperimentalTestApi::class)
class ShareRobot(private val compose: ComposeTestRule, private val requests: RequestRepository) {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app: Context = instrumentation.targetContext

  /** The sharing app's package: the test package, a different app with its own uid and process. */
  val sharingPackage: String = instrumentation.context.packageName

  val device: UiDevice = UiDevice.getInstance(instrumentation)

  private val authority = TestMediaProvider.AUTHORITY

  /** Files the sharing app is given with the next share (it keeps them where its provider serves them). */
  private val pending = LinkedHashMap<String, ByteArray>()

  /** A real MP4 (H.264 + AAC) made here, which the sharing app holds as [file]; its size in bytes. */
  fun putClip(file: String = CLIP_FILE, seconds: Int = 2): Long {
    val local = SyntheticClip.ensure(File(app.cacheDir, "share-$seconds-s.mp4"), seconds)
    put(file, local.readBytes())
    return local.length()
  }

  /** The sharing app holds [bytes] as [file] from the next share on. */
  fun put(file: String, bytes: ByteArray) {
    pending[file] = bytes
  }

  /**
   * A shared item of the sharing app: [file] (put first), shown as [name] with [type]; [declared] is the size its
   * provider reports (a number, which may be a lie; `none`; or null for the truth).
   */
  fun item(file: String = CLIP_FILE, name: String = "Street interview.mp4", type: String = "video/mp4", declared: String? = null): Uri =
    uri("file/$file", name, type, declared)

  /** An item streamed slowly through a pipe ([bytes] long, a chunk every [pauseMs]), as a cloud file is. */
  fun slowItem(bytes: Long, name: String = "Long talk.mp4", type: String = "video/mp4", pauseMs: Long = 40): Uri =
    uri("slow/$bytes", name, type, null).buildUpon().appendQueryParameter("pause", pauseMs.toString()).build()

  /** An AMR voice note, a type the computer cannot analyze (SH-AC10). */
  fun amrItem(name: String = "voice.amr"): Uri {
    put(name, "#!AMR\n".toByteArray() + ByteArray(256))
    return item(name, name, "audio/amr")
  }

  private fun uri(path: String, name: String, type: String, declared: String?): Uri =
    Uri.parse("content://$authority/$path").buildUpon()
      .appendQueryParameter("name", name)
      .appendQueryParameter("type", type)
      .apply { if (declared != null) appendQueryParameter("declared", declared) }
      .build()

  // -------------------------------------------------------------------------------------------------------------
  // Sharing

  /** The sharing app shares [text] (and [subject]) as `text/plain`, from its task or, with [newTask], a new one. */
  fun shareText(text: String, subject: String? = null, newTask: Boolean = false) {
    val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    subject?.let { share.putExtra(Intent.EXTRA_SUBJECT, it) }
    launch(share, newTask)
  }

  /** The sharing app shares [items]: `ACTION_SEND` for one, `ACTION_SEND_MULTIPLE` (stream list + `ClipData`) for more. */
  fun shareFiles(items: List<Uri>, type: String = "video/*", newTask: Boolean = false) {
    val share = if (items.size == 1) {
      Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, items.single())
    } else {
      Intent(Intent.ACTION_SEND_MULTIPLE).setType(type)
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items))
        .also { intent ->
          intent.clipData = ClipData(ClipDescription("shared media", arrayOf(type)), ClipData.Item(items.first())).apply {
            items.drop(1).forEach { addItem(ClipData.Item(it)) }
          }
        }
    }
    launch(share, newTask)
  }

  private fun launch(share: Intent, newTask: Boolean) {
    val files = Bundle().apply { pending.forEach { (name, bytes) -> putByteArray(name, bytes) } }
    pending.clear()
    // A fresh task each time: an earlier share's sharing app (or one still finishing) never swallows this one.
    app.startActivity(
      Intent().setClassName(sharingPackage, SharingAppActivity::class.java.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        .putExtra(SharingAppActivity.SHARE, share)
        .putExtra(SharingAppActivity.FILES, files)
        .putExtra(SharingAppActivity.NEW_TASK, newTask),
    )
    awaitSheet()
  }

  /** Ends the sharing app's task (and a sheet still in it). */
  fun finishSharingApp() {
    app.startActivity(
      Intent().setClassName(sharingPackage, SharingAppActivity::class.java.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(SharingAppActivity.FINISH, true),
    )
  }

  // -------------------------------------------------------------------------------------------------------------
  // The sheet

  /**
   * Whether a node matching [matcher] is on screen. Before the sheet's activity has composed (it is started by another
   * app, after the test began) and after it is gone there is no hierarchy at all, which reads as "none".
   */
  fun exists(matcher: SemanticsMatcher): Boolean = try {
    compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
  } catch (_: IllegalStateException) {
    false
  }

  private fun await(matcher: SemanticsMatcher, timeoutMs: Long = TIMEOUT_MS) {
    try {
      compose.waitUntil(timeoutMs) { exists(matcher) }
    } catch (timeout: ComposeTimeoutException) {
      throw AssertionError(
        "nothing on screen matched ${matcher.description} within $timeoutMs ms; on screen:\n${screen()}\nrequests: " +
          requests().map { "${it.state} ${it.lastError?.code} ${it.actions}" },
        timeout,
      )
    }
  }

  /** What the Compose rule sees, for a failure's message. */
  private fun screen(): String = try {
    val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes().size
    (0 until roots).joinToString("\n") { compose.onAllNodes(isRoot())[it].printToString(maxDepth = Int.MAX_VALUE) }
  } catch (_: IllegalStateException) {
    "(no composition)"
  }

  fun awaitSheet() = await(hasTestTag(ShareTags.SHEET))

  fun awaitTag(tag: String) = await(hasTestTag(tag))

  fun awaitText(text: String, substring: Boolean = true) = await(hasText(text, substring = substring))

  fun tap(tag: String) {
    awaitTag(tag)
    compose.onNodeWithTag(tag).performClick()
    compose.waitForIdle()
  }

  fun tapText(text: String) {
    awaitText(text, substring = false)
    compose.onNodeWithText(text).performClick()
    compose.waitForIdle()
  }

  /** "Add to queue" (or "Analyze"), then the request is stored. */
  fun addToQueue() = tap(ShareTags.PRIMARY)

  /** Answers the notification question "Not now" when the sheet asks it (the first request of a fresh install). */
  fun notNowIfAsked() {
    val asked = try {
      await(hasText(app.getString(R.string.share_notify_title)), QUESTION_WAIT_MS)
      true
    } catch (_: AssertionError) {
      false
    }
    if (asked) tapText(app.getString(R.string.share_not_now))
  }

  /**
   * Whether a share sheet is open: a `ShareActivity` that is not finishing, whatever covers it (the system's
   * permission dialog hides its composition from the Compose rule, so the activity is what is asked).
   */
  fun sheetOpen(): Boolean {
    var open = false
    instrumentation.runOnMainSync {
      val monitor = ActivityLifecycleMonitorRegistry.getInstance()
      open = LIVE_STAGES.any { stage ->
        monitor.getActivitiesInStage(stage).any { it is ShareActivity && !it.isFinishing }
      }
    }
    return open
  }

  /** Waits until the sheet has closed (its activity is finishing or gone). */
  fun awaitClosed(timeoutMs: Long = TIMEOUT_MS) = compose.waitUntil(timeoutMs) { !sheetOpen() }

  /** The sheet stays for [ms] (a bounded wait that fails as soon as it closes, never a sleep). */
  fun assertStaysOpen(ms: Long) {
    val closed = try {
      compose.waitUntil(ms) { !sheetOpen() }
      true
    } catch (_: ComposeTimeoutException) {
      false
    }
    check(!closed) { "the share sheet closed within $ms ms" }
  }

  /** The back key, as a learner presses it. */
  fun back() {
    device.pressBack()
    compose.waitForIdle()
  }

  /** The sharing app is the app on top again (SH-AC4, SH-AC11). */
  fun assertSharingAppOnTop() {
    check(device.wait(Until.hasObject(By.pkg(sharingPackage).text(SharingAppActivity.TEXT)), TIMEOUT_MS)) {
      "the sharing app is not on top; the current package is ${device.currentPackageName}"
    }
  }

  /** Every live activity of this app's process, by its stage. */
  fun activities(stage: Stage): List<android.app.Activity> {
    var found = emptyList<android.app.Activity>()
    instrumentation.runOnMainSync { found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).toList() }
    return found
  }

  /** Finishes a sheet a failed test left open. */
  fun finishSheets() {
    instrumentation.runOnMainSync {
      LIVE_STAGES.forEach { stage ->
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)
          .filterIsInstance<ShareActivity>().forEach { it.finish() }
      }
    }
  }

  // -------------------------------------------------------------------------------------------------------------
  // The device queue and TalkBack

  fun requests(): List<Request> = runBlocking { requests.requests().first() }

  /** Waits until the queue's requests satisfy [check] (polling the real repository, no sleep). */
  fun awaitRequests(timeoutMs: Long = TIMEOUT_MS, check: (List<Request>) -> Boolean): List<Request> {
    var last = emptyList<Request>()
    try {
      compose.waitUntil(timeoutMs) { requests().also { last = it }.let(check) }
    } catch (timeout: ComposeTimeoutException) {
      throw AssertionError("the requests never matched; last seen: ${last.map { "${it.state} ${it.lastError?.code} ${it.actions}" }}", timeout)
    }
    return last
  }

  /**
   * Turns touch exploration (what TalkBack turns on, and what `LocalTalkBackOn` reads) on or off through the test's
   * own accessibility connection; false when this device does not let it.
   */
  fun touchExploration(on: Boolean): Boolean {
    val automation = instrumentation.uiAutomation
    val info = automation.serviceInfo ?: return false
    info.flags = if (on) {
      info.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
    } else {
      info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
    }
    automation.serviceInfo = info
    val manager = app.getSystemService(AccessibilityManager::class.java)
    return try {
      compose.waitUntil(TIMEOUT_MS) { manager.isTouchExplorationEnabled == on }
      true
    } catch (_: ComposeTimeoutException) {
      false
    }
  }

  companion object {
    const val TIMEOUT_MS = 15_000L
    private val LIVE_STAGES = listOf(
      Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED,
    )
    const val CLIP_FILE = "clip.mp4"
    private const val QUESTION_WAIT_MS = 3_000L
  }
}
