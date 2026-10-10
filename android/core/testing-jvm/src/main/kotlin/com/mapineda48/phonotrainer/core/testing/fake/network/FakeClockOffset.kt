package com.mapineda48.phonotrainer.core.testing.fake.network

import com.mapineda48.phonotrainer.core.network.api.ClockOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** [ClockOffset] against a settable phone clock: the offset is the server time minus [phoneNowS]. */
class FakeClockOffset(var phoneNowS: Long = 0, initialOffsetS: Long = 0) : ClockOffset {
  private val offset = MutableStateFlow(initialOffsetS)

  override val offsetS: StateFlow<Long> = offset

  override fun update(serverTime: Long) {
    offset.value = serverTime - phoneNowS
  }
}
