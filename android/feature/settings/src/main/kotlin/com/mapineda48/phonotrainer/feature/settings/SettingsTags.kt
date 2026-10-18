package com.mapineda48.phonotrainer.feature.settings

import com.mapineda48.phonotrainer.core.domain.model.EndOfShort
import com.mapineda48.phonotrainer.core.domain.model.JobId

/**
 * Test tags of :feature:settings ("settings.<screen>.<element>", work-plan 2.6). Tags and the content descriptions
 * they sit on are part of the test contract of the Robolectric screen tests and of the `e2e/settings` robot.
 */
object SettingsTags {
  // The Settings page (SettingsKey).
  const val PAGE = "settings.settings.page"
  const val APPEARANCE = "settings.settings.appearance"
  const val COLORS_STANDARD = "settings.settings.colors.standard"
  const val COLORS_CVD = "settings.settings.colors.cvd"
  const val PATTERNS = "settings.settings.patterns"
  const val MOTION = "settings.settings.motion"
  const val TEXT_SIZE = "settings.settings.textSize"
  const val SPEED = "settings.settings.speed"
  const val PLAY_EACH = "settings.settings.playEach"
  const val END_OF_SHORT = "settings.settings.endOfShort"
  const val CAPTIONS = "settings.settings.captions"
  const val LISTEN_FIRST = "settings.settings.listenFirst"
  const val TRACK = "settings.settings.track"
  const val MIX = "settings.settings.mix"
  const val QUESTIONS = "settings.settings.questions"
  const val GUIDANCE = "settings.settings.guidance"
  const val LINK_COMPUTER = "settings.settings.computer"
  const val LINK_STORAGE = "settings.settings.storage"
  const val LINK_NOTIFICATIONS = "settings.settings.notifications"
  const val LINK_HELP = "settings.settings.help"
  const val LINK_ABOUT = "settings.settings.about"

  /** One choice of the "At the end of a short" menu. */
  fun endOfShort(choice: EndOfShort): String = "settings.settings.endOfShort.${choice.name}"

  // "Clips in the Mix" (the sheet of "Clips left out of the Mix").
  const val MIX_SHEET = "settings.mix.sheet"
  const val MIX_EMPTY = "settings.mix.empty"
  const val MIX_DONE = "settings.mix.done"

  fun mixClip(id: JobId): String = "settings.mix.clip.${id.value}"

  // Your computer (YourComputerKey).
  const val COMPUTER_LIST = "settings.computer.list"
  const val COMPUTER_FACTS = "settings.computer.facts"
  const val COMPUTER_CHECK = "settings.computer.check"
  const val COMPUTER_DETAILS = "settings.computer.details"
  const val COMPUTER_NEW_ADDRESS = "settings.computer.newAddress"
  const val COMPUTER_CONNECT_AGAIN = "settings.computer.connectAgain"
  const val COMPUTER_FORGET = "settings.computer.forget"
  const val COMPUTER_FORGET_FAILED = "settings.computer.forgetFailed"
  const val COMPUTER_FORGOTTEN = "settings.computer.forgotten"
  const val COMPUTER_NONE = "settings.computer.none"
  const val COMPUTER_CONNECT = "settings.computer.connect"
  const val FORGET_DIALOG = "settings.computer.forgetDialog"
  const val FORGET_TITLE = "settings.computer.forgetDialog.title"
  const val FORGET_BODY = "settings.computer.forgetDialog.body"
  const val FORGET_CANCEL = "settings.computer.forgetDialog.cancel"
  const val FORGET_CONFIRM = "settings.computer.forgetDialog.confirm"

