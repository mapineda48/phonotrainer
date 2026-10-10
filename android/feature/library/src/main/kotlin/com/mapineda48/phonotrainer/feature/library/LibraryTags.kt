package com.mapineda48.phonotrainer.feature.library

/** Test tags of :feature:library ("library.<screen>.<element>", work-plan 2.6). */
object LibraryTags {
  const val SEARCH = "library.list.search"
  const val LIST = "library.list.list"

  /** The stub's ViewModel instance marker, read by the shell's per-tab state proof. */
  const val INSTANCE = "library.list.instance"

  /** Row [number] of the stub's list. */
  fun row(number: Int): String = "library.list.row.$number"

  const val CLIP_WATCH = "library.clip.watch"
}
