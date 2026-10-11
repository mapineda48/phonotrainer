package com.mapineda48.phonotrainer.core.network.client

import com.mapineda48.phonotrainer.core.datastore.pairing.DeviceKeyResult
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerHealth
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerIdentity
import com.mapineda48.phonotrainer.core.domain.model.remote.PairGrant
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteArtifactStatus
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteJob
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteLibrary
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.ClockOffset
import com.mapineda48.phonotrainer.core.network.api.ComputerClient
import com.mapineda48.phonotrainer.core.network.api.Created
import com.mapineda48.phonotrainer.core.network.api.LinkCreate
import com.mapineda48.phonotrainer.core.network.api.PairRequest
import com.mapineda48.phonotrainer.core.network.api.RerunBody
import com.mapineda48.phonotrainer.core.network.api.UploadChunk
import com.mapineda48.phonotrainer.core.network.api.UploadDeclaration
import com.mapineda48.phonotrainer.core.network.api.UploadState
import com.mapineda48.phonotrainer.core.network.dto.ComputerJson
import com.mapineda48.phonotrainer.core.network.dto.HelloDto
import com.mapineda48.phonotrainer.core.network.dto.JobEnvelopeDto
import com.mapineda48.phonotrainer.core.network.dto.LinkCreateDto
import com.mapineda48.phonotrainer.core.network.dto.PairRequestDto
import com.mapineda48.phonotrainer.core.network.dto.RerunDto
import com.mapineda48.phonotrainer.core.network.dto.UploadDeclarationDto
import com.mapineda48.phonotrainer.core.network.dto.Wire
import com.mapineda48.phonotrainer.core.network.dto.malformed
import com.mapineda48.phonotrainer.core.network.dto.toGrant
import com.mapineda48.phonotrainer.core.network.dto.toHealth
import com.mapineda48.phonotrainer.core.network.dto.toIdentity
import com.mapineda48.phonotrainer.core.network.dto.toJob
import com.mapineda48.phonotrainer.core.network.dto.toLibrary
import com.mapineda48.phonotrainer.core.network.dto.toUploadState
import com.mapineda48.phonotrainer.core.network.http.Failures
import com.mapineda48.phonotrainer.core.network.http.Profile
import com.mapineda48.phonotrainer.core.network.peers.ComputerPeers
import com.mapineda48.phonotrainer.core.network.peers.ComputerVerifiedPeers
import com.mapineda48.phonotrainer.core.network.service.ApiFactory
import com.mapineda48.phonotrainer.core.network.service.ComputerApi
import com.mapineda48.phonotrainer.core.network.signing.Pt1
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response

/**
 * The [ComputerClient] of architecture 5.3 and 5.4: one method per row of server-contract 6.10, over [ApiFactory]'s
 * Retrofit `ComputerApi`. Every answer is validated and mapped to the domain before it leaves the module: `Ok` for a
 * 2xx answer, `Refused` with the error envelope for the rest, `Failed` when no answer came (with the call's timeout),
 * the guard refused, or the answer was malformed (security review M-3). Path parameters that do not have their
 * documented shape are refused as malformed before anything is sent.
 *
 * `hello` with a challenge is where an address proves it is the paired computer (5.3): a valid proof for the paired
 * server id marks the address verified (and teaches the clock offset); a missing or wrong proof, another server id or
 * a revoked device leaves it unverified, so no signed request is ever sent there.
 */
