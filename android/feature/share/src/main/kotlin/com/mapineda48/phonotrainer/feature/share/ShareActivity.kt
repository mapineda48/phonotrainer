package com.mapineda48.phonotrainer.feature.share

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.ui.DestinationStub
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import dagger.hilt.android.AndroidEntryPoint

/**
 * The share target's placeholder (architecture 12): a translucent activity whose sheet reads nothing from the intent
 * and adds nothing. WP-23 replaces the sheet; the manifest entry (filters, empty task affinity) is already final.
 */
@AndroidEntryPoint
class ShareActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    setContent { PhonoTheme { ShareStubSheet(onClose = ::close) } }
  }

  /** Back to the sharing app: when the sheet is the root of its own task, the task goes too. */
  private fun close() {
    if (isTaskRoot) finishAndRemoveTask() else finish()
  }
}

@Composable
internal fun ShareStubSheet(onClose: () -> Unit, modifier: Modifier = Modifier) {
  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
    Surface(modifier = Modifier.navigationBarsPadding()) {
      DestinationStub(title = stringResource(R.string.share_title), lede = stringResource(R.string.share_lede)) {
        PhonoButton(
          text = stringResource(R.string.share_close),
          onClick = onClose,
          modifier = Modifier.testTag(ShareTags.CLOSE),
          kind = PhonoButtonKind.SECONDARY,
        )
      }
    }
  }
}

@Preview
@Composable
private fun ShareStubSheetPreview() {
  PhonoTheme { ShareStubSheet(onClose = {}) }
}
