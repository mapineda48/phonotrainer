package com.mapineda48.phonotrainer.core.network.http

import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import okhttp3.Interceptor

/** Release builds log no request (architecture 20.4): the logging interceptor is a debug-only dependency. */
internal object HttpDebugLog {
  const val TAG: String = "PhonoHttp"

  @Suppress("UNUSED_PARAMETER")
  fun interceptor(log: PhonoLog): Interceptor? = null
}
