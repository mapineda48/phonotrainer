package com.mapineda48.phonotrainer.core.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme

/** Button emphasis (design 9.1): the primary action, a secondary one, or a quiet text action. */
enum class PhonoButtonKind { PRIMARY, SECONDARY, QUIET }

/** The app's button (placeholder visuals; 48 dp minimum target). */
@Composable
fun PhonoButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  kind: PhonoButtonKind = PhonoButtonKind.PRIMARY,
  enabled: Boolean = true,
) {
  val sized = modifier.heightIn(min = 48.dp)
  when (kind) {
    PhonoButtonKind.PRIMARY -> Button(onClick = onClick, modifier = sized, enabled = enabled) { Text(text) }
    PhonoButtonKind.SECONDARY -> OutlinedButton(onClick = onClick, modifier = sized, enabled = enabled) { Text(text) }
    PhonoButtonKind.QUIET -> TextButton(onClick = onClick, modifier = sized, enabled = enabled) { Text(text) }
  }
}

/** A card of the app (a sheet of paper on the page, design 9.1). */
@Composable
fun PhonoCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Card(modifier = modifier) { Column(modifier = Modifier.padding(16.dp), content = content) }
}

/** A modal bottom sheet of the app (design 9.9, 9.12). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhonoSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), content = content)
  }
}

@Preview
@Composable
private fun ControlsPreview() {
  PhonoTheme {
    PhonoCard {
      PhonoButton(text = "Watch", onClick = {})
      PhonoButton(text = "From the start", onClick = {}, kind = PhonoButtonKind.SECONDARY)
      PhonoButton(text = "Not now", onClick = {}, kind = PhonoButtonKind.QUIET)
    }
  }
}
