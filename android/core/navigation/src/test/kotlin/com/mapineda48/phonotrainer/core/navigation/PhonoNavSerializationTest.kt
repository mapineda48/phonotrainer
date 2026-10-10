package com.mapineda48.phonotrainer.core.navigation

import android.os.Parcel
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every key of architecture 16.1 survives the saved-state round trip that back stacks go through on rotation and
 * process death (through a real Parcel), and internal targets survive the intent extra (architecture 16.4).
 */
@RunWith(RobolectricTestRunner::class)
class PhonoNavSerializationTest {
  private val job = JobId("20261004-133519-a77c")

  private val everyKey: List<NavKey> = listOf(
    HomeKey,
    WatchKey(),
    WatchKey(FeedStart.TodaySet),
    WatchKey(FeedStart.Resume),
    WatchKey(FeedStart.InOrder(job, firstSegment = 3, fromStart = false)),
    WatchKey(FeedStart.ChangeMode(ChangeLabel("flapping"), onlyClip = job)),
    WatchKey(FeedStart.SavedMode),
    WatchKey(FeedStart.AtWord(job, segment = 2, word = 5, openLesson = true)),
    LearnKey,
    PracticeKey(focus = "flapping"),
    LibraryKey,
    ClipKey(job, ClipTab.SUMMARY),
    RequestsKey,
    RequestDetailKey(RequestId("5a0c4c5e-0b6e-4bd6-9f53-1f1b0bbf7c11")),
    AddClipKey(AddClipStart.PASTE),
    ConnectKey(ConnectMode.NEW_ADDRESS),
    ConnectionDetailsKey,
    PhenomenonKey(ChangeLabel("glottal_stop")),
    IpaChartKey(symbol = "ɾ"),
    InsightsKey,
    SavedKey,
    SettingsKey,
    StorageKey,
    YourComputerKey,
    NotificationSettingsKey,
    AboutKey,
    LicencesKey,
    HelpKey,
    WelcomeKey(restart = true),
    GlossaryIndexKey,
    LessonKey(job, segment = 1, word = 0, origin = LessonOrigin.FEED, presentation = LessonPresentation.SHEET),
    PhraseDetailsKey(job, segment = 4),
  )

  @Test
  fun everyKeySurvivesTheSavedStateRoundTripThroughAParcel() {
    val serializer = ListSerializer(PolymorphicSerializer(NavKey::class))
    val saved = encodeToSavedState(serializer, everyKey, PhonoNavSerialization.configuration)

    val parcel = Parcel.obtain()
    try {
      parcel.writeBundle(saved)
      parcel.setDataPosition(0)
      val restored = parcel.readBundle(javaClass.classLoader)!!
      assertThat(decodeFromSavedState(serializer, restored, PhonoNavSerialization.configuration))
        .containsExactlyElementsIn(everyKey).inOrder()
    } finally {
      parcel.recycle()
    }
  }

  @Test
  fun everyKeyClassIsRegistered() {
    // One instance of each key class above: a new key that is not registered fails the round trip test.
    assertThat(everyKey.map { it::class }.toSet()).hasSize(26)
  }

  @Test
  fun internalTargetsRoundTripAndGetDistinctRequestCodes() {
    val targets = listOf(
      NavTarget.ClipFeed(job),
      NavTarget.ClipFeed(JobId("20261005-090000-b001")),
      NavTarget.RequestDetail(RequestId("5a0c4c5e-0b6e-4bd6-9f53-1f1b0bbf7c11")),
      NavTarget.Requests,
      NavTarget.Home,
      NavTarget.WordLesson(job, segment = 2, word = 7),
    )

    targets.forEach { assertThat(PhonoNavSerialization.decode(PhonoNavSerialization.encode(it))).isEqualTo(it) }
    assertThat(targets.map(PhonoNavSerialization::requestCode).toSet()).hasSize(targets.size)
  }

  @Test
  fun aMalformedExtraIsNoTarget() {
    assertThat(PhonoNavSerialization.decode(null)).isNull()
    assertThat(PhonoNavSerialization.decode("{not json")).isNull()
    assertThat(PhonoNavSerialization.decode("""{"type":"unknown"}""")).isNull()
  }

  @Test
  fun eachTabHasItsRoot() {
    assertThat(Tab.entries.map { it.rootKey }).containsExactly(HomeKey, WatchKey(), LearnKey, PracticeKey()).inOrder()
  }
}
