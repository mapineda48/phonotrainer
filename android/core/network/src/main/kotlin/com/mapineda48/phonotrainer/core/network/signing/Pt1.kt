package com.mapineda48.phonotrainer.core.network.signing

import com.mapineda48.phonotrainer.core.common.hash.Sha256
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The PT1 formulas of server-contract 3.2, 4.3 and 6.2, written from the contract and pinned by WP-01's reference
 * vectors (`tests/phone_fixtures.py`, reproduced in `Pt1VectorsTest`). The device key `K = sha256(token)` comes from
 * `PairingStore.withDeviceKey`, which wipes it after each use (architecture 20.3); nothing here keeps it.
 */
internal object Pt1 {
  /** `hex(HMAC-SHA256(K, "pt-req-v1|" + METHOD + "|" + path_and_query + "|" + ts + "|" + nonce))` (4.3). */
  fun requestMac(key: ByteArray, method: String, target: String, ts: Long, nonce: String): String =
    hmacHex(key, "pt-req-v1|${method.uppercase(Locale.ROOT)}|$target|$ts|$nonce")

  /** The `Authorization` value of a signed request (4.3). */
  fun authorization(deviceId: String, key: ByteArray, method: String, target: String, ts: Long, nonce: String): String =
    "$SCHEME device=$deviceId, ts=$ts, nonce=$nonce, mac=${requestMac(key, method, target, ts, nonce)}"

  /** `hex(HMAC-SHA256(K, "pt-hello-v1|" + server_id + "|" + device_id + "|" + challenge))`, `hello`'s proof (6.2). */
  fun helloProof(key: ByteArray, serverId: String, deviceId: String, challenge: String): String =
    hmacHex(key, "pt-hello-v1|$serverId|$deviceId|$challenge")

  /** 32 lower-case hex characters from 16 random bytes: a request nonce or a `hello` challenge. */
  fun newNonce(): String = Sha256.toHex(ByteArray(NONCE_BYTES).also(random::nextBytes))

  /** Compares two MACs in time independent of where they differ (the server's `hmac.compare_digest`). */
  fun sameMac(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))

  const val SCHEME: String = "PT1"
  private const val NONCE_BYTES = 16
  private const val HMAC = "HmacSHA256"
  private val random = SecureRandom()

  private fun hmacHex(key: ByteArray, message: String): String {
    val mac = Mac.getInstance(HMAC)
    mac.init(SecretKeySpec(key, HMAC))
    return Sha256.toHex(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
  }
}
