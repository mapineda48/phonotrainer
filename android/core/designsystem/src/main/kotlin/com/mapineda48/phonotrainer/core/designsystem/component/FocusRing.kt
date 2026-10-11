package com.mapineda48.phonotrainer.core.designsystem.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import com.mapineda48.phonotrainer.core.designsystem.theme.LocalPhonoColors
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes

/** Set on an element while its focus ring is drawn (A-13), so screen tests can see the indicator. */
val FocusRingShown = SemanticsPropertyKey<Boolean>("PhonoFocusRingShown")

var SemanticsPropertyReceiver.focusRingShown by FocusRingShown

/**
 * The one focus ring of the app (design 11, A-13): 3 dp of `focus`, 2 dp outside the element, following its [shape].
 * Place it before the element's `clickable`, `toggleable` or `focusable`: it follows the focus of the target after it,
 * so keyboard, D-pad and switch access always show where they are.
 */
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(PhonoRadius.medium)): Modifier = composed {
  var focused by remember { mutableStateOf(false) }
  val color = LocalPhonoColors.current.focus
  this
    .onFocusChanged { focused = it.isFocused || it.hasFocus }
    .semantics { focusRingShown = focused }
    .then(
      if (focused) {
        Modifier.border(PhonoSizes.focusRing, color, shape)
      } else {
        Modifier
      },
    )
    .padding(PhonoSizes.focusOffset)
}
