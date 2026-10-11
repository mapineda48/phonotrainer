package com.mapineda48.phonotrainer.shell

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.mapineda48.phonotrainer.core.navigation.HomeKey
import com.mapineda48.phonotrainer.core.navigation.LearnKey
import com.mapineda48.phonotrainer.core.navigation.NavTarget
import com.mapineda48.phonotrainer.core.navigation.Navigator
import com.mapineda48.phonotrainer.core.navigation.PracticeKey
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The one [Navigator] (architecture 16.2): a saved back stack per tab and the current tab. The screen shows the Home
 * stack with the current tab's stack on top, which gives the spec's Back order: close the overlay, pop the secondary
 * destination, from a tab root go Home, and from Home's root leave the app (the shell lets the system take that Back).
 *
 * Tab roots switch tabs instead of being pushed. A feed with a start (`WatchKey(start)`) becomes the Watch tab's new
 * root, and `PracticeKey(focus)` the Practice tab's; Back from either goes Home. Navigating to the key already on top
 * (a double tap) changes nothing: an equal key would share the entry's content key, so pushing or replacing it would
 * stack a duplicate or clear the shown entry's state (decision I-16).
 */
@Stable
internal class AppNavigator(
  private val stacks: Map<Tab, NavBackStack<NavKey>>,
  private val tab: MutableState<Tab>,
  private val scope: CoroutineScope,
  private val resolver: TargetResolver,
) : Navigator {
  val currentTab: Tab
    get() = tab.value

  fun stack(of: Tab): NavBackStack<NavKey> = stacks.getValue(of)

  /** Whether Back has anything to do inside the app; when false the system leaves it (Home at its root). */
  val handlesBack: Boolean
    get() = currentTab != Tab.HOME || stack(Tab.HOME).size > 1

  override fun navigate(key: NavKey) {
    when (key) {
      HomeKey -> show(Tab.HOME)
      LearnKey -> show(Tab.LEARN)
      is WatchKey -> if (key.start == null) show(Tab.WATCH) else root(Tab.WATCH, key)
      is PracticeKey -> if (key.focus == null) show(Tab.PRACTICE) else root(Tab.PRACTICE, key)
      else -> stack(currentTab).let { stack -> if (stack.lastOrNull() != key) stack.add(key) }
    }
  }

  /** Makes [key] the only entry of [of] and shows it; when it already is, only shows it. */
  private fun root(of: Tab, key: NavKey) {
    val stack = stack(of)
    if (stack.size == 1 && stack.single() == key) show(of) else replace(of, listOf(key))
  }

  override fun back() {
    val stack = stack(currentTab)
    when {
      stack.size > 1 -> stack.removeAt(stack.lastIndex)
      currentTab != Tab.HOME -> tab.value = Tab.HOME
    }
  }

  override fun switchTab(tab: Tab) {
    if (tab != currentTab) {
      this.tab.value = tab
    } else if (tab != Tab.WATCH) {
      val stack = stack(tab)
      while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
  }

  override fun open(target: NavTarget) {
    scope.launch { resolver.resolve(target, this@AppNavigator) }
  }

  /** Shows [of] as it was left. */
  fun show(of: Tab) {
    tab.value = of
  }

  /**
   * Replaces the stack of [of] with [keys] and shows it. The new keys are added before the old ones go, so the stack
   * is never empty; entries that disappear are popped (their state and ViewModels cleared).
   */
  fun replace(of: Tab, keys: List<NavKey>) {
    require(keys.isNotEmpty())
    val stack = stack(of)
    val old = stack.size
    stack.addAll(keys)
    repeat(old) { stack.removeAt(0) }
    tab.value = of
  }
}
