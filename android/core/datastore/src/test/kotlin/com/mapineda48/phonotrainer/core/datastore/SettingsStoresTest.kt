package com.mapineda48.phonotrainer.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.Appearance
import com.mapineda48.phonotrainer.core.domain.model.CaptionMode
import com.mapineda48.phonotrainer.core.domain.model.CoachMarkId
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice
import com.mapineda48.phonotrainer.core.domain.model.EndOfShort
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LessonGuidance
import com.mapineda48.phonotrainer.core.domain.model.MotionChoice
import com.mapineda48.phonotrainer.core.domain.model.PatternChoice
import com.mapineda48.phonotrainer.core.domain.model.StorageBudget
import com.mapineda48.phonotrainer.core.domain.model.Track
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
  fun everyFieldAwayFromItsDefaultRoundTrips() = runTest(scope.testScheduler) {
    val repository = DataStoreSettingsRepository(store, storeScope)
    val changed = UserSettings(
      appearance = Appearance.LIGHT,
      colors = ColorChoice.COLOR_VISION_FRIENDLY,
      patterns = PatternChoice.OFF,
      motion = MotionChoice.REDUCE,
      defaultSpeed = 0.5f,
      playEachShort = false,
      endOfShort = EndOfShort.NEXT,
      captions = CaptionMode.IPA,
      listenFirst = true,
      track = Track.DIALOGUE,
      excludedFromMix = setOf(JobId("20261004-133519-a77c"), JobId("20261005-101010-b88d")),
      questionsAfterSet = false,
      lessonGuidance = LessonGuidance.COMPACT,
      downloadVideos = false,
      storageBudget = StorageBudget.Limit(5_000_000_000),
      notifyReady = false,
      notifyAttention = false,
      welcomeDone = true,
      notificationsAsked = true,
    )
    // Every field differs from the default, so a field that is not stored fails the comparison below.
    val defaults = UserSettings()
    UserSettings::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.forEach {
      it.isAccessible = true
      assertThat(it.get(changed)).isNotEqualTo(it.get(defaults))
    }

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
