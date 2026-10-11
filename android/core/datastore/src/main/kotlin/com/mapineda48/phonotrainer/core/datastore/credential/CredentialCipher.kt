package com.mapineda48.phonotrainer.core.datastore.credential

/**
 * The pairing token as `pairing.preferences_pb` keeps it: ciphertext and IV, never the plaintext (architecture 20.3).
 * Not a data class: it holds arrays.
 */
class SealedCredential(val ciphertext: ByteArray, val iv: ByteArray)

/** The stored credential cannot be decrypted: the key is gone or invalidated, or the bytes were changed. */
class CredentialUnreadableException(cause: Throwable? = null) :
  Exception("The stored credential cannot be decrypted.", cause)

/**
 * Encrypts the pairing token for storage (architecture 20.3). The pairing store is its only user; the plaintext it
 * returns is wiped by the caller as soon as the device key is derived from it.
 */
interface CredentialCipher {
  /** Encrypts [plaintext] with the device credential key, creating the key the first time. */
  fun seal(plaintext: ByteArray): SealedCredential

  /** The plaintext of [sealed]. @throws CredentialUnreadableException when it cannot be decrypted. */
  fun open(sealed: SealedCredential): ByteArray

  /** Deletes the key ("Forget this computer"): every credential sealed before can no longer be opened. */
  fun forget()
}
