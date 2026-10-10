package com.mapineda48.phonotrainer.core.analysis

import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.ClipDataException
import com.mapineda48.phonotrainer.core.domain.model.JobId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Tolerant decoder of a clip's analysis (architecture 9.1, NFR-08): `analysis.json`, or the `analysis_compact`
 * rendition (the same JSON, gzip). Every optional key may be missing (older analyses, the espeak engine, rules 1 to
 * 3), unknown keys are ignored, and a value of an unexpected type reads as absent; only the skeleton the timeline
 * needs is required (a top-level object, `segments` as an array of objects, and numeric `start`/`end` on every phrase
 * and word). Phrases and words are never dropped or reordered, so their indices stay those of the file.
 *
 * Stateless and thread-safe. Blocking and CPU-bound: the caller runs it on `Dispatchers.Default`; the largest real
 * analysis (4.8 MB) must parse well under NFR-02's 1.5 s.
 *
 * How each key maps, and what absent, unknown and malformed values become, is documented on the `:core:domain`
 * types it fills ([ClipData] and its parts) and in WP-12's shape notes; the rules live in [AnalysisMapper].
 */
object AnalysisParser {
  /** Decoded-size cap: 13 times the largest real analysis. A bigger stream (a gzip bomb) is corrupt. */
  const val MAX_DECODED_BYTES: Long = 64L * 1024 * 1024

  /**
   * The JSON reader: unknown keys are irrelevant to a tree, and Python's `json.dumps` may write `NaN` and `Infinity`,
   * which must parse (they then read as absent, or as corrupt where a time is required).
   */
  private val json = Json {
    allowSpecialFloatingPointValues = true
    isLenient = false
  }

  /**
   * Decodes [input] into the [ClipData] of [jobId].
   *
   * @param jobId the clip's server job id (not stored in `analysis.json`).
   * @param revision the clip-data artifact's revision (server-contract 6.4.2), copied to [ClipData.revision].
   * @param input the artifact's bytes, read to the end and never closed: the caller opens and closes it, and maps a
   *   missing file to `AppError.Content.Missing` itself.
   * @param gzip true for the `analysis_compact` rendition (a gzip member, never `Content-Encoding`).
   * @param maxDecodedBytes the decoded-size cap; tests lower it.
   * @throws ClipDataException with `AppError.Content.Corrupt` for anything that does not decode: a bad gzip stream,
   *   invalid or truncated JSON, a missing or mistyped required key, more than [maxDecodedBytes], or an
   *   `IOException` while reading [input]. It throws nothing else (but `Error`s).
   */
  @Throws(ClipDataException::class)
  fun parse(
    jobId: JobId,
    revision: String,
    input: InputStream,
    gzip: Boolean,
    maxDecodedBytes: Long = MAX_DECODED_BYTES,
  ): ClipData {
    val text = read(input, gzip, maxDecodedBytes)
    val root: JsonElement = try {
      json.parseToJsonElement(text)
    } catch (e: Exception) {
      // SerializationException for invalid or truncated JSON; anything else the reader may raise is corrupt too.
      throw corrupt("not valid JSON", e)
    }
    return try {
      AnalysisMapper(jobId, revision).clip(root)
    } catch (e: ClipDataException) {
      throw e
    } catch (e: RuntimeException) {
      // A mapping rule that failed in a way no test foresaw is still a corrupt file, never a crash.
      throw corrupt("unreadable analysis", e)
    }
  }

  private fun read(input: InputStream, gzip: Boolean, maxDecodedBytes: Long): String {
    require(maxDecodedBytes > 0) { "maxDecodedBytes must be positive" }
    val bytes = try {
      if (gzip) {
        GZIPInputStream(NonClosing(input)).use { readCapped(it, maxDecodedBytes) }
      } else {
        readCapped(input, maxDecodedBytes)
      }
    } catch (e: ClipDataException) {
      throw e
    } catch (e: Exception) {
      // IOException (a read error, a bad gzip header or body, EOFException for a truncated member) and anything the
      // inflater throws for damaged data.
      throw corrupt(if (gzip) "not a valid gzip stream" else "read failed", e)
    }
    return bytes.toString(Charsets.UTF_8)
  }

  private fun readCapped(input: InputStream, max: Long): ByteArray {
    val out = ByteArrayOutputStream(INITIAL_BUFFER)
    val buffer = ByteArray(READ_CHUNK)
    var total = 0L
    while (true) {
      val n = input.read(buffer)
      if (n < 0) break
      total += n
      if (total > max) throw corrupt("decoded size over $max bytes")
      out.write(buffer, 0, n)
    }
    return out.toByteArray()
  }

  internal fun corrupt(message: String, cause: Throwable? = null): ClipDataException =
    ClipDataException(AppError.Content.Corrupt, message, cause)

  /** Lets the gzip stream (and its native inflater) be closed without closing the caller's stream. */
  private class NonClosing(input: InputStream) : FilterInputStream(input) {
    override fun close() = Unit
  }

  private const val READ_CHUNK = 64 * 1024
  private const val INITIAL_BUFFER = 256 * 1024
}
