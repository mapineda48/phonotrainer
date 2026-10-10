package com.mapineda48.phonotrainer.core.testing.computer

import java.util.Locale

/** A minimal `multipart/form-data` reader for `/compare` (server-contract 6.9). */
internal object Multipart {
  class Part(val name: String, val fileName: String?, val contentType: String?, val data: ByteArray)

  /** The parts of [body], or null when [contentType] is not multipart or the body is malformed. */
  fun parse(contentType: String?, body: ByteArray): List<Part>? {
    val boundary = contentType
      ?.split(';')
      ?.map { it.trim() }
      ?.takeIf { it.firstOrNull()?.lowercase(Locale.ROOT) == "multipart/form-data" }
      ?.firstOrNull { it.lowercase(Locale.ROOT).startsWith("boundary=") }
      ?.substringAfter('=')
      ?.trim('"')
      ?: return null
    val delimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)
    val parts = mutableListOf<Part>()
    var position = indexOf(body, delimiter, 0)
    if (position < 0) return null
    while (true) {
      position += delimiter.size
      if (position + 2 <= body.size && body[position] == DASH && body[position + 1] == DASH) return parts
      position = skipLineBreak(body, position) ?: return null
      val headEnd = indexOf(body, BLANK_LINE, position)
      if (headEnd < 0) return null
      val headers = String(body, position, headEnd - position, Charsets.ISO_8859_1).split("\r\n")
      val dataStart = headEnd + BLANK_LINE.size
      val next = indexOf(body, "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1), dataStart)
      if (next < 0) return null
      part(headers, body.copyOfRange(dataStart, next))?.let(parts::add)
      position = next + 2
    }
  }

  private fun part(headers: List<String>, data: ByteArray): Part? {
    var name: String? = null
    var fileName: String? = null
    var type: String? = null
    for (header in headers) {
      val key = header.substringBefore(':').trim().lowercase(Locale.ROOT)
      val value = header.substringAfter(':', "").trim()
      when (key) {
        "content-disposition" -> value.split(';').map { it.trim() }.forEach { attribute ->
          when {
            attribute.startsWith("name=") -> name = attribute.substringAfter('=').trim('"')
            attribute.startsWith("filename=") -> fileName = attribute.substringAfter('=').trim('"')
          }
        }
        "content-type" -> type = value
      }
    }
    return name?.let { Part(it, fileName, type, data) }
  }

  private fun skipLineBreak(body: ByteArray, at: Int): Int? =
    if (at + 1 < body.size && body[at] == CR && body[at + 1] == LF) at + 2 else null

  private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
    outer@ for (i in from..haystack.size - needle.size) {
      for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
      return i
    }
    return -1
  }

  private const val DASH = '-'.code.toByte()
  private const val CR = '\r'.code.toByte()
  private const val LF = '\n'.code.toByte()
  private val BLANK_LINE = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
}
