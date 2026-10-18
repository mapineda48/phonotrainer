package com.mapineda48.phonotrainer.feature.home

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import com.mapineda48.phonotrainer.core.navigation.ConnectionDetailsKey
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.PhenomenonKey
import com.mapineda48.phonotrainer.core.navigation.RequestsKey
import com.mapineda48.phonotrainer.core.navigation.StorageKey
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.ui.component.ConnectionAction
import org.junit.Test

/** Where Home's controls lead (spec F-03, architecture 16.1). */
class HomeRoutesTest {
  private val job = HomeFixtures.job

  @Test
  fun startOpensTheMixAtTodaysSet() {
    // F-03-AC1.
    assertThat(HomeDestinations.today).isEqualTo(WatchKey(FeedStart.TodaySet))
  }

  @Test
  fun aClipsPlayActionOpensItInOrderAtItsResumeShort() {
    // F-03-AC8: "In order" on the clip; the feed resumes it (short 1 if never opened).
    assertThat(HomeDestinations.watchClip(job)).isEqualTo(WatchKey(FeedStart.InOrder(job, firstSegment = null, fromStart = false)))
  }

  @Test
  fun keepWatchingOpensTheFeedWhereTheLearnerStopped() {
    // F-03-AC6.
    assertThat(HomeDestinations.keepWatching(ShortId(job, 12))).isEqualTo(WatchKey(FeedStart.InOrder(job, firstSegment = 12, fromStart = false)))
  }

  @Test
  fun aChipOpensItsChangeModeOrItsLearnPageWhenItHasNoShorts() {
    // F-03-AC7 and D-48.
    assertThat(HomeDestinations.change(ChangeLabel("flapping"), 34)).isEqualTo(WatchKey(FeedStart.ChangeMode(ChangeLabel("flapping"), onlyClip = null)))
    assertThat(HomeDestinations.change(ChangeLabel("th_stopping"), 0)).isEqualTo(PhenomenonKey(ChangeLabel("th_stopping")))
  }

  @Test
  fun theStatusCardsControlsLeadToTheirScreens() {
    assertThat(HomeDestinations.connection(ConnectionAction.CONNECT)).isEqualTo(ConnectKey(ConnectMode.FROM_HOME))
    assertThat(HomeDestinations.connection(ConnectionAction.CONNECT_AGAIN)).isEqualTo(ConnectKey(ConnectMode.CONNECT_AGAIN))
    assertThat(HomeDestinations.connection(ConnectionAction.NEW_ADDRESS)).isEqualTo(ConnectKey(ConnectMode.NEW_ADDRESS))
    assertThat(HomeDestinations.connection(ConnectionAction.WHY)).isEqualTo(ConnectionDetailsKey)
    assertThat(HomeDestinations.connection(ConnectionAction.DETAILS)).isEqualTo(ConnectionDetailsKey)
    assertThat(HomeDestinations.connection(ConnectionAction.SEE_REQUESTS)).isEqualTo(RequestsKey)
    assertThat(HomeDestinations.connection(ConnectionAction.STORAGE)).isEqualTo(StorageKey)
    // Carried out on Home (Sync now, the inline action) or by the system (Battery settings).
    assertThat(HomeDestinations.connection(ConnectionAction.SYNC_NOW)).isNull()
    assertThat(HomeDestinations.connection(ConnectionAction.ATTENTION)).isNull()
    assertThat(HomeDestinations.connection(ConnectionAction.BATTERY_SETTINGS)).isNull()
  }
}
