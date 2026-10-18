package com.mapineda48.phonotrainer.core.sync.engine

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * [ForegroundState] from `ProcessLifecycleOwner`: at least STARTED while any activity of the app is visible, the share
 * sheet included (architecture 7.2). The observer is registered on the main thread, once.
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
      state.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
      lifecycle.addObserver(
        LifecycleEventObserver { _, _ -> state.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
      )
    }
  }
}
