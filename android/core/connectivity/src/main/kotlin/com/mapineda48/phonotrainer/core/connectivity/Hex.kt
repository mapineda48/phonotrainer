package com.mapineda48.phonotrainer.core.connectivity

/** Lower-case hexadecimal, as every nonce, challenge and proof of the contract is written (server-contract 4.3). */
internal object Hex {
  private const val DIGITS = "0123456789abcdef"
  private const val NIBBLE = 4
  private const val LOW = 0x0f
  private const val BYTE = 0xff

  fun encode(bytes: ByteArray): String {
    val out = StringBuilder(bytes.size * 2)
    for (byte in bytes) {
      val value = byte.toInt() and BYTE
      out.append(DIGITS[value ushr NIBBLE]).append(DIGITS[value and LOW])
    }
    return out.toString()
  }
}
