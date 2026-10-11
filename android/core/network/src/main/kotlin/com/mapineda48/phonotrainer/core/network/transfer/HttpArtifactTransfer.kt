package com.mapineda48.phonotrainer.core.network.transfer

import com.mapineda48.phonotrainer.core.common.di.IoDispatcher
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.network.api.ArtifactTransfer
import com.mapineda48.phonotrainer.core.network.api.DownloadOutcome
import com.mapineda48.phonotrainer.core.network.api.DownloadRequest
import com.mapineda48.phonotrainer.core.network.client.Refusals
import com.mapineda48.phonotrainer.core.network.http.ComputerHttp
import com.mapineda48.phonotrainer.core.network.http.Failures
import com.mapineda48.phonotrainer.core.network.http.Profile
import com.mapineda48.phonotrainer.core.network.http.Routes
import com.mapineda48.phonotrainer.core.network.peers.ComputerPeers
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response

/**
 * Resumable artifact downloads (architecture 7.4), signed and confined like every call, on the download profile (30 s
 * per read, no call timeout):
 * - a part already [DownloadRequest.expectedBytes] long is never requested: the caller verifies it (A-M5);
 * - a part shorter than [DownloadRequest.resumeFrom] cannot be resumed: it is emptied ([DownloadOutcome.RestartedFromZero]);
 * - resuming sends `Range: bytes=<resumeFrom>-` and `If-Range`; `206` appends at `resumeFrom` (its `Content-Range` must
 *   start there), a `200` where `206` was expected empties the part without reading it (`RestartedFromZero`), `416`
 *   leaves the part as it is ([DownloadOutcome.RangeNotSatisfiable]);
 * - never more than `expectedBytes` is written: a longer answer is malformed (security review M-3);
 * - the part is synced to disk before each progress report (at most every 512 KiB, and at the end), so the length the
 *   caller records never exceeds what is on disk; a lost connection keeps what arrived.
 */
