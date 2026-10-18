package com.mapineda48.phonotrainer.core.sync.engine

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * [ForegroundState] from `ProcessLifecycleOwner`: at least STARTED while any activity of the app is visible, the share
 * sheet included (architecture 7.2). The observer is registered on the main thread, once, and removed when the scope
 * ends.
 */
@Singleton
internal class ProcessForegroundState @Inject constructor(
  @param:ApplicationScope private val scope: CoroutineScope,
) : ForegroundState {
  private val state = MutableStateFlow(false)

  override val foreground: StateFlow<Boolean> = state.asStateFlow()

  init {
    scope.launch(Dispatchers.Main.immediate) {
      val lifecycle = ProcessLifecycleOwner.get().lifecycle
      val observer = LifecycleEventObserver { _, _ -> state.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
      state.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
      lifecycle.addObserver(observer)
      // The application scope lives as long as the process; a scope that ends (each end-to-end test's) takes it away.
      try {
        awaitCancellation()
      } finally {
        lifecycle.removeObserver(observer)
      }
    }
  }
}
