package com.mapineda48.phonotrainer.shell

import androidx.navigation3.runtime.NavKey
import com.mapineda48.phonotrainer.core.navigation.AboutKey
import com.mapineda48.phonotrainer.core.navigation.AddClipKey
import com.mapineda48.phonotrainer.core.navigation.ClipKey
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectionDetailsKey
import com.mapineda48.phonotrainer.core.navigation.GlossaryIndexKey
import com.mapineda48.phonotrainer.core.navigation.HelpKey
import com.mapineda48.phonotrainer.core.navigation.HomeKey
import com.mapineda48.phonotrainer.core.navigation.InsightsKey
import com.mapineda48.phonotrainer.core.navigation.IpaChartKey
import com.mapineda48.phonotrainer.core.navigation.LearnKey
import com.mapineda48.phonotrainer.core.navigation.LessonKey
import com.mapineda48.phonotrainer.core.navigation.LibraryKey
import com.mapineda48.phonotrainer.core.navigation.LicencesKey
import com.mapineda48.phonotrainer.core.navigation.NotificationSettingsKey
import com.mapineda48.phonotrainer.core.navigation.PhenomenonKey
import com.mapineda48.phonotrainer.core.navigation.PhraseDetailsKey
import com.mapineda48.phonotrainer.core.navigation.PracticeKey
import com.mapineda48.phonotrainer.core.navigation.RequestDetailKey
import com.mapineda48.phonotrainer.core.navigation.RequestsKey
import com.mapineda48.phonotrainer.core.navigation.SavedKey
import com.mapineda48.phonotrainer.core.navigation.SettingsKey
import com.mapineda48.phonotrainer.core.navigation.StorageKey
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.navigation.WelcomeKey
import com.mapineda48.phonotrainer.core.navigation.YourComputerKey

/** Test tags of the app shell ("shell.<element>"); part of the test contract (work-plan 2.6). */
object ShellTags {
  const val SNACKBAR = "shell.snackbar"
  const val BOTTOM_BAR = "shell.bottomBar"
  const val BANNER = "shell.banner.connectAgain"
  const val BANNER_ACTION = "shell.banner.connectAgain.action"
  const val ANNOUNCER = "shell.announcer"

  fun tab(tab: Tab): String = "shell.tab.${tab.name.lowercase()}"

  /**
   * The tag the shell puts around every destination, whatever its feature draws: "destination.<name>". Explicit per
   * key, so it survives R8 and a feature replacing its stub.
   */
  fun destination(key: NavKey): String = "destination." + when (key) {
    HomeKey -> "home"
    is WatchKey -> "watch"
    LearnKey -> "learn"
    is PracticeKey -> "practice"
    LibraryKey -> "library"
    is ClipKey -> "clip"
    RequestsKey -> "requests"
    is RequestDetailKey -> "requestDetail"
    is AddClipKey -> "addClip"
    is ConnectKey -> "connect"
    ConnectionDetailsKey -> "connectionDetails"
    is PhenomenonKey -> "phenomenon"
    is IpaChartKey -> "ipaChart"
    InsightsKey -> "insights"
    SavedKey -> "saved"
    SettingsKey -> "settings"
    StorageKey -> "storage"
    YourComputerKey -> "yourComputer"
    NotificationSettingsKey -> "notificationSettings"
    AboutKey -> "about"
    LicencesKey -> "licences"
    HelpKey -> "help"
    is WelcomeKey -> "welcome"
    GlossaryIndexKey -> "glossaryIndex"
    is LessonKey -> "lesson"
    is PhraseDetailsKey -> "phraseDetails"
    else -> "unknown"
  }
}
