package com.mapineda48.phonotrainer.core.content

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Both files reach the package's assets, where the app reads them (F-14-AC2: offline and before pairing). */
@RunWith(RobolectricTestRunner::class)
class AndroidBundledAssetsTest {
  private val assets = AndroidBundledAssets(ApplicationProvider.getApplicationContext<Context>())

  @Test
  fun theDidacticContentIsPackaged() {
    assertThat(assets.read(ContentAssets.DIDACTIC)).isEqualTo(SourceAssets.didactic)
  }

  @Test
  fun theReferenceSnapshotIsPackaged() {
    assertThat(assets.read(ContentAssets.REFERENCE)).isEqualTo(SourceAssets.reference)
  }
}
