package com.mapineda48.phonotrainer.feature.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.model.Duplicate
import com.mapineda48.phonotrainer.core.navigation.InternalEntry
import com.mapineda48.phonotrainer.core.navigation.NavTarget
import com.mapineda48.phonotrainer.core.ui.a11y.LocalTalkBackOn
import com.mapineda48.phonotrainer.core.ui.extension.PhonoAppRoot
import com.mapineda48.phonotrainer.feature.share.intake.SharedIntents
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The share target (architecture 12, spec 8): a translucent activity in the sharing app's task (`taskAffinity=""`,
 * excluded from recents), whose sheet confirms a shared link or files and returns the learner to the app they shared
 * from. The intent is read once into bounded, untrusted input (20.2); nothing of it is forwarded, and only the app's
 * own targets are ever opened ("Open PhonoTrainer", "See requests", "Open it": through the internal alias, 16.4). The
 * content sits inside the app root with the learner's settings, so the sheet has the in-app appearance, palette,
 * motion and TalkBack state (decision I-13).
 */
@AndroidEntryPoint
class ShareActivity : ComponentActivity() {
  @Inject lateinit var settings: SettingsRepository

  private val viewModel: ShareViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    viewModel.start(SharedIntents.read(intent))
    // Not tied to the started state: a sheet that closes by itself while the learner is elsewhere goes all the same.
    lifecycleScope.launch {
      viewModel.state.first { it.phase is SharePhase.Closed }
      close()
    }
    setContent {
      val current by settings.settings.collectAsStateWithLifecycle()
      PhonoAppRoot(settings = current) {
        ShareRoute(
          viewModel = viewModel,
          onOpen = ::open,
          modifier = Modifier.semantics { testTagsAsResourceId = true },
        )
      }
    }
  }

  /**
   * Opens [target] in the app through the internal alias, in PhonoTrainer's own task (architecture 12, 16.4), then the
   * sheet goes.
   */
  private fun open(target: NavTarget) {
    startActivity(InternalEntry.intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    viewModel.close()
  }

  /** Back to the sharing app: when the sheet is the root of its own task (a `NEW_TASK` launch), the task goes too. */
  private fun close() {
    if (isFinishing) return
    if (isTaskRoot) finishAndRemoveTask() else finish()
  }
}

/** The sheet wired to its view model, the system permission prompt and the app's targets. */
@Composable
internal fun ShareRoute(viewModel: ShareViewModel, onOpen: (NavTarget) -> Unit, modifier: Modifier = Modifier) {
  // Not lifecycle-bound: the copy finishes and the sheet closes by itself even after Home.
  val state by viewModel.state.collectAsState()
  val talkBack = LocalTalkBackOn.current
  LaunchedEffect(talkBack) { viewModel.setTalkBack(talkBack) }
  val scope = rememberCoroutineScope()
  val prompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    viewModel.onSystemPromptResult(granted)
  }
  ShareSheet(
    state = state,
    modifier = modifier,
    actions = ShareActions(
      onAudioOnly = viewModel::setAudioOnly,
      onSubmit = { viewModel.submit() },
      onAnalyzeAgain = { viewModel.submit(force = true) },
      onClose = viewModel::close,
      onBack = viewModel::back,
      onOpenApp = { onOpen(NavTarget.Home) },
      onSeeRequests = { onOpen(NavTarget.Requests) },
      onOpenDuplicate = { onOpen(targetOf(it)) },
      onTryAudioOnly = viewModel::tryAudioOnly,
      onRemove = viewModel::removeFailed,
      onKeepAdding = viewModel::keepAdding,
      onStop = viewModel::stopAdding,
      onNotNow = viewModel::notNow,
      onNotifyMe = {
        scope.launch {
          val permission = viewModel.systemPermission
          if (viewModel.notifyMe() && permission != null) prompt.launch(permission)
        }
      },
    ),
  )
}

/** "Open it" on a duplicate: its request when this phone has one, else its clip, else Requests (spec 7.7). */
internal fun targetOf(duplicate: Duplicate): NavTarget = when {
  duplicate.requestId != null -> NavTarget.RequestDetail(checkNotNull(duplicate.requestId))
  duplicate.jobId != null -> NavTarget.ClipFeed(checkNotNull(duplicate.jobId))
  else -> NavTarget.Requests
}
