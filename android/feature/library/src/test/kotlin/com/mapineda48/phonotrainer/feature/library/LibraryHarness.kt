package com.mapineda48.phonotrainer.feature.library

import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.ClipDataSummary
import com.mapineda48.phonotrainer.core.domain.model.ClipMeta
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.PairedComputer
import com.mapineda48.phonotrainer.core.domain.model.Phrase
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.Word
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeClipRepository
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** [FakeClipRepository] that counts `clips()` calls, can fail them, and can hold `setKept` until released. */
class CountingClips(val fake: FakeClipRepository = FakeClipRepository()) : ClipRepository by fake {
  var calls = 0
    private set

  /** The next `clips()` calls fail while this is above zero (each failure takes one). */
  var failures = 0
  var keepGate: CompletableDeferred<Unit>? = null
  var keeps = 0
    private set

  override fun clips(query: ClipQuery): Flow<List<ClipSummary>> {
    calls++
    if (failures > 0) {
      failures--
      return flow { throw IllegalStateException("the catalogue is unreadable") }
    }
    return fake.clips(query)
  }

  override suspend fun setKept(id: JobId, kept: Boolean) {
    keeps++
    keepGate?.await()
    fake.setKept(id, kept)
  }
}

object LibraryFixtures {
  /** A paired computer last synced at [lastSync]. */
  fun paired(lastSync: Instant?) = PairedComputer(
    serverId = ServerId("server-1"),
    name = "Desk PC",
    deviceId = DeviceId("device-1"),
    deviceName = "Test phone",
    address = HostPort("192.0.2.10", 8765),
    libraryId = LibraryId("library-1"),
    pairedAt = Instant.EPOCH,
    lastSeen = lastSync,
    lastSync = lastSync,
    apiVersion = 6,
    capabilities = null,
  )

  private fun word(index: Int, text: String) = Word(
    index = index,
    text = text,
    startS = index.toDouble(),
    endS = index + 0.5,
    dictIpa = null,
    canonicalIpa = null,
    realizedIpa = null,
    canonicalPhones = emptyList(),
    realizedPhones = emptyList(),
    changes = emptyList(),
    diffCost = 0.0,
    lowConfidence = false,
    boundaryLinkNext = false,
    lexicalForm = null,
    oov = false,
  )

  /** Clip data whose phrase k holds [phrases] k's words, for the spotlight texts of the Shorts tab. */
  fun clipData(id: JobId, vararg phrases: List<String>) = ClipData(
    jobId = id,
    revision = "0123456789abcdef",
    meta = ClipMeta(60.0, "en", 3, "timit61", true, "0.1.0"),
    phrases = phrases.mapIndexed { index, words ->
      Phrase(
        index = index,
        startS = index * 5.0,
        endS = index * 5.0 + 4.0,
        text = words.joinToString(" "),
        words = words.mapIndexed(::word),
        f0 = null,
        finalContour = null,
        emphasisWord = null,
        prominence = null,
        wordClasses = null,
        intonationUnits = emptyList(),
      )
    },
    summary = ClipDataSummary(mapOf(ChangeLabel("flapping") to 1), emptyMap()),
  )
}
