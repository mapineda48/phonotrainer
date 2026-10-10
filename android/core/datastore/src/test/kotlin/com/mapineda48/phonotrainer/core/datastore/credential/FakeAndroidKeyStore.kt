package com.mapineda48.phonotrainer.core.datastore.credential

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * An in-memory `AndroidKeyStore` JCA provider for Robolectric, which has none: a key store of secret keys and an AES
 * key generator that takes a [KeyGenParameterSpec] the way the platform's does and records it. The keys are ordinary
 * AES keys, so the standard `AES/GCM/NoPadding` cipher of the JDK runs on them. [install] before a test, [uninstall]
 * after.
 */
object FakeAndroidKeyStore {
  /** The keys by alias. Tests remove or replace one to act as the system invalidating it. */
  val keys: MutableMap<String, SecretKey> = ConcurrentHashMap()

  /** Every spec a key was generated with, in order. */
  val specs: MutableList<KeyGenParameterSpec> = Collections.synchronizedList(mutableListOf())

  private val provider = object : Provider(NAME, 1.0, "In-memory AndroidKeyStore for tests") {
    init {
      putService(object : Service(this, "KeyStore", NAME, FakeKeyStore::class.java.name, null, null) {
        override fun newInstance(constructorParameter: Any?): Any = FakeKeyStore()
      })
      putService(object : Service(this, "KeyGenerator", "AES", FakeAesKeyGenerator::class.java.name, null, null) {
        override fun newInstance(constructorParameter: Any?): Any = FakeAesKeyGenerator()
      })
    }
  }

  fun install() {
    keys.clear()
    specs.clear()
    Security.removeProvider(NAME)
    Security.addProvider(provider)
  }

  fun uninstall() {
    Security.removeProvider(NAME)
    keys.clear()
    specs.clear()
  }

  private const val NAME = "AndroidKeyStore"

  private class FakeKeyStore : KeyStoreSpi() {
    override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]

    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null

    override fun engineGetCertificate(alias: String): Certificate? = null

    override fun engineGetCreationDate(alias: String): Date? = null

    override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
      keys[alias] = key as SecretKey
    }

    override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) =
      throw UnsupportedOperationException()

    override fun engineSetCertificateEntry(alias: String, cert: Certificate) = throw UnsupportedOperationException()

    override fun engineDeleteEntry(alias: String) {
      keys.remove(alias)
    }

    override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())

    override fun engineContainsAlias(alias: String): Boolean = alias in keys

    override fun engineSize(): Int = keys.size

    override fun engineIsKeyEntry(alias: String): Boolean = alias in keys

    override fun engineIsCertificateEntry(alias: String): Boolean = false

    override fun engineGetCertificateAlias(cert: Certificate): String? = null

    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit

    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
  }

  private class FakeAesKeyGenerator : KeyGeneratorSpi() {
    private var spec: KeyGenParameterSpec? = null
    private var random: SecureRandom = SecureRandom()

    override fun engineInit(random: SecureRandom?) =
      throw IllegalStateException("AndroidKeyStore keys need a KeyGenParameterSpec")

    override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
      spec = params as? KeyGenParameterSpec ?: throw IllegalArgumentException("not a KeyGenParameterSpec")
      if (random != null) this.random = random
    }

    override fun engineInit(keysize: Int, random: SecureRandom?) =
      throw IllegalStateException("AndroidKeyStore keys need a KeyGenParameterSpec")

    override fun engineGenerateKey(): SecretKey {
      val spec = checkNotNull(spec) { "init first" }
      val bytes = ByteArray(spec.keySize / Byte.SIZE_BITS).also(random::nextBytes)
      val key = SecretKeySpec(bytes, "AES")
      specs += spec
      keys[spec.keystoreAlias] = key
      return key
    }
  }
}
