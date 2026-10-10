package com.mapineda48.phonotrainer.core.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme

/** One action of an empty state: its label, its emphasis and what it does. */
data class EmptyStateAction(
  val label: String,
  val onClick: () -> Unit,
  val kind: PhonoButtonKind = PhonoButtonKind.PRIMARY,
)

/** The empty-state scaffold of design 9.21: illustration (decorative), title, body and up to two actions. */
@Composable
fun EmptyState(
  @DrawableRes illustration: Int?,
  title: String,
  body: String,
  modifier: Modifier = Modifier,
  actions: List<EmptyStateAction> = emptyList(),
) {
  Column(
    modifier = modifier.padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    illustration?.let { Image(painter = painterResource(it), contentDescription = null) }
    Text(title, style = MaterialTheme.typography.titleLarge)
    Text(body, style = MaterialTheme.typography.bodyMedium)
    actions.forEachIndexed { index, action ->
      PhonoButton(
        text = action.label,
        onClick = action.onClick,
        kind = action.kind,
        modifier = Modifier.testTag("${UiTags.EMPTY_ACTION}.$index"),
      )
    }
  }
}

@Preview
@Composable
private fun EmptyStatePreview() {
  PhonoTheme {
    EmptyState(
      illustration = null,
      title = "Nothing saved yet",
      body = "Save a short to find it here.",
      actions = listOf(EmptyStateAction("Go to Watch", {})),
    )
  }
}
