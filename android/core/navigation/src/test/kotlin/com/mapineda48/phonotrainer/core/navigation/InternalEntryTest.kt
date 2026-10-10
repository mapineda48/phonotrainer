package com.mapineda48.phonotrainer.core.navigation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.JobId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Internal targets travel only through the alias, and two targets make two distinct intents (architecture 13, 16.4). */
@RunWith(RobolectricTestRunner::class)
class InternalEntryTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun anAliasIntentCarriesItsTarget() {
    val target = NavTarget.ClipFeed(JobId("20261004-133519-a77c"))
    val intent = InternalEntry.intent(context, target)

    assertThat(intent.component?.className).isEqualTo(InternalEntry.ALIAS_CLASS)
    assertThat(InternalEntry.targetOf(intent)).isEqualTo(target)
  }

  @Test
  fun anyOtherIntentIsIgnoredEvenWithTheExtra() {
    val forged = Intent().setComponent(ComponentName(context.packageName, "com.mapineda48.phonotrainer.MainActivity"))
      .putExtra(InternalEntry.EXTRA_TARGET, PhonoNavSerialization.encode(NavTarget.Requests))

    assertThat(InternalEntry.targetOf(forged)).isNull()
    assertThat(InternalEntry.targetOf(null)).isNull()
  }

  @Test
  fun twoTargetsGiveTwoDistinctIntents() {
    val one = InternalEntry.intent(context, NavTarget.ClipFeed(JobId("a")))
    val two = InternalEntry.intent(context, NavTarget.ClipFeed(JobId("b")))

    assertThat(one.filterEquals(two)).isFalse()
    assertThat(one.data?.scheme).isEqualTo(InternalEntry.URI_SCHEME)
  }
}
