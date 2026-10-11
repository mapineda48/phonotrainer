package com.mapineda48.phonotrainer.core.network.signing

import com.mapineda48.phonotrainer.core.datastore.pairing.DeviceKeyResult
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.network.api.ClockOffset
import com.mapineda48.phonotrainer.core.network.api.VerifiedPeers
import com.mapineda48.phonotrainer.core.network.dto.ComputerJson
import com.mapineda48.phonotrainer.core.network.http.CredentialUnreadableException
import com.mapineda48.phonotrainer.core.network.http.NotPairedException
import com.mapineda48.phonotrainer.core.network.http.Routes
import com.mapineda48.phonotrainer.core.network.http.UnverifiedPeerException
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/** Signs one request (server-contract 4.3). */
internal fun interface RequestSigner {
  /** The `Authorization` value for [method] and the request [target]; an [IOException] when it cannot sign. */
  @Throws(IOException::class)
  fun authorization(method: String, target: String): String
}

/**
 * Signs with the paired phone's key: the device id from the pairing store, `ts` = the phone's clock plus the learned
 * offset (architecture 5.3), a fresh nonce, and the key only inside `PairingStore.withDeviceKey`, which wipes it.
 * OkHttp interceptors are blocking, so the store is read with `runBlocking` on the calling OkHttp thread.
 */
internal class PairingRequestSigner(
  private val store: PairingStore,
  private val clock: Clock,
  private val offset: ClockOffset,
  private val nonces: () -> String = Pt1::newNonce,
) : RequestSigner {
  override fun authorization(method: String, target: String): String = try {
    runBlocking { sign(method, target) }
  } catch (e: IOException) {
    throw e
  } catch (e: InterruptedException) {
    throw java.io.InterruptedIOException("Interrupted while signing.").apply { initCause(e) }
  } catch (e: RuntimeException) {
    throw IOException("The request could not be signed.", e)
  }

  private suspend fun sign(method: String, target: String): String {
    val record = store.current() ?: throw NotPairedException()
    val ts = clock.now().epochSecond + offset.offsetS.value
    val nonce = nonces()
    return when (val result = store.withDeviceKey { key -> Pt1.authorization(record.deviceId.value, key, method, target, ts, nonce) }) {
      is DeviceKeyResult.Ok -> result.value
      DeviceKeyResult.NotPaired -> throw NotPairedException()
      DeviceKeyResult.Unreadable -> throw CredentialUnreadableException()
    }
  }
}

/**
 * Adds `Authorization: PT1 …` (architecture 5.3, security review M-1) to every signed route, and only when the
 * request's `host:port` is the verified address: a signed route to any other address is never sent
 * ([UnverifiedPeerException]), so no request, file or recording reaches a computer that has not proved itself.
 * `hello`, `pair` and `/api/health` never carry the header. A `401 clock_skew` teaches the offset from its
 * `server_time` and the request is signed again and sent once more (server-contract 4.3).
 */
internal class SigningInterceptor(
  private val signer: RequestSigner,
  private val verified: VerifiedPeers,
  private val clockOffset: ClockOffset,
) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request().newBuilder().removeHeader(AUTHORIZATION).build()
    val url = request.url
    if (!Routes.isSigned(url)) return chain.proceed(request)
    if (!verified.isVerified(HostPort(url.host, url.port))) throw UnverifiedPeerException()

    val first = chain.proceed(signed(request))
    val serverTime = clockSkewOf(first) ?: return first
    first.close()
    clockOffset.update(serverTime)
    return chain.proceed(signed(request))
  }

  private fun signed(request: Request): Request =
    request.newBuilder().header(AUTHORIZATION, signer.authorization(request.method, target(request.url))).build()

  /** The `server_time` of a `401 clock_skew`, or null for any other answer. */
  private fun clockSkewOf(response: Response): Long? {
    if (response.code != UNAUTHORIZED) return null
    val body = try {
      response.peekBody(PEEK_BYTES).string()
    } catch (_: IOException) {
      return null
    }
    val envelope = runCatching { ComputerJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    if ((envelope["code"] as? JsonPrimitive)?.content != CLOCK_SKEW) return null
    return (envelope["server_time"] as? JsonPrimitive)?.longOrNull
  }

  companion object {
    const val AUTHORIZATION = "Authorization"
    private const val UNAUTHORIZED = 401
    private const val CLOCK_SKEW = "clock_skew"
    private const val PEEK_BYTES = 64L * 1024L

    /** The request target exactly as sent, still percent-encoded: `encodedPath` plus `?encodedQuery` (4.3). */
    fun target(url: HttpUrl): String = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
  }
}
