package com.mapineda48.phonotrainer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mapineda48.phonotrainer.core.designsystem.theme.NightMode
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.theme.isDark
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.api.UserMessages
import com.mapineda48.phonotrainer.core.navigation.EntryInstaller
import com.mapineda48.phonotrainer.core.navigation.InternalEntry
import com.mapineda48.phonotrainer.core.navigation.NavTarget
import com.mapineda48.phonotrainer.core.navigation.WelcomeKey
import com.mapineda48.phonotrainer.core.ui.a11y.LocalReducedMotion
import com.mapineda48.phonotrainer.core.ui.a11y.LocalTalkBackOn
import com.mapineda48.phonotrainer.core.ui.a11y.rememberReducedMotion
import com.mapineda48.phonotrainer.core.ui.a11y.rememberTalkBackOn
import com.mapineda48.phonotrainer.core.ui.ipa.LocalIpaReading
import com.mapineda48.phonotrainer.shell.AppNavigator
import com.mapineda48.phonotrainer.shell.PhonoShell
import com.mapineda48.phonotrainer.shell.ShellAnnouncer
import com.mapineda48.phonotrainer.shell.ShellInputs
import com.mapineda48.phonotrainer.shell.ShellIpaReading
import com.mapineda48.phonotrainer.shell.TargetResolver
import com.mapineda48.phonotrainer.shell.rememberAppNavigator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * The single activity (architecture 16.5): the splash until the settings are read, edge to edge, the theme from
 * `UserSettings.appearance`, how IPA is read aloud (A-05), and the shell. Internal targets are honoured only through
 * the `.InternalEntry` alias, in [onCreate] only when there is no saved state, and in [onNewIntent]; once applied, the
 * target is removed from the activity's intent, so a rotation or a process-death restore never navigates again
 * (architecture 16.4).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  @Inject lateinit var installers: Set<@JvmSuppressWildcards EntryInstaller>

  @Inject lateinit var resolver: TargetResolver

  @Inject lateinit var settings: SettingsRepository

  @Inject lateinit var userMessages: UserMessages

  @Inject lateinit var computerStatus: ComputerStatusRepository

  @Inject lateinit var announcer: ShellAnnouncer

  @Inject lateinit var ipaReading: ShellIpaReading

  private val pendingTarget = MutableStateFlow<NavTarget?>(null)

  @Volatile private var settingsRead = false

  /** The shell's navigator, for the end-to-end tests that drive every destination. */
  @VisibleForTesting
  internal var navigator: AppNavigator? = null
    private set

  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen().setKeepOnScreenCondition { !settingsRead }
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    if (savedInstanceState == null && takeTarget(intent)) {
      // The launch intent carried the target: keep the intent (same identity) without it, as architecture 16.4 asks.
      setIntent(Intent(intent).apply { removeExtra(InternalEntry.EXTRA_TARGET) })
    }
    // Welcome on the first launch from the launcher only: never on a restore, never for an internal target.
    val mayWelcome = savedInstanceState == null && pendingTarget.value == null
    lifecycleScope.launch {
      settings.awaitLoaded()
      settingsRead = true
    }
    val inputs = ShellInputs(installers, resolver, userMessages, computerStatus.status, announcer)

    setContent {
      val current by settings.settings.collectAsStateWithLifecycle()
      val reading by ipaReading.reading.collectAsStateWithLifecycle()
      val navigator = rememberAppNavigator(resolver)
      SideEffect { this.navigator = navigator }
      LaunchedEffect(navigator) {
        settings.awaitLoaded()
        if (mayWelcome && !settings.settings.value.welcomeDone) navigator.navigate(WelcomeKey())
      }
      LaunchedEffect(navigator) {
        pendingTarget.filterNotNull().collect { target ->
          pendingTarget.value = null
          navigator.open(target)
        }
      }
      LaunchedEffect(current.appearance) {
        settings.awaitLoaded()
        NightMode.apply(this@MainActivity, current.appearance)
      }
      CompositionLocalProvider(
        LocalReducedMotion provides rememberReducedMotion(current.motion),
        LocalTalkBackOn provides rememberTalkBackOn(),
        LocalIpaReading provides reading,
      ) {
        PhonoTheme(
          darkTheme = current.appearance.isDark(),
          colors = current.colors,
          patterns = current.patterns,
          reducedMotion = LocalReducedMotion.current,
        ) {
          PhonoShell(inputs, navigator, Modifier.semantics { testTagsAsResourceId = true })
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    takeTarget(intent)
  }

  /**
   * Queues the target of an intent that came through the alias; true when there was one. A new intent never becomes
   * the activity's intent (no `setIntent` in [onNewIntent]), so it cannot be applied again on a recreate.
   */
  private fun takeTarget(intent: Intent?): Boolean {
    val target = InternalEntry.targetOf(intent) ?: return false
    pendingTarget.value = target
    return true
  }
}
