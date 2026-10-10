package com.mapineda48.phonotrainer.core.testing.computer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.InputStream
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A raw OkHttp client signed by the reference signer (server-contract 4.3): what the JVM tests of the fake computer
 * use instead of the production client, so that every route and code is checked against the contract itself.
 */
internal class TestPhone(private val computer: FakeComputer, var credentials: PhoneCredentials? = null) {
  val client: OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(ReferenceSigner({ credentials }, { computer.serverTimeS() }))
    .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
    .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
    .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .build()

  fun get(path: String, headers: Map<String, String> = emptyMap()): TestResponse = send("GET", path, null, headers)

  fun delete(path: String): TestResponse = send("DELETE", path, null)

  fun post(path: String, json: String? = null, headers: Map<String, String> = emptyMap()): TestResponse =
    send("POST", path, (json ?: "").toRequestBody(if (json == null) null else JSON), headers)

  fun put(path: String, bytes: ByteArray, headers: Map<String, String> = emptyMap()): TestResponse =
    send("PUT", path, bytes.toRequestBody(OCTETS), headers)

  fun patch(path: String, bytes: ByteArray, offset: Long): TestResponse = send(
    "PATCH", path, bytes.toRequestBody("application/offset+octet-stream".toMediaType()),
    mapOf("Upload-Offset" to offset.toString()),
  )

  fun send(method: String, path: String, body: RequestBody?, headers: Map<String, String> = emptyMap()): TestResponse {
    val request = Request.Builder().url(computer.url(path)).method(method, body)
    headers.forEach { (name, value) -> request.header(name, value) }
    client.newCall(request.build()).execute().use { response ->
      return TestResponse(response.code, response.body.bytes(), response.headers)
    }
  }

  // --- the usual requests -------------------------------------------------------------------------------------

  fun createLink(url: String, key: String = newKey(), audioOnly: Boolean = false): TestResponse =
    post("/api/phone/jobs/link", """{"client_request_id": "$key", "url": "$url", "audio_only": $audioOnly}""")

  fun declare(key: String, fileName: String, bytes: ByteArray, separate: Boolean? = true): TestResponse = post(
    "/api/phone/uploads",
    """{"client_request_id": "$key", "file_name": "$fileName", "bytes": ${bytes.size}, """ +
      """"sha256": "${PhoneCrypto.sha256Hex(bytes)}", "separate_dialogue": $separate}""",
  )

  /** Declares and sends a file; returns the PUT's answer. */
  fun upload(bytes: ByteArray, fileName: String = "VID_0001.mp4", key: String = newKey()): TestResponse {
    val declared = declare(key, fileName, bytes)
    check(declared.code in setOf(200, 201)) { "declaration refused: ${declared.text}" }
    return put("/api/phone/uploads/$key", bytes)
  }

  fun rerun(id: String, key: String = newKey(), audioOnly: Boolean? = null): TestResponse =
    post("/api/phone/jobs/$id/rerun", """{"client_request_id": "$key", "audio_only": $audioOnly}""")

  companion object {
    const val TIMEOUT_S = 5L
    val JSON = "application/json; charset=utf-8".toMediaType()
    val OCTETS = "application/octet-stream".toMediaType()

    fun newKey(): String = UUID.randomUUID().toString()

    /** A body OkHttp sends chunked (no `Content-Length`). */
    fun chunked(text: String): RequestBody = object : RequestBody() {
      override fun contentType() = JSON

      override fun contentLength() = -1L

      override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8(text)
      }
    }

    /** Sends [head] as is on a plain socket (paths an HTTP client would normalize) and returns the status code. */
    fun rawStatus(computer: FakeComputer, head: String): Int = Socket(computer.address.host, computer.address.port).use {
      it.soTimeout = (TIMEOUT_S * 1000).toInt()
      it.getOutputStream().write(head.toByteArray(Charsets.ISO_8859_1))
      it.getOutputStream().flush()
      statusOf(it.getInputStream())
    }

    private fun statusOf(input: InputStream): Int {
      val line = StringBuilder()
      while (true) {
        val c = input.read()
        if (c < 0 || c == '\n'.code) break
        line.append(c.toChar())
      }
      return line.split(' ')[1].toInt()
    }
  }
}

internal class TestResponse(val code: Int, val bytes: ByteArray, val headers: Headers) {
  val text: String get() = bytes.toString(Charsets.UTF_8)

  val json: JsonObject get() = Json.parseToJsonElement(text).jsonObject

  /** The `code` of the error envelope, or null. */
  val errorCode: String? get() = (json["code"] as? JsonPrimitive)?.content

  val job: JsonObject get() = json["job"]!!.jsonObject

  val jobId: String get() = job.str("id")!!

  override fun toString(): String = "$code $text"
}

internal fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.obj(name: String): JsonObject = this[name]!!.jsonObject

internal fun JsonObject.arr(name: String): JsonArray = this[name] as JsonArray

internal fun JsonElement.prim(): JsonPrimitive = jsonPrimitive
