package com.mapineda48.phonotrainer.core.testing.computer

import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * The cryptography of server-contract 3.2, 4.3 and 6.2, written from the contract alone: the per-device key
 * `K = sha256(token)`, request signatures, identity proofs and re-activation proofs. The fake computer checks with it
 * and the tests sign with it; the reference vectors of WP-01's `tests/phone_fixtures.py` pin every formula
 * (`ReferenceVectorsTest`).
 */
object PhoneCrypto {
  /** The device's MAC key: the 32 raw bytes of `sha256(token)` (server-contract 3.2). */
  fun keyFromToken(token: String): ByteArray = sha256(token.toByteArray(Charsets.UTF_8))

  /** `hex(HMAC-SHA256(K, "pt-req-v1|" + METHOD + "|" + path_and_query + "|" + ts + "|" + nonce))` (4.3). */
  fun requestMac(key: ByteArray, method: String, target: String, ts: Long, nonce: String): String =
    hmacHex(key, "pt-req-v1|${method.uppercase(Locale.ROOT)}|$target|$ts|$nonce")

  /** `hex(HMAC-SHA256(K, "pt-hello-v1|" + server_id + "|" + device_id + "|" + challenge))` (6.2). */
  fun helloProof(key: ByteArray, serverId: String, deviceId: String, challenge: String): String =
    hmacHex(key, "pt-hello-v1|$serverId|$deviceId|$challenge")

  /** `hex(HMAC-SHA256(K_old, "pt-reactivate-v1|" + code))`, the `previous_token_proof` of "Connect again" (6.2). */
  fun reactivationProof(oldKey: ByteArray, code: String): String = hmacHex(oldKey, "pt-reactivate-v1|$code")

  /** The whole `Authorization` header value of a signed request (4.3). */
  fun authorization(deviceId: String, key: ByteArray, method: String, target: String, ts: Long, nonce: String): String =
    "PT1 device=$deviceId, ts=$ts, nonce=$nonce, mac=${requestMac(key, method, target, ts, nonce)}"

  /** A fresh nonce: 32 lower-case hex characters (16 random bytes). */
  fun newNonce(random: Random = Random.Default): String = hex(random.nextBytes(NONCE_BYTES))

  fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

  fun sha256Hex(bytes: ByteArray): String = hex(sha256(bytes))

  fun hmacHex(key: ByteArray, message: String): String {
    val mac = Mac.getInstance(HMAC)
    mac.init(SecretKeySpec(key, HMAC))
    return hex(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
  }

  /** Compares two strings in time independent of where they differ (the server's `hmac.compare_digest`). */
  fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

  fun hex(bytes: ByteArray): String {
    val out = StringBuilder(bytes.size * 2)
    for (byte in bytes) {
      val value = byte.toInt() and 0xff
      out.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0f])
    }
    return out.toString()
  }

  fun unhex(text: String): ByteArray {
    require(text.length % 2 == 0) { "odd hex length" }
    return ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
  }

  private const val HMAC = "HmacSHA256"
  private const val NONCE_BYTES = 16
  private const val HEX_DIGITS = "0123456789abcdef"
}
