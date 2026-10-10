package com.mapineda48.phonotrainer.core.network.api

import kotlinx.coroutines.flow.StateFlow

/** The computer's clock minus the phone's, learned from `hello` and `clock_skew` (architecture 5.3). */
interface ClockOffset {
  val offsetS: StateFlow<Long>

  fun update(serverTime: Long)
}
