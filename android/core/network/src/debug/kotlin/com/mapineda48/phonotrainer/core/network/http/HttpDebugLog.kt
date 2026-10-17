package com.mapineda48.phonotrainer.core.network.http

import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor

/**
 * The request log of debug builds (architecture 20.4, security review M-7): request and response lines and headers
 * only (`HEADERS`, never `BODY`, which would print the pairing code of `/pair` and the token of its answer), with
 * `Authorization` redacted, written through [PhonoLog]. Release builds have no such log (the release source set).
 */
internal object HttpDebugLog {
  const val TAG: String = "PhonoHttp"

  fun interceptor(log: PhonoLog): Interceptor? =
    HttpLoggingInterceptor { line -> log.d(TAG) { line } }.apply {
      level = HttpLoggingInterceptor.Level.HEADERS
      redactHeader("Authorization")
    }
}
