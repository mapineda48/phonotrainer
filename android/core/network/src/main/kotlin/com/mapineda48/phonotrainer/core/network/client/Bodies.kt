package com.mapineda48.phonotrainer.core.network.client

import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * [length] bytes of [file] from [offset] (the whole file for `PUT`, a chunk for `PATCH`; server-contract 6.6), read
 * from disk each time it is written, with [onProgress] told the bytes sent so far. One-shot: OkHttp never resends a
 * file on its own after a connection failure; the device queue decides when to send it again (architecture 8.5).
 */
internal class FileRangeBody(
  private val file: File,
  private val offset: Long,
  private val length: Long,
  private val type: MediaType,
  private val onProgress: (Long) -> Unit = {},
) : RequestBody() {
  override fun contentType(): MediaType = type

  override fun contentLength(): Long = length

  override fun isOneShot(): Boolean = true

  override fun writeTo(sink: BufferedSink) {
    RandomAccessFile(file, "r").use { input ->
      input.seek(offset)
      val buffer = ByteArray(CHUNK_BYTES)
      var sent = 0L
      while (sent < length) {
        val read = input.read(buffer, 0, minOf(CHUNK_BYTES.toLong(), length - sent).toInt())
        if (read < 0) throw EOFException("The file is shorter than declared.")
        sink.write(buffer, 0, read)
        sent += read
        onProgress(sent)
      }
    }
  }

  companion object {
    private const val CHUNK_BYTES = 64 * 1024
    val OCTET_STREAM: MediaType = "application/octet-stream".toMediaType()
    val OFFSET_OCTET_STREAM: MediaType = "application/offset+octet-stream".toMediaType()
  }
}

/** A JSON body OkHttp never sends twice on its own: the pairing request, whose code is single-use (6.2). */
internal class OneShotJsonBody(private val bytes: ByteArray, private val type: MediaType) : RequestBody() {
  override fun contentType(): MediaType = type

  override fun contentLength(): Long = bytes.size.toLong()

  override fun isOneShot(): Boolean = true

  override fun writeTo(sink: BufferedSink) {
    sink.write(bytes)
  }
}
