package com.mapineda48.phonotrainer.core.network.service

import com.mapineda48.phonotrainer.core.network.dto.HealthDto
import com.mapineda48.phonotrainer.core.network.dto.HelloDto
import com.mapineda48.phonotrainer.core.network.dto.JobDto
import com.mapineda48.phonotrainer.core.network.dto.JobEnvelopeDto
import com.mapineda48.phonotrainer.core.network.dto.LegacyHealthDto
import com.mapineda48.phonotrainer.core.network.dto.LibraryDto
import com.mapineda48.phonotrainer.core.network.dto.LinkCreateDto
import com.mapineda48.phonotrainer.core.network.dto.PairGrantDto
import com.mapineda48.phonotrainer.core.network.dto.RenditionStatusDto
import com.mapineda48.phonotrainer.core.network.dto.RerunDto
import com.mapineda48.phonotrainer.core.network.dto.UploadDeclarationDto
import com.mapineda48.phonotrainer.core.network.dto.UploadStateDto
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The phone API of server-contract 6, one method per row of its endpoint summary (6.10; architecture 5.4). One
 * instance exists per address and timeout profile (`ApiFactory`); every answer comes back as a `Response`, so the
 * client sees the status code, the headers and the error body of a refusal. Path parameters are validated by the
 * client before a call is made. The CR-01 routes of 6.11 and 6.12 are v1-complete (WP-28).
 */
internal interface ComputerApi {
  @GET("api/phone/hello")
  suspend fun hello(
    @Query("device_id") deviceId: String?,
    @Query("challenge") challenge: String?,
  ): Response<HelloDto>

  /** `GET /api/health` of a server older than API 6, at the candidate address, without a credential (D-15). */
  @GET("api/health")
  suspend fun legacyHealth(): Response<LegacyHealthDto>

  /** The body is a one-shot JSON `PairRequestDto`: a pairing code is never sent twice by a transparent retry. */
  @POST("api/phone/pair")
  suspend fun pair(@Body body: RequestBody): Response<PairGrantDto>

  @GET("api/phone/health")
  suspend fun health(): Response<HealthDto>

  @DELETE("api/phone/devices/self")
  suspend fun unpairSelf(): Response<Unit>

  @GET("api/phone/library")
  suspend fun library(): Response<LibraryDto>

  @GET("api/phone/jobs/{id}")
  suspend fun job(@Path("id") id: String): Response<JobDto>

  @GET("api/phone/requests/{key}")
  suspend fun requestLookup(@Path("key") key: String): Response<JobEnvelopeDto>

  @GET("api/phone/reference")
  suspend fun reference(): Response<ResponseBody>

  @POST("api/phone/jobs/link")
  suspend fun createLink(@Body body: LinkCreateDto): Response<JobEnvelopeDto>

  @POST("api/phone/uploads")
  suspend fun openUpload(@Body body: UploadDeclarationDto): Response<UploadStateDto>

  /** The whole file (core): `Content-Length` equal to the declared bytes, never chunked. */
  @PUT("api/phone/uploads/{key}")
  suspend fun putUpload(@Path("key") key: String, @Body body: RequestBody): Response<JobEnvelopeDto>

  /** Complete. */
  @GET("api/phone/uploads/{key}")
  suspend fun uploadStatus(@Path("key") key: String): Response<UploadStateDto>

  /** Complete: `204` with `Upload-Offset` while bytes remain, else the job (`201`/`200`). */
  @PATCH("api/phone/uploads/{key}")
  suspend fun patchUpload(
    @Path("key") key: String,
    @Header("Upload-Offset") offset: Long,
    @Body body: RequestBody,
  ): Response<ResponseBody>

  @DELETE("api/phone/uploads/{key}")
  suspend fun deleteUpload(@Path("key") key: String): Response<Unit>

  @POST("api/phone/jobs/{id}/cancel")
  suspend fun cancel(@Path("id") id: String): Response<JobEnvelopeDto>

  @DELETE("api/phone/jobs/{id}")
  suspend fun deleteJob(@Path("id") id: String): Response<Unit>

  @POST("api/phone/jobs/{id}/rerun")
  suspend fun rerun(@Path("id") id: String, @Body body: RerunDto): Response<JobEnvelopeDto>

  /** Complete; no body. */
  @POST("api/phone/jobs/{id}/renditions/video_phone")
  suspend fun requestPhoneVideo(@Path("id") id: String): Response<RenditionStatusDto>

  /** Complete. */
  @GET("api/phone/jobs/{id}/contour")
  suspend fun contour(
    @Path("id") id: String,
    @Query("start") start: String,
    @Query("end") end: String,
  ): Response<ResponseBody>

  /** Complete: multipart `file`, `start`, `end`. */
  @Multipart
  @POST("api/phone/jobs/{id}/compare")
  suspend fun compare(
    @Path("id") id: String,
    @Part file: MultipartBody.Part,
    @Part("start") start: RequestBody,
    @Part("end") end: RequestBody,
  ): Response<ResponseBody>
}
