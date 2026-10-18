package com.mapineda48.phonotrainer.feature.connect

import androidx.navigation3.runtime.NavKey
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import com.mapineda48.phonotrainer.core.navigation.NavTarget
import com.mapineda48.phonotrainer.core.navigation.Navigator
import com.mapineda48.phonotrainer.core.navigation.RequestsKey
import com.mapineda48.phonotrainer.core.navigation.StorageKey
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.ui.component.ConnectionAction
import org.junit.Test

/** Where Connect and Connection details lead (spec F-02 behaviour 6; architecture 16.2). */
class ConnectRoutesTest {
  @Test
  fun goToHomeLeavesConnectAndShowsHomeAtItsRoot() {
    val navigator = RecordingNavigator()
    ConnectExits.home(navigator)
    assertThat(navigator.calls).containsExactly("back", "tab:HOME").inOrder()
  }

  @Test
  fun theCardsControlsLeadWhereTheySay() {
    assertThat(DetailsDestinations.of(ConnectionAction.CONNECT)).isEqualTo(ConnectKey(ConnectMode.FROM_HOME))
    assertThat(DetailsDestinations.of(ConnectionAction.CONNECT_AGAIN)).isEqualTo(ConnectKey(ConnectMode.CONNECT_AGAIN))
    assertThat(DetailsDestinations.of(ConnectionAction.NEW_ADDRESS)).isEqualTo(ConnectKey(ConnectMode.NEW_ADDRESS))
    assertThat(DetailsDestinations.of(ConnectionAction.SEE_REQUESTS)).isEqualTo(RequestsKey)
    assertThat(DetailsDestinations.of(ConnectionAction.STORAGE)).isEqualTo(StorageKey)
    // "Why?" and "Details" are this page; "Sync now" is a call.
    listOf(ConnectionAction.WHY, ConnectionAction.DETAILS, ConnectionAction.SYNC_NOW).forEach {
      assertThat(DetailsDestinations.of(it)).isNull()
    }
  }

  private class RecordingNavigator : Navigator {
    val calls = mutableListOf<String>()

    override fun navigate(key: NavKey) {
      calls += "navigate:$key"
    }

    override fun back() {
      calls += "back"
    }

    override fun switchTab(tab: Tab) {
      calls += "tab:$tab"
    }

    override fun open(target: NavTarget) {
      calls += "open:$target"
    }
  }
}
