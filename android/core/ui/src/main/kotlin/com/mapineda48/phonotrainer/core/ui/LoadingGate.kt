package com.mapineda48.phonotrainer.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import kotlinx.coroutines.delay

/**
 * The loading rule of the spec, implemented once (architecture 3.1): nothing for 300 ms, then a placeholder, then
 * after 1 s the words "Loading…"; [content] as soon as [loading] is false.
 */
@Composable
fun LoadingGate(loading: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  if (!loading) {
    content()
    return
  }
  var stage by remember { mutableIntStateOf(0) }
  LaunchedEffect(Unit) {
    delay(300)
    stage = 1
    delay(700)
    stage = 2
  }
  Box(modifier = modifier.testTag(UiTags.LOADING)) {
    if (stage >= 1) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    if (stage >= 2) Text(stringResource(R.string.ui_loading))
  }
}

@Preview
@Composable
private fun LoadingGatePreview() {
  PhonoTheme { LoadingGate(loading = false) { Text("Ready") } }
}
