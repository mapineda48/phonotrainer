package com.mapineda48.phonotrainer.core.testing.computer

import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import okhttp3.Interceptor
import okhttp3.Response
import kotlin.random.Random

/** What a paired phone holds after pairing (server-contract 6.2): its device id and token, and the computer's id. */
class PhoneCredentials(val deviceId: DeviceId, val token: String, val serverId: ServerId) {
  /** `K = sha256(token)`, the device's MAC key (server-contract 3.2). */
  val key: ByteArray get() = PhoneCrypto.keyFromToken(token)

  override fun toString(): String = "PhoneCredentials(deviceId=${deviceId.value}, serverId=${serverId.value})"
}

/**
 * The reference signer: an OkHttp interceptor that adds a fresh `Authorization: PT1 …` header to every request, the
 * Kotlin twin of `PT1Auth` in WP-01's `tests/phone_fixtures.py`. The signed target is the request target exactly as
 * sent (`encodedPath` plus `?encodedQuery`, server-contract 4.3); `ts` comes from [timeS], usually the fake
 * computer's clock (or the phone's clock plus a learned offset).
 *
 * Tests use it to talk to [FakeComputer] with a raw OkHttp client; the production `SigningInterceptor` (WP-05b) is
 * checked against the same reference vectors.
 */
class ReferenceSigner(
  private val credentials: () -> PhoneCredentials?,
  private val timeS: () -> Long,
  private val random: Random = Random.Default,
) : Interceptor {
  constructor(credentials: PhoneCredentials, timeS: () -> Long) : this({ credentials }, timeS)

  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val holder = credentials() ?: return chain.proceed(request)
    val url = request.url
    val target = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
    val header = PhoneCrypto.authorization(
      deviceId = holder.deviceId.value,
      key = holder.key,
      method = request.method,
      target = target,
      ts = timeS(),
      nonce = PhoneCrypto.newNonce(random),
    )
    return chain.proceed(request.newBuilder().header("Authorization", header).build())
  }
}
