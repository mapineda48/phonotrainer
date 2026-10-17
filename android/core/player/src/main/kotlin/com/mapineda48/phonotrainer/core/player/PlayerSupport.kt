package com.mapineda48.phonotrainer.core.player

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The dispatcher every player class runs on. Media3 players are confined to the thread of their application looper,
 * which here is the main thread; tests hand in a test dispatcher instead.
 */
internal class PlayerDispatchers(val main: CoroutineDispatcher) {
  @Inject constructor() : this(Dispatchers.Main.immediate)
}

/**
 * Whether the app is in the foreground (spec D-28: playback stops when it leaves; architecture 11.2: the pool releases
 * its codecs on the process's `ON_STOP`). A seam so tests on the managed device, where no activity starts the process
 * lifecycle, can drive it.
 */
internal interface ForegroundSignal {
  val foreground: StateFlow<Boolean>
}

/**
 * [ForegroundSignal] from [ProcessLifecycleOwner]: started means foreground. Its `ON_STOP` comes 700 ms after the last
 * activity stops, so a rotation does not count as leaving the app.
 */
@Singleton
internal class ProcessForegroundSignal @Inject constructor() : ForegroundSignal {
  private val state = MutableStateFlow(true)
  override val foreground: StateFlow<Boolean> = state.asStateFlow()

  init {
    val register = Runnable {
      val lifecycle = ProcessLifecycleOwner.get().lifecycle
      state.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
      lifecycle.addObserver(
        LifecycleEventObserver { _, _ -> state.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
      )
    }
    if (Looper.myLooper() == Looper.getMainLooper()) register.run() else Handler(Looper.getMainLooper()).post(register)
  }
}