  // Storage (StorageKey).
  const val STORAGE_LIST = "settings.storage.list"
  const val STORAGE_TOTAL = "settings.storage.total"
  const val STORAGE_METER = "settings.storage.meter"
  const val STORAGE_TABLE = "settings.storage.table"
  const val STORAGE_BUDGET = "settings.storage.budget"
  const val STORAGE_DOWNLOAD_VIDEOS = "settings.storage.downloadVideos"
  const val STORAGE_FLOOR = "settings.storage.floor"
  const val STORAGE_BUDGET_REACHED = "settings.storage.budgetReached"
  const val STORAGE_PAUSED = "settings.storage.paused"
  const val STORAGE_BACKGROUND = "settings.storage.background"
  const val STORAGE_BATTERY = "settings.storage.battery"
  const val STORAGE_EMPTY = "settings.storage.empty"
  const val STORAGE_LOADING = "settings.storage.loading"
  const val STORAGE_ERROR = "settings.storage.error"
  const val STORAGE_RETRY = "settings.storage.retry"
  const val STORAGE_REMOVE_ALL = "settings.storage.removeAll"
  const val REMOVE_ALL_DIALOG = "settings.storage.removeAllDialog"
  const val REMOVE_ALL_TITLE = "settings.storage.removeAllDialog.title"
  const val REMOVE_ALL_BODY = "settings.storage.removeAllDialog.body"
  const val REMOVE_ALL_CANCEL = "settings.storage.removeAllDialog.cancel"
  const val REMOVE_ALL_CONFIRM = "settings.storage.removeAllDialog.confirm"
  const val REMOVE_DIALOG = "settings.storage.removeDialog"
  const val REMOVE_TITLE = "settings.storage.removeDialog.title"
  const val REMOVE_BODY = "settings.storage.removeDialog.body"
  const val REMOVE_CANCEL = "settings.storage.removeDialog.cancel"
  const val REMOVE_CONFIRM = "settings.storage.removeDialog.confirm"

  /** The table twin's row of a category (design 9.20): "clips", "videos", "waiting" or "cache". */
  fun storageCategory(key: String): String = "settings.storage.category.$key"

  fun storageClip(id: JobId): String = "settings.storage.clip.${id.value}"

  fun storageRemoveVideo(id: JobId): String = "settings.storage.clip.${id.value}.removeVideo"

  fun storageRemove(id: JobId): String = "settings.storage.clip.${id.value}.remove"

  fun storageKeep(id: JobId): String = "settings.storage.clip.${id.value}.keep"

  /** The per-clip download line (design 9.20). */
  fun storageDownload(id: JobId): String = "settings.storage.clip.${id.value}.download"

  fun storageDamaged(id: JobId): String = "settings.storage.clip.${id.value}.damaged"

  fun storageTryAgain(id: JobId): String = "settings.storage.clip.${id.value}.tryAgain"

  // Notifications (NotificationSettingsKey).
  const val NOTIFY_LIST = "settings.notifications.list"
  const val NOTIFY_OFF = "settings.notifications.off"
  const val NOTIFY_OPEN_SETTINGS = "settings.notifications.openSettings"
  const val NOTIFY_TURN_ON = "settings.notifications.turnOn"
  const val NOTIFY_READY = "settings.notifications.ready"
  const val NOTIFY_ATTENTION = "settings.notifications.attention"
  const val NOTIFY_SYSTEM = "settings.notifications.system"

  // Help (HelpKey).
  const val HELP_LIST = "settings.help.list"
  const val HELP_TOUR = "settings.help.tour"
  const val HELP_MARKS = "settings.help.marks"
  const val HELP_LEGEND = "settings.help.legend"
  const val HELP_HOW = "settings.help.how"

  // About (AboutKey).
  const val ABOUT_LIST = "settings.about.list"
  const val ABOUT_VERSION = "settings.about.version"
  const val ABOUT_API = "settings.about.api"
  const val ABOUT_LICENCE = "settings.about.licence"
  const val ABOUT_READ_LICENCE = "settings.about.readLicence"
  const val ABOUT_SOURCE = "settings.about.source"
  const val ABOUT_PRIVACY = "settings.about.privacy"
  const val ABOUT_LICENCES = "settings.about.licences"

  // Open-source licences (LicencesKey).
  const val LICENCES_LIST = "settings.licences.list"
  const val LICENCES_LOADING = "settings.licences.loading"
  const val LICENCES_ERROR = "settings.licences.error"
  const val LICENCE_SHEET = "settings.licences.sheet"
  const val LICENCE_TEXT = "settings.licences.sheet.text"
  const val LICENCE_CLOSE = "settings.licences.sheet.close"

  /** The row of the licence entry [id] (a library's coordinates, `bundled:<name>` or `app`). */
  fun licence(id: String): String = "settings.licences.item.$id"
}
