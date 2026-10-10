package com.mapineda48.phonotrainer.core.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey

/** The four tabs of the bottom bar (spec 5.1). */
enum class Tab { HOME, WATCH, LEARN, PRACTICE }

/** The key at the root of each tab's back stack. */
val Tab.rootKey: NavKey
  get() = when (this) {
    Tab.HOME -> HomeKey
    Tab.WATCH -> WatchKey()
    Tab.LEARN -> LearnKey
    Tab.PRACTICE -> PracticeKey()
  }

/**
 * Moves between destinations (architecture 16.2, 16.3); implemented once by the app shell (`AppNavigator`). Screens
 * receive it through their [EntryInstaller] and never hold a back stack themselves.
 */
interface Navigator {
  /** Pushes [key] on the current tab's stack; a tab's root key switches to that tab instead. */
  fun navigate(key: NavKey)

  /** The spec's Back order: close the top overlay, pop, from a tab root go Home, from Home leave the app. */
  fun back()

  /** Shows [tab]; reselecting the current tab pops it to its root, except Watch. */
  fun switchTab(tab: Tab)

  /** Resolves an internal target, falling back to its parent with the spec's notice when it no longer exists. */
  fun open(target: NavTarget)
}

/**
 * How a feature contributes its destinations (architecture 16.3): each feature binds one with `@IntoSet` in its own
 * Hilt module, and the shell builds `entryProvider { installers.forEach { with(it) { install(navigator) } } }`.
 */
fun interface EntryInstaller {
  fun EntryProviderScope<NavKey>.install(navigator: Navigator)
}

/** Entry metadata the shell's scene strategies read (architecture 16.2). */
object NavOverlay {
  /** Marks an entry that renders as a bottom sheet over the entries beneath it (an overlay scene). */
  const val SHEET: String = "phonotrainer.overlay.sheet"

  /** The metadata of a sheet overlay entry: `entry<LessonKey>(metadata = { NavOverlay.sheet() }) { … }`. */
  fun sheet(): Map<String, Any> = mapOf(SHEET to true)
}