@Singleton
internal class HttpComputerClient @Inject constructor(
  private val apis: ApiFactory,
  private val peers: ComputerPeers,
  private val verified: ComputerVerifiedPeers,
  private val clockOffset: ClockOffset,
  private val store: PairingStore,
) : ComputerClient {
  /** The last `health` said an analysis is running: probes then wait up to 10 s (architecture 5.1, spec 7.5). */
  @Volatile private var analyzing = false

  // --- identity, pairing, health (server-contract 6.2) --------------------------------------------------------------

  override suspend fun hello(at: HostPort, challengeFor: DeviceId?): ApiResult<ComputerIdentity> {
    val challenge = challengeFor?.let { Pt1.newNonce() }
    return call(at, probe(), { hello(challengeFor?.value, challenge) }) { response ->
      val answer = response.body() ?: malformed("hello")
      val proofValid = if (challengeFor != null && challenge != null) prove(at, answer, challengeFor, challenge) else null
      answer.toIdentity(proofValid)
    }
  }

  override suspend fun legacyHealth(at: HostPort): ApiResult<Int> =
    call(at, probe(), { legacyHealth() }) { response -> response.body()?.apiVersion ?: malformed("api_version") }

  override suspend fun pair(at: HostPort, request: PairRequest): ApiResult<PairGrant> {
    val body = PairRequestDto(
      code = request.code,
      deviceName = request.deviceName,
      appVersion = request.appVersion,
      appVersionName = request.appVersionName,
      previousDeviceId = request.previousDeviceId?.value,
      previousTokenProof = request.previousTokenProof,
    )
    val bytes = ComputerJson.encodeToString(PairRequestDto.serializer(), body).toByteArray(Charsets.UTF_8)
    return call(at, Profile.API, { pair(OneShotJsonBody(bytes, JSON)) }) { response ->
      (response.body() ?: malformed("pair")).toGrant().also { clockOffset.update(it.serverTime) }
    }
  }

  override suspend fun health(): ApiResult<ComputerHealth> =
    current(probe(), { health() }) { response ->
      (response.body() ?: malformed("health")).toHealth().also { analyzing = it.analyzing > 0 }
    }

  override suspend fun unpairSelf(): ApiResult<Unit> = current(Profile.API, { unpairSelf() }) { }

  // --- library, jobs, reference (6.3, 6.8) --------------------------------------------------------------------------

  override suspend fun library(): ApiResult<RemoteLibrary> =
    current(Profile.API, { library() }) { response -> (response.body() ?: malformed("library")).toLibrary() }

  override suspend fun job(id: JobId): ApiResult<RemoteJob> = withJobId(id) { path ->
    current(Profile.API, { job(path) }) { response -> (response.body() ?: malformed("job")).toJob(lean = false) }
  }

  override suspend fun requestLookup(key: RequestId): ApiResult<RemoteJob> = withKey(key) { path ->
    current(Profile.API, { requestLookup(path) }) { response -> envelope(response).toJob() }
  }

  override suspend fun reference(): ApiResult<String> =
    current(Profile.API, { reference() }) { response -> response.body()?.string() ?: malformed("reference") }

  // --- links and uploads (6.5, 6.6) ---------------------------------------------------------------------------------

  override suspend fun createLink(body: LinkCreate): ApiResult<Created<RemoteJob>> = withKey(body.clientRequestId) { key ->
    val dto = LinkCreateDto(key, body.url, body.audioOnly, body.separateDialogue)
    current(Profile.API, { createLink(dto) }) { response -> created(response) }
  }

  override suspend fun openUpload(body: UploadDeclaration): ApiResult<UploadState> = withKey(body.clientRequestId) { key ->
    val dto = UploadDeclarationDto(key, body.fileName, body.bytes, body.sha256.lowercase(Locale.ROOT), body.separateDialogue)
    current(Profile.API, { openUpload(dto) }) { response -> (response.body() ?: malformed("upload")).toUploadState() }
  }

  override suspend fun putUpload(key: RequestId, file: File, onProgress: (Long) -> Unit): ApiResult<Created<RemoteJob>> =
    withKey(key) { path ->
      val body = FileRangeBody(file, offset = 0, length = file.length(), FileRangeBody.OCTET_STREAM, onProgress)
      current(Profile.UPLOAD, { putUpload(path, body) }) { response -> created(response) }
    }

  override suspend fun uploadStatus(key: RequestId): ApiResult<UploadState> = withKey(key) { path ->
    current(Profile.API, { uploadStatus(path) }) { response -> (response.body() ?: malformed("upload")).toUploadState() }
  }

  override suspend fun patchUpload(key: RequestId, file: File, offset: Long, length: Long): ApiResult<UploadChunk> =
    withKey(key) { path ->
      val body = FileRangeBody(file, offset, length, FileRangeBody.OFFSET_OCTET_STREAM)
      current(Profile.UPLOAD, { patchUpload(path, offset, body) }) { response ->
        if (response.code() == NO_CONTENT) {
          UploadChunk.Accepted(response.headers()["Upload-Offset"]?.toLongOrNull()?.takeIf { it >= 0 } ?: malformed("Upload-Offset"))
        } else {
          val text = response.body()?.string() ?: malformed("upload")
          val job = ComputerJson.decodeFromString(JobEnvelopeDto.serializer(), text).toJob()
          UploadChunk.Finished(Created(job, created = response.code() == CREATED))
        }
      }
    }

  override suspend fun deleteUpload(key: RequestId): ApiResult<Unit> = withKey(key) { path ->
    current(Profile.API, { deleteUpload(path) }) { }
  }

  // --- cancel, delete, re-run (6.7) ---------------------------------------------------------------------------------

  override suspend fun cancel(id: JobId): ApiResult<RemoteJob> = withJobId(id) { path ->
    current(Profile.API, { cancel(path) }) { response -> envelope(response).toJob() }
  }

  override suspend fun deleteJob(id: JobId): ApiResult<Unit> = withJobId(id) { path ->
    current(Profile.API, { deleteJob(path) }) { }
  }

  override suspend fun rerun(id: JobId, body: RerunBody): ApiResult<Created<RemoteJob>> = withJobId(id) { path ->
    withKey(body.clientRequestId) { key ->
      current(Profile.API, { rerun(path, RerunDto(key, body.audioOnly)) }) { response -> created(response) }
    }
  }

  // --- v1-complete: phone video and record yourself (6.4.4, 6.9) --------------------------------------------------

  override suspend fun requestPhoneVideo(id: JobId): ApiResult<RemoteArtifactStatus> = withJobId(id) { path ->
    current(Profile.API, { requestPhoneVideo(path) }) { response ->
      val status = response.body()?.status?.uppercase(Locale.ROOT) ?: malformed("status")
      RemoteArtifactStatus.entries.firstOrNull { it.name == status && it != RemoteArtifactStatus.UNKNOWN }
        ?: RemoteArtifactStatus.UNKNOWN
    }
  }

  override suspend fun contour(id: JobId, startS: Double, endS: Double): ApiResult<String> = withJobId(id) { path ->
    current(Profile.API, { contour(path, startS.toString(), endS.toString()) }) { response ->
      response.body()?.string() ?: malformed("contour")
    }
  }

  override suspend fun compare(id: JobId, take: File, contentType: String, startS: Double, endS: Double): ApiResult<String> =
    withJobId(id) { path ->
      val type = contentType.toMediaTypeOrNull() ?: return@withJobId ApiResult.Failed(AppError.Computer.Malformed)
      val file = MultipartBody.Part.createFormData("file", take.name, take.asRequestBody(type))
      current(Profile.UPLOAD, { compare(path, file, startS.toString().toRequestBody(), endS.toString().toRequestBody()) }) {
          response ->
        response.body()?.string() ?: malformed("compare")
      }
    }

  // --- internals ----------------------------------------------------------------------------------------------------

  private fun probe(): Profile = if (analyzing) Profile.PROBE_SLOW else Profile.PROBE

  /**
   * Checks `hello`'s proof for the paired computer (architecture 5.3) and records the outcome: verified (and the clock
   * offset learned) only for a valid proof of the paired server id, from a device that is not revoked.
   */
  private suspend fun prove(at: HostPort, answer: HelloDto, device: DeviceId, challenge: String): Boolean {
    val pairing = store.current()
    val proof = answer.proof
    val valid = pairing != null && pairing.deviceId == device && proof != null &&
      answer.serverId == pairing.serverId.value &&
      when (val result = store.withDeviceKey { key -> Pt1.helloProof(key, pairing.serverId.value, device.value, challenge) }) {
        is DeviceKeyResult.Ok -> Pt1.sameMac(result.value, proof)
        else -> false
      }
    if (valid && pairing != null) {
      answer.serverTime?.let(clockOffset::update)
      if (answer.revoked == true) verified.forget(at) else verified.markVerified(at, pairing.serverId)
    } else {
      verified.forget(at)
    }
    return valid
  }

  private suspend fun <D, T> current(
    profile: Profile,
    request: suspend ComputerApi.() -> Response<D>,
    map: suspend (Response<D>) -> T,
  ): ApiResult<T> = call(peers.current.value, profile, request, map)

  /** One call at [at]: `Ok`, `Refused` with the error envelope, or `Failed` (architecture 19). */
  private suspend fun <D, T> call(
    at: HostPort?,
    profile: Profile,
    request: suspend ComputerApi.() -> Response<D>,
    map: suspend (Response<D>) -> T,
  ): ApiResult<T> {
    val api = at?.let { apis.api(it, profile) } ?: return ApiResult.Failed(AppError.Computer.Guarded)
    return try {
      val response = api.request()
      if (response.isSuccessful) {
        ApiResult.Ok(map(response), response.code())
      } else {
        Refusals.of(response.code(), response.errorBody()?.string(), response.headers()["Retry-After"])
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      ApiResult.Failed(Failures.toError(e, apis.noAnswerAfterS(profile)))
    }
  }

  private inline fun <T> withJobId(id: JobId, block: (String) -> ApiResult<T>): ApiResult<T> =
    if (Wire.JOB_ID.matches(id.value)) block(id.value) else ApiResult.Failed(AppError.Computer.Malformed)

  private inline fun <T> withKey(key: RequestId, block: (String) -> ApiResult<T>): ApiResult<T> =
    if (Wire.UUID.matches(key.value)) block(key.value.lowercase(Locale.ROOT)) else ApiResult.Failed(AppError.Computer.Malformed)

  private fun envelope(response: Response<JobEnvelopeDto>): JobEnvelopeDto = response.body() ?: malformed("job")

  /** `{"job": …}` of a creation route: 201 created now, 200 found by its key (server-contract 6.1). */
  private fun created(response: Response<JobEnvelopeDto>): Created<RemoteJob> =
    Created(envelope(response).toJob(), created = response.code() == CREATED)

  private companion object {
    const val CREATED = 201
    const val NO_CONTENT = 204
    val JSON = ApiFactory.JSON.toMediaType()
  }
}
