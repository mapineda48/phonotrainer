package com.mapineda48.phonotrainer.core.ui.extension

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.theme.isDark
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.ui.a11y.LocalReducedMotion
import com.mapineda48.phonotrainer.core.ui.a11y.LocalTalkBackOn
import com.mapineda48.phonotrainer.core.ui.a11y.rememberReducedMotion
import com.mapineda48.phonotrainer.core.ui.a11y.rememberTalkBackOn
import com.mapineda48.phonotrainer.core.ui.ipa.IpaReading
import com.mapineda48.phonotrainer.core.ui.ipa.LocalIpaReading

/**
 * The app's root composition (architecture 16.5, 17, 18; decision I-13): where the learner's settings become the
 * theme and the accessibility locals. `MainActivity`, the share sheet's `ShareActivity` (WP-23) and every screen test
 * (`:core:testing`'s `PhonoScreenRule`) wrap their content in it, so the second activity and the tests draw what the
 * app draws: the in-app appearance (not only the system's), the palette and the patterns, reduced motion from the
 * system animator scale and Settings > Motion (one value for both [LocalReducedMotion] and `PhonoTheme`'s motion),
 * TalkBack ([LocalTalkBackOn]: no autoplay, no auto-close of the share sheet, A-08, A-11) and how IPA is read aloud
 * ([LocalIpaReading], A-05).
 *
 * Window-level effects stay with each activity (edge to edge, `NightMode.apply`). It lives in `ui/extension/`, the
 * integrator's package of `:core:ui` (work-plan 3.2), because the app, the features and the test harness all reach
 * `:core:ui`.
 */
@Composable
fun PhonoAppRoot(
  settings: UserSettings,
  ipaReading: IpaReading = IpaReading(),
  reducedMotion: Boolean = rememberReducedMotion(settings.motion),
  talkBackOn: Boolean = rememberTalkBackOn(),
  content: @Composable () -> Unit,
) {
  CompositionLocalProvider(
    LocalReducedMotion provides reducedMotion,
    LocalTalkBackOn provides talkBackOn,
    LocalIpaReading provides ipaReading,
  ) {
    PhonoTheme(
      darkTheme = settings.appearance.isDark(),
      colors = settings.colors,
      patterns = settings.patterns,
      reducedMotion = reducedMotion,
      content = content,
    )
  }
}
