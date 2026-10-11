package com.mapineda48.phonotrainer.core.datastore.credential

import android.security.keystore.KeyProperties
import com.google.common.truth.Truth.assertThat
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Architecture 20.3: AES-256-GCM in the keystore, no user authentication, and every unreadable case reported as such. */
@RunWith(RobolectricTestRunner::class)
class KeystoreCredentialCipherTest {
  private val cipher = KeystoreCredentialCipher()
  private val token = "test-token-one".toByteArray()

  @Before
  fun setUp() = FakeAndroidKeyStore.install()

  @After
  fun tearDown() = FakeAndroidKeyStore.uninstall()

  @Test
  fun aSealedTokenOpensToTheSameBytes() {
    val sealed = cipher.seal(token)

    assertThat(sealed.ciphertext).isNotEqualTo(token)
    assertThat(String(sealed.ciphertext, Charsets.ISO_8859_1)).doesNotContain("test-token-one")
    assertThat(sealed.iv).hasLength(12)
    assertThat(cipher.open(sealed)).isEqualTo(token)
  }

  @Test
  fun theKeyIsCreatedOnceWithTheArchitecturesSpec() {
    cipher.seal(token)
    cipher.seal(token)

    assertThat(FakeAndroidKeyStore.specs).hasSize(1)
    val spec = FakeAndroidKeyStore.specs.single()
    assertThat(spec.keystoreAlias).isEqualTo("device_credential_v1")
    assertThat(spec.keySize).isEqualTo(256)
    assertThat(spec.blockModes.toList()).containsExactly(KeyProperties.BLOCK_MODE_GCM)
    assertThat(spec.encryptionPaddings.toList()).containsExactly(KeyProperties.ENCRYPTION_PADDING_NONE)
    assertThat(spec.purposes).isEqualTo(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
    assertThat(spec.isUserAuthenticationRequired).isFalse()
    assertThat(spec.isRandomizedEncryptionRequired).isTrue()
    assertThat(FakeAndroidKeyStore.keys.keys).containsExactly(KeystoreCredentialCipher.ALIAS)
  }

  @Test
  fun everySealUsesAFreshIv() {
    val one = cipher.seal(token)
    val two = cipher.seal(token)

    assertThat(one.iv).isNotEqualTo(two.iv)
    assertThat(one.ciphertext).isNotEqualTo(two.ciphertext)
    assertThat(cipher.open(one)).isEqualTo(cipher.open(two))
  }

  @Test
  fun withoutTheKeyNothingOpens() {
    val sealed = cipher.seal(token)
    cipher.forget()

    assertThat(FakeAndroidKeyStore.keys).isEmpty()
    assertThrows(CredentialUnreadableException::class.java) { cipher.open(sealed) }
    // Opening never creates a key.
    assertThat(FakeAndroidKeyStore.keys).isEmpty()
  }

  @Test
  fun aKeyReplacedBehindTheStoresBackIsUnreadable() {
    val sealed = cipher.seal(token)
    // Restored to another phone, or invalidated by the system: same alias, another key.
    FakeAndroidKeyStore.keys[KeystoreCredentialCipher.ALIAS] = SecretKeySpec(ByteArray(32) { 7 }, "AES")

    assertThrows(CredentialUnreadableException::class.java) { cipher.open(sealed) }
  }

  @Test
  fun changedBytesNeverOpen() {
    val sealed = cipher.seal(token)
    val flipped = sealed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
    val otherIv = sealed.iv.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

    listOf(
      SealedCredential(flipped, sealed.iv),
      SealedCredential(sealed.ciphertext, otherIv),
      SealedCredential(sealed.ciphertext.copyOf(sealed.ciphertext.size - 1), sealed.iv),
      SealedCredential(sealed.ciphertext, ByteArray(0)),
    ).forEach { damaged ->
      assertThrows(CredentialUnreadableException::class.java) { cipher.open(damaged) }
    }
    assertThat(cipher.open(sealed)).isEqualTo(token)
  }
}