@Singleton
internal class HttpArtifactTransfer @Inject constructor(
  private val http: ComputerHttp,
  private val peers: ComputerPeers,
  @IoDispatcher private val io: CoroutineDispatcher,
) : ArtifactTransfer {
  override suspend fun download(request: DownloadRequest, onProgress: (Long) -> Unit): DownloadOutcome =
    withContext(io) { transfer(request, onProgress) }

  private suspend fun transfer(request: DownloadRequest, onProgress: (Long) -> Unit): DownloadOutcome {
    val start = request.resumeFrom
    val target = request.target
    if (start < 0 || request.expectedBytes < 0 || (request.expectedBytes > 0 && start > request.expectedBytes)) {
      return DownloadOutcome.Failed(AppError.Computer.Malformed)
    }
    if (start > 0 && target.length() < start) {
      emptyPart(target)
      return DownloadOutcome.RestartedFromZero
    }
    if (request.expectedBytes > 0 && start == request.expectedBytes) {
      return DownloadOutcome.Complete(request.ifRange, start)
    }
    val url = resolve(request.url) ?: return DownloadOutcome.Failed(AppError.Computer.Guarded)
    val builder = Request.Builder().url(url).get()
    if (start > 0) {
      builder.header("Range", "bytes=$start-")
      request.ifRange?.let { builder.header("If-Range", it) }
    }
    val call = http.client(Profile.DOWNLOAD).newCall(builder.build())
    val noAnswerAfterS = http.noAnswerAfterS(Profile.DOWNLOAD)
    val response = try {
      call.await()
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      return DownloadOutcome.Failed(Failures.toError(e, noAnswerAfterS))
    }
    val cancelOnStop = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
    return try {
      response.use { answer(it, request, onProgress, noAnswerAfterS) }
    } finally {
      cancelOnStop.dispose()
    }
  }

  private suspend fun answer(
    response: Response,
    request: DownloadRequest,
    onProgress: (Long) -> Unit,
    noAnswerAfterS: Int,
  ): DownloadOutcome {
    val start = request.resumeFrom
    val from = when (response.code) {
      PARTIAL_CONTENT -> {
        if (start == 0L || contentRangeStart(response) != start) return DownloadOutcome.Failed(AppError.Computer.Malformed)
        start
      }
      OK -> {
        if (start > 0) {
          emptyPart(request.target)
          return DownloadOutcome.RestartedFromZero
        }
        0L
      }
      RANGE_NOT_SATISFIABLE -> return DownloadOutcome.RangeNotSatisfiable
      else -> {
        val body = try {
          response.peekBody(Routes.JSON_CAP).string()
        } catch (_: IOException) {
          null
        }
        return DownloadOutcome.Failed(Refusals.errorOf(Refusals.of(response.code, body, response.header("Retry-After"))))
      }
    }
    val declared = response.body.contentLength()
    if (request.expectedBytes > 0 && declared >= 0 && from + declared > request.expectedBytes) {
      return DownloadOutcome.Failed(AppError.Computer.Malformed)
    }
    return write(response, request, from, onProgress, noAnswerAfterS)
  }

  private suspend fun write(
    response: Response,
    request: DownloadRequest,
    from: Long,
    onProgress: (Long) -> Unit,
    noAnswerAfterS: Int,
  ): DownloadOutcome {
    request.target.parentFile?.mkdirs()
    RandomAccessFile(request.target, "rw").use { part ->
      part.setLength(from)
      part.seek(from)
      var length = from
      var reported = from
      val buffer = ByteArray(CHUNK_BYTES)
      val source = response.body.byteStream()
      try {
        while (true) {
          currentCoroutineContext().ensureActive()
          val read = source.read(buffer)
          if (read < 0) break
          if (request.expectedBytes > 0 && length + read > request.expectedBytes) {
            part.write(buffer, 0, (request.expectedBytes - length).toInt())
            part.fd.sync()
            return DownloadOutcome.Failed(AppError.Computer.Malformed)
          }
          part.write(buffer, 0, read)
          length += read
          if (length - reported >= PROGRESS_BYTES) {
            part.fd.sync()
            reported = length
            onProgress(length)
          }
        }
      } catch (e: IOException) {
        part.fd.sync()
        if (length != reported) onProgress(length)
        currentCoroutineContext().ensureActive()
        return DownloadOutcome.Failed(Failures.toError(e, noAnswerAfterS))
      }
      part.fd.sync()
      if (length != reported || length == from) onProgress(length)
      return DownloadOutcome.Complete(response.header("ETag"), length)
    }
  }

  /** The listing's `url` (relative, server-contract 6.4.2) against the current address, or an absolute `http` URL. */
  private fun resolve(url: String): HttpUrl? {
    url.toHttpUrlOrNull()?.let { return it }
    if (!url.startsWith("/")) return null
    val at = peers.current.value ?: return null
    return "http://${at.host}:${at.port}$url".toHttpUrlOrNull()
  }

  /** The first byte of `Content-Range: bytes <start>-<end>/<total>`, or null. */
  private fun contentRangeStart(response: Response): Long? =
    response.header("Content-Range")?.let { CONTENT_RANGE.find(it)?.groupValues?.get(1)?.toLongOrNull() }

  private fun emptyPart(target: File) {
    if (target.exists()) RandomAccessFile(target, "rw").use { it.setLength(0) }
  }

  private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
      object : Callback {
        override fun onResponse(call: Call, response: Response) {
          continuation.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
          continuation.resumeWithException(e)
        }
      },
    )
  }

  private companion object {
    const val OK = 200
    const val PARTIAL_CONTENT = 206
    const val RANGE_NOT_SATISFIABLE = 416
    const val CHUNK_BYTES = 64 * 1024
    const val PROGRESS_BYTES = 512L * 1024L
    val CONTENT_RANGE = Regex("^bytes (\\d+)-\\d+/(\\d+|\\*)$")
  }
}
