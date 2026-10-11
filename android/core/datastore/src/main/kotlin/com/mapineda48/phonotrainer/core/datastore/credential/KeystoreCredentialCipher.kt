package com.mapineda48.phonotrainer.core.datastore.credential

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CredentialCipher] over an AES-256-GCM key in `AndroidKeyStore` (architecture 20.3): alias [ALIAS], not exportable,
 * no user authentication, randomized encryption (the keystore chooses each IV). The key never leaves the keystore, so
 * a backup or a copy of the app's files cannot decrypt the token: a restored phone pairs as a new device (6.3).
 * The alias and the associated data name the purpose, so the ciphertext is useless anywhere else.
 */
@Singleton
class KeystoreCredentialCipher @Inject constructor() : CredentialCipher {
  private val lock = Any()

  override fun seal(plaintext: ByteArray): SealedCredential = synchronized(lock) {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
    cipher.updateAAD(AAD)
    SealedCredential(ciphertext = cipher.doFinal(plaintext), iv = cipher.iv)
  }

  override fun open(sealed: SealedCredential): ByteArray = synchronized(lock) {
    val key = try {
      key(create = false)
    } catch (e: GeneralSecurityException) {
      throw CredentialUnreadableException(e)
    } ?: throw CredentialUnreadableException()
    try {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.iv))
      cipher.updateAAD(AAD)
      cipher.doFinal(sealed.ciphertext)
    } catch (e: GeneralSecurityException) {
      // Covers a key invalidated by the system and a ciphertext that does not authenticate.
      throw CredentialUnreadableException(e)
    } catch (e: IllegalArgumentException) {
      // An empty or malformed IV read from a damaged file.
      throw CredentialUnreadableException(e)
    }
  }

  override fun forget() = synchronized(lock) {
    keyStore().deleteEntry(ALIAS)
  }

  private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

  private fun key(create: Boolean): SecretKey? {
    (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
    if (!create) return null
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
    generator.init(
      KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(KEY_BITS)
        .setRandomizedEncryptionRequired(true)
        .setUserAuthenticationRequired(false)
        .build(),
    )
    return generator.generateKey()
  }

  companion object {
    /** The keystore entry of the device credential key (architecture 20.3). */
    const val ALIAS: String = "device_credential_v1"

    const val PROVIDER: String = "AndroidKeyStore"
    const val KEY_BITS: Int = 256
    const val TAG_BITS: Int = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val AAD = "phonotrainer:$ALIAS".toByteArray(Charsets.UTF_8)
  }
}
