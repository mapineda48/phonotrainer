package com.mapineda48.phonotrainer.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.Appearance
import com.mapineda48.phonotrainer.core.domain.model.CoachMarkId
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.StorageBudget
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The settings and coach-mark stores of architecture 4.5 on a real preferences file. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStoresTest {
  @get:Rule
  val folder = TemporaryFolder()

  private val scope = TestScope(UnconfinedTestDispatcher())
  private val storeScope = CoroutineScope(UnconfinedTestDispatcher(scope.testScheduler))
  private val store by lazy {
    PreferenceDataStoreFactory.create(scope = storeScope) { File(folder.root, "settings.preferences_pb") }
  }

  @After
  fun tearDown() {
    storeScope.cancel()
    scope.cancel()
  }

  @Test
  fun anEmptyFileReadsAsTheDefaults() = runTest(scope.testScheduler) {
    val repository = DataStoreSettingsRepository(store, storeScope)
    repository.awaitLoaded()
    assertThat(repository.settings.value).isEqualTo(UserSettings())
  }

  @Test
  fun everySettingRoundTrips() = runTest(scope.testScheduler) {
    val repository = DataStoreSettingsRepository(store, storeScope)
    val changed = UserSettings(
      appearance = Appearance.DARK,
      defaultSpeed = 0.75f,
      excludedFromMix = setOf(JobId("20261004-133519-a77c")),
      storageBudget = StorageBudget.FreeSpaceOnly,
      welcomeDone = true,
    )
    repository.update { changed }

    val reopened = DataStoreSettingsRepository(store, storeScope)
    reopened.awaitLoaded()
    assertThat(reopened.settings.value).isEqualTo(changed)
  }

  @Test
  fun aValueThisVersionDoesNotKnowReadsAsTheDefault() = runTest(scope.testScheduler) {
    store.edit { it[stringPreferencesKey("appearance")] = "SEPIA" }
    val repository = DataStoreSettingsRepository(store, storeScope)
    repository.awaitLoaded()
    assertThat(repository.settings.value.appearance).isEqualTo(Appearance.SYSTEM)
  }

  @Test
  fun coachMarksShowUntilDismissedAndResetBringsThemBack() = runTest(scope.testScheduler) {
    val marks = DataStoreCoachMarkRepository(store)
    val tapWord = CoachMarkId("tap_word")

    assertThat(marks.shouldShow(tapWord).first()).isTrue()
    marks.dismiss(tapWord)
    assertThat(marks.shouldShow(tapWord).first()).isFalse()
    assertThat(marks.shouldShow(CoachMarkId("swipe")).first()).isTrue()
    marks.resetAll()
    assertThat(marks.shouldShow(tapWord).first()).isTrue()
  }
}
