package com.mapineda48.phonotrainer.core.analysis

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.ClipDataException
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Phrase
import org.junit.Assert.assertThrows
import java.io.ByteArrayInputStream

/** Shared helpers of the `:core:analysis` tests. */
object ClipAssertions {
  val JOB = JobId("20261004-133519-a77c")
  const val REVISION = "0123456789abcdef"

  fun parse(bytes: ByteArray, gzip: Boolean = false, max: Long = AnalysisParser.MAX_DECODED_BYTES): ClipData =
    AnalysisParser.parse(JOB, REVISION, ByteArrayInputStream(bytes), gzip, max)

  fun parse(text: String): ClipData = parse(text.toByteArray(Charsets.UTF_8))

  /** Parsing [bytes] fails as corrupt; returns the exception for its message. */
  fun assertCorrupt(bytes: ByteArray, gzip: Boolean = false, max: Long = AnalysisParser.MAX_DECODED_BYTES):
    ClipDataException {
    val error = assertThrows(ClipDataException::class.java) { parse(bytes, gzip, max) }
    assertThat(error.error).isEqualTo(AppError.Content.Corrupt)
    return error
  }

  /** Field-by-field equality of two clips ([ClipData] and [Phrase] are not data classes: they hold arrays). */
  fun assertSameClip(actual: ClipData, expected: ClipData) {
    assertThat(actual.jobId).isEqualTo(expected.jobId)
    assertThat(actual.revision).isEqualTo(expected.revision)
    assertThat(actual.meta).isEqualTo(expected.meta)
    assertThat(actual.summary).isEqualTo(expected.summary)
    assertThat(actual.phrases).hasSize(expected.phrases.size)
    actual.phrases.zip(expected.phrases).forEach { (a, e) -> assertSamePhrase(a, e) }
  }

  fun assertSamePhrase(actual: Phrase, expected: Phrase) {
    val where = "phrase ${expected.index}"
    assertWithMessage(where).that(actual.index).isEqualTo(expected.index)
    assertWithMessage(where).that(actual.startS).isEqualTo(expected.startS)
    assertWithMessage(where).that(actual.endS).isEqualTo(expected.endS)
    assertWithMessage(where).that(actual.text).isEqualTo(expected.text)
    assertWithMessage(where).that(actual.words).isEqualTo(expected.words)
    assertWithMessage(where).that(actual.f0?.timesS?.toList()).isEqualTo(expected.f0?.timesS?.toList())
    assertWithMessage(where).that(actual.f0?.hz?.toList()).isEqualTo(expected.f0?.hz?.toList())
    assertWithMessage(where).that(actual.finalContour).isEqualTo(expected.finalContour)
    assertWithMessage(where).that(actual.emphasisWord).isEqualTo(expected.emphasisWord)
    assertWithMessage(where).that(actual.prominence?.toList()).isEqualTo(expected.prominence?.toList())
    assertWithMessage(where).that(actual.wordClasses).isEqualTo(expected.wordClasses)
    assertWithMessage(where).that(actual.intonationUnits).isEqualTo(expected.intonationUnits)
    assertWithMessage(where).that(actual.f0Stats).isEqualTo(expected.f0Stats)
    assertWithMessage(where).that(actual.rhythm).isEqualTo(expected.rhythm)
  }
}
