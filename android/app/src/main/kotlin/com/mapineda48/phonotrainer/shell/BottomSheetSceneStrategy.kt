package com.mapineda48.phonotrainer.shell

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import com.mapineda48.phonotrainer.core.navigation.NavOverlay

/**
 * Renders an entry marked with [NavOverlay.sheet] as a modal bottom sheet over the entries beneath it, which stay
 * composed (architecture 16.2; the Navigation 3 bottom-sheet recipe): the lesson over a paused feed, phrase details.
 */
internal class BottomSheetSceneStrategy<T : Any> : SceneStrategy<T> {
  override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
    val last = entries.lastOrNull() ?: return null
    if (last.metadata[NavOverlay.SHEET] != true) return null
    val beneath = entries.dropLast(1)
    return BottomSheetScene(last.contentKey, beneath, last, onBack)
  }
}

@OptIn(ExperimentalMaterial3Api::class)
private class BottomSheetScene<T : Any>(
  override val key: Any,
  override val previousEntries: List<NavEntry<T>>,
  private val entry: NavEntry<T>,
  private val onBack: () -> Unit,
) : OverlayScene<T> {
  override val entries: List<NavEntry<T>> = listOf(entry)

  override val overlaidEntries: List<NavEntry<T>> = previousEntries

  override val content: @Composable () -> Unit = {
    ModalBottomSheet(onDismissRequest = onBack) { entry.Content() }
  }

  // Navigation 3 compares scenes to decide whether to animate; two scenes are the same when they show the same entry
  // over the same entries.
  override fun equals(other: Any?): Boolean =
    other is BottomSheetScene<*> && other.key == key && other.entry == entry && other.previousEntries == previousEntries

  override fun hashCode(): Int = (key.hashCode() * 31 + entry.hashCode()) * 31 + previousEntries.hashCode()
}
