package com.mapineda48.phonotrainer.core.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme

/**
 * Text with IPA inside /…/, […] or after "→" (architecture 15.3). Placeholder: plain text; WP-03 sets the IPA in
 * Charis and builds the accessible text from symbol names (A-05).
 */
@Composable
fun IpaText(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
  Text(text = text, modifier = modifier, style = style)
}

@Preview
@Composable
private fun IpaTextPreview() {
  PhonoTheme { IpaText("water /ˈwɔtɚ/ → [ˈwɔɾɚ]") }
}
