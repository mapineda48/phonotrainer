package com.mapineda48.phonotrainer.e2e.share

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.mapineda48.phonotrainer.core.common.files.FileLayout
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.api.RequestRepository
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.remote.Capabilities
import com.mapineda48.phonotrainer.core.domain.model.remote.PairGrant
import dagger.hilt.android.testing.HiltAndroidRule
import java.io.File
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule

/**
 * What every share-area test shares (work-plan WP-23; architecture 21.5): the global test set with real repositories,
 * the Compose rule over every activity of the app's process, and [share], the area robot. Each test leaves no sheet
 * and no sharing app behind.
 */
abstract class ShareAreaTest {
  @get:Rule(order = 0)
  val hilt = HiltAndroidRule(this)

  @get:Rule(order = 1)
  val compose = createEmptyComposeRule()

  @Inject lateinit var requests: RequestRepository

  @Inject lateinit var pairing: PairingStore

  protected lateinit var share: ShareRobot

  protected val app: Context = InstrumentationRegistry.getInstrumentation().targetContext

  @Before
  fun setUpShare() {
    hilt.inject()
    share = ShareRobot(compose, requests)
  }

  @After
  fun tearDownShare() {
    share.finishSheets()
    share.finishSharingApp()
  }

  /** The words of a string resource of the app (tests find nodes by resource, never by literal text). */
  protected fun words(id: Int, vararg args: Any): String = app.getString(id, *args)

  /**
   * The computer's declared upload limit (spec 8.3: "larger than your computer accepts"). The pairing record is
   * written directly because pairing itself (WP-07, WP-05b) is not in this baseline; once it is, `TestSeeder.pair()`
   * with the fake computer's capabilities replaces this. The address is a documentation one that nothing answers.
   */
  protected fun computerAccepts(maxUploadBytes: Long) = runBlocking {
    pairing.save(
      PairGrant(
        deviceId = DeviceId("e2e-share-device"),
        token = "e2e-share-credential".toCharArray(),
        serverId = ServerId("e2e-share-computer"),
        name = "Test computer",
        libraryId = LibraryId("e2e-share-library"),
        apiVersion = 6,
        minAppVersion = 1,
        serverTime = 0,
      ),
      HostPort("192.0.2.10", 8765),
      "Test phone",
      Instant.EPOCH,
      0,
    )
    pairing.recordSeen(
      Instant.EPOCH,
      6,
      Capabilities(
        linkDownloads = true,
        downloaderVersion = null,
        dialogueSeparation = true,
        renditions = false,
        phoneVideo = false,
        resumableUploads = false,
        recordCompare = false,
        maxUploadBytes = maxUploadBytes,
        maxChunkBytes = 8L * 1024 * 1024,
        maxLinkDurationS = 3_600,
        freeBytes = Long.MAX_VALUE / 4,
      ),
    )
    val seen = pairing.current()?.capabilities?.maxUploadBytes
    check(seen == maxUploadBytes) { "the pairing record reads a limit of $seen, not $maxUploadBytes" }
  }

  /** The files a request keeps on this phone (`requests/<id>/`, architecture 4.6). */
  protected fun keptFiles(id: RequestId): List<String> =
    FileLayout.requestDir(app.filesDir, id).listFiles()?.map(File::getName)?.sorted().orEmpty()
}
