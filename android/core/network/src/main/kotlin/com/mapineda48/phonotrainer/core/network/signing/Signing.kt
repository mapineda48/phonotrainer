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
 * PT1 signing, application half (architecture 5.3, security review M-1). A signed route goes out only when its
 * `host:port` is the verified address: to any other address it fails with [UnverifiedPeerException] before a
 * connection is made, so no request, file or recording reaches a computer that has not proved itself. `hello`, `pair`
 * and `/api/health` never carry the header. A `401 clock_skew` teaches the offset from its `server_time` and the
 * request is sent once more, signed again with the new offset (server-contract 4.3).
 *
 * HTTP requests are signed by [AttemptSigner], once per network attempt: OkHttp retries a request on a new connection
 * when a reused one fails before the answer, and a resent signature would carry a nonce the computer has already seen,
 * which it refuses as a replay with `401 credential_rejected` (a lost pairing, architecture 6.2 rule 5). OkHttp runs no
 * network interceptor for a WebSocket upgrade, so the job channel's upgrade is signed here; the channel's client never
 * retries by itself and never reuses a pooled connection (`ComputerHttp`, `Profile.CHANNEL`).
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

    val first = chain.proceed(signedIfUpgrade(request))
    val serverTime = clockSkewOf(first) ?: return first
    first.close()
    clockOffset.update(serverTime)
    return chain.proceed(signedIfUpgrade(request))
  }

  /** A WebSocket upgrade, which no network interceptor sees, signed here; any other request left to [AttemptSigner]. */
  private fun signedIfUpgrade(request: Request): Request =
    if (request.header(UPGRADE).equals(WEBSOCKET, ignoreCase = true)) signed(signer, request) else request

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
    private const val UPGRADE = "Upgrade"
    private const val WEBSOCKET = "websocket"
    private const val UNAUTHORIZED = 401
    private const val CLOCK_SKEW = "clock_skew"
    private const val PEEK_BYTES = 64L * 1024L

    /** The request target exactly as sent, still percent-encoded: `encodedPath` plus `?encodedQuery` (4.3). */
    fun target(url: HttpUrl): String = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")

    /** [request] with a fresh `Authorization: PT1 …` (a new `ts` and nonce every time). */
    fun signed(signer: RequestSigner, request: Request): Request =
      request.newBuilder().header(AUTHORIZATION, signer.authorization(request.method, target(request.url))).build()
  }
}

/**
 * PT1 signing, network half: runs once per network attempt for every signed route, OkHttp's transparent retries
 * included. Each attempt checks again that the address actually connected is the verified one (a change of network
 * resets verification, architecture 5.3, and a retry opens its socket on the network current then) and is signed with a
 * fresh `ts` and nonce, so no attempt resends a signature the computer has seen. A refusal here happens before a byte
 * of the request is written, and OkHttp does not retry it. Ordered before the debug log, which shows the header
 * redacted.
 */
internal class AttemptSigner(private val signer: RequestSigner, private val verified: VerifiedPeers) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val url = request.url
    if (!Routes.isSigned(url)) return chain.proceed(request)
    val target = HostPort(url.host, url.port)
    val connected = chain.connection()?.route()?.socketAddress
    val sameAddress = connected?.port == target.port && connected.address?.hostAddress == target.host
    if (!sameAddress || !verified.isVerified(target)) throw UnverifiedPeerException()
    return chain.proceed(SigningInterceptor.signed(signer, request))
  }
}
