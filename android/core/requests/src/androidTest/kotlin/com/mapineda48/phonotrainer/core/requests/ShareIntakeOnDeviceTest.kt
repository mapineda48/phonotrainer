package com.mapineda48.phonotrainer.core.requests

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.common.hash.Sha256
import com.mapineda48.phonotrainer.core.common.hash.Sha256Hasher
import com.mapineda48.phonotrainer.core.domain.model.FileOptions
import com.mapineda48.phonotrainer.core.domain.model.FileSource
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.requests.android.QueueWorkNames
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The expedited copy of a shared item on the device (architecture 8.3, Android review H2a): enqueued while the share
 * sheet is visible, it goes on after the sheet finished and Home was pressed, and completes; a stopped copy of a
 * source that cannot be read again (a pipe) ends `COPY_FAILED` `copy_interrupted` with nothing left behind (QT-13);
 * the items of a multiple share, named in its `EXTRA_STREAM` and again in its `ClipData`, are each copied once.
 */
@RunWith(AndroidJUnit4::class)
class ShareIntakeOnDeviceTest {
  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun shared(uri: String) = FileSource(uri, displayName = null, mimeType = null, declaredBytes = null, persisted = false)

  @Test
  fun theExpeditedCopyGoesOnAfterTheSheetFinishedAndHomeWasPressed() = runBlocking {
    DeviceQueue().use { queue ->
      val size = 16L * 1024 * 1024
      val scenario = ActivityScenario.launch(SheetStandInActivity::class.java)
      var id: RequestId? = null
      scenario.onActivity {
        id = runBlocking { queue.intake.add(listOf(shared(TestMediaProvider.slowUri(context, size))), FileOptions(audioOnly = false), null) }
          .added.single()
      }
      val request = checkNotNull(id)
      scenario.onActivity { it.finish() }
      UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressHome()
      scenario.close()
      // Still copying once the sheet is gone.
      assertThat(queue.core.requests.get(request.value)!!.state).isEqualTo("PREPARING")

      assertThat(queue.awaitState(request, "WAITING", "COPY_FAILED")).isEqualTo("WAITING")

      val row = queue.core.requests.get(request.value)!!
      val expected = Sha256Hasher().apply {
        val buffer = ByteArray(TestMediaProvider.CHUNK)
        var done = 0L
        while (done < size) {
          val length = minOf(buffer.size.toLong(), size - done).toInt()
          for (i in 0 until length) buffer[i] = TestMediaProvider.patternByte(done + i)
          update(buffer, 0, length)
          done += length
        }
      }.result()
      assertThat(row.fileSha256).isEqualTo(expected.sha256)
      assertThat(row.fileBytes).isEqualTo(size)
      val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(QueueWorkNames.shareIntake(request)).get()
      assertThat(work.single().state).isEqualTo(WorkInfo.State.SUCCEEDED)
    }
  }

  @Test
  fun aStoppedCopyOfAPipeFailsAsInterruptedAndLeavesNothing() = runBlocking {
    DeviceQueue().use { queue ->
      val id = queue.intake.add(listOf(shared(TestMediaProvider.slowUri(context, 32L * 1024 * 1024))), FileOptions(audioOnly = false), null)
        .added.single()
      withTimeout(TIMEOUT_MS) { while ((queue.live.copied.value[id.value]?.bytes ?: 0) < STARTED_BYTES) delay(POLL_MS) }

      WorkManager.getInstance(context).cancelUniqueWork(QueueWorkNames.shareIntake(id))

      assertThat(queue.awaitState(id, "COPY_FAILED", "WAITING")).isEqualTo("COPY_FAILED")
      val row = queue.core.requests.get(id.value)!!
      assertThat(row.stateReason).isEqualTo("copy_interrupted")
      assertThat(queue.files.dir(id).listFiles().orEmpty().toList()).isEmpty()
    }
  }

  @Test
  fun theItemsOfAMultipleShareAreEachCopiedOnceFromTheIntentsClipData() = runBlocking {
    DeviceQueue().use { queue ->
      val files = listOf("multi-a.bin", "multi-b.bin").mapIndexed { n, name ->
        File(context.cacheDir, name).apply { writeBytes(ByteArray(300_000 + n) { (it * (n + 3)).toByte() }) }
      }
      val uris = files.map { Uri.parse(TestMediaProvider.fileUri(context, it.name)) }
      val intent = Intent(context, SheetStandInActivity::class.java).apply {
        action = Intent.ACTION_SEND_MULTIPLE
        type = "video/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        clipData = ClipData.newRawUri(null, uris[0]).apply { addItem(ClipData.Item(uris[1])) }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      try {
        ActivityScenario.launch<SheetStandInActivity>(intent).use { scenario ->
          var ids: List<RequestId> = emptyList()
          scenario.onActivity { sheet ->
            // What the sheet hands over (architecture 12): the EXTRA_STREAM list and every ClipData item.
            val streams = IntentCompat.getParcelableArrayListExtra(sheet.intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            val clip = checkNotNull(sheet.intent.clipData)
            val items = streams + (0 until clip.itemCount).map { clip.getItemAt(it).uri }
            ids = runBlocking { queue.intake.add(items.map { shared(it.toString()) }, FileOptions(audioOnly = false), null) }.added
          }

          assertThat(ids).hasSize(2)
          ids.zip(files).forEach { (id, file) ->
            assertThat(queue.awaitState(id, "WAITING", "COPY_FAILED")).isEqualTo("WAITING")
            assertThat(queue.core.requests.get(id.value)!!.fileSha256).isEqualTo(Sha256.of(file).sha256)
          }
        }
      } finally {
        files.forEach { it.delete() }
      }
    }
  }

  private companion object {
    const val TIMEOUT_MS = 60_000L
    const val POLL_MS = 50L
    const val STARTED_BYTES = 256L * 1024
  }
}
