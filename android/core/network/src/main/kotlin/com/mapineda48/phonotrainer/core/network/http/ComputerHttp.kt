package com.mapineda48.phonotrainer.core.network.http

import com.mapineda48.phonotrainer.core.network.guard.CleartextGuard
import com.mapineda48.phonotrainer.core.network.guard.GuardEventListener
import com.mapineda48.phonotrainer.core.network.guard.NoDns
import java.net.Proxy
import java.time.Duration
import java.util.EnumMap
import javax.net.SocketFactory
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.buffer

/** The kinds of call of architecture 5.1, each with its own timeouts. */
internal enum class Profile { PROBE, PROBE_SLOW, API, DOWNLOAD, UPLOAD, CHANNEL }

/**
 * The timeouts of architecture 5.1 (tests shorten them). A probe allows [probeWhileAnalyzing] while the computer was
 * last known to be analyzing (spec 7.5); the channel pings every [channelPing], so a missing pong fails it within two
 * intervals (14 s, spec 7.5). [idleConnection] stays below the server's 5 s keep-alive (uvicorn's default), so the
 * client never reuses a connection the computer has already closed.
 */
internal data class NetworkTimeouts(
  val probe: Duration = Duration.ofSeconds(3),
  val probeWhileAnalyzing: Duration = Duration.ofSeconds(10),
  val connect: Duration = Duration.ofSeconds(5),
  val apiRead: Duration = Duration.ofSeconds(20),
  val apiWrite: Duration = Duration.ofSeconds(20),
  val apiCall: Duration = Duration.ofSeconds(30),
  val downloadRead: Duration = Duration.ofSeconds(30),
  val uploadRead: Duration = Duration.ofSeconds(60),
  val uploadWrite: Duration = Duration.ofSeconds(60),
  val channelPing: Duration = Duration.ofSeconds(7),
  val idleConnection: Duration = Duration.ofSeconds(4),
)

/**
 * The one OkHttp client of architecture 5.1 and the clients derived from it per [Profile]: they share the connection
 * pool, the dispatcher, the guard, the signer, the network binding and the absence of DNS and proxies, and differ only
 * in their timeouts.
 */
internal class ComputerHttp(val base: OkHttpClient, private val timeouts: NetworkTimeouts) {
  private val clients = EnumMap<Profile, OkHttpClient>(Profile::class.java)

  fun client(profile: Profile): OkHttpClient = synchronized(clients) {
    clients.getOrPut(profile) { derive(profile) }
  }

  /** The seconds after which a call of [profile] gives up waiting: what `ProbeOutcome.NoAnswer` reports. */
  fun noAnswerAfterS(profile: Profile): Int = when (profile) {
    Profile.PROBE -> timeouts.probe
    Profile.PROBE_SLOW -> timeouts.probeWhileAnalyzing
    Profile.API -> timeouts.apiCall
    Profile.DOWNLOAD -> timeouts.downloadRead
    Profile.UPLOAD -> timeouts.uploadRead
    Profile.CHANNEL -> timeouts.connect
  }.seconds.toInt().coerceAtLeast(1)

  private fun derive(profile: Profile): OkHttpClient {
    val builder = base.newBuilder().connectTimeout(timeouts.connect)
    when (profile) {
      Profile.PROBE, Profile.PROBE_SLOW -> {
        val limit = if (profile == Profile.PROBE) timeouts.probe else timeouts.probeWhileAnalyzing
        builder.connectTimeout(minOf(limit, timeouts.probe)).readTimeout(limit).writeTimeout(limit).callTimeout(limit)
      }
      Profile.API -> builder.readTimeout(timeouts.apiRead).writeTimeout(timeouts.apiWrite).callTimeout(timeouts.apiCall)
      Profile.DOWNLOAD -> builder.readTimeout(timeouts.downloadRead).writeTimeout(timeouts.apiWrite).callTimeout(Duration.ZERO)
      Profile.UPLOAD -> builder.readTimeout(timeouts.uploadRead).writeTimeout(timeouts.uploadWrite).callTimeout(Duration.ZERO)
      Profile.CHANNEL -> builder.readTimeout(Duration.ZERO).callTimeout(Duration.ZERO).pingInterval(timeouts.channelPing)
    }
    return builder.build()
  }

  companion object {
    /**
     * The base client: no DNS ([dns] is replaced only by tests that count lookups), no proxy, no redirects, HTTP/1.1,
     * the guard first, then the signer and the caps.
     */
    fun create(
      guard: CleartextGuard,
      signing: Interceptor,
      socketFactory: SocketFactory,
      pool: ConnectionPool,
      timeouts: NetworkTimeouts,
      debugLog: Interceptor?,
      dns: Dns = NoDns,
    ): ComputerHttp {
      val base = OkHttpClient.Builder()
        .dns(dns)
        .proxy(Proxy.NO_PROXY)
        .socketFactory(socketFactory)
        .connectionPool(pool)
        .eventListener(GuardEventListener(guard))
        .protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .addInterceptor(guard)
        .addInterceptor(signing)
        .addInterceptor(BodyCapInterceptor)
        .apply { debugLog?.let(::addNetworkInterceptor) }
        .build()
      return ComputerHttp(base, timeouts)
    }

    /** The pool every derived client shares; idle connections close before the server's keep-alive ends. */
    fun pool(timeouts: NetworkTimeouts): ConnectionPool =
      ConnectionPool(MAX_IDLE_CONNECTIONS, timeouts.idleConnection.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)

    private const val MAX_IDLE_CONNECTIONS = 4
  }
}

/** The routes of server-contract 6 the stack treats specially. */
internal object Routes {
  private const val PHONE = "/api/phone/"
  private val UNSIGNED = setOf("/api/phone/hello", "/api/phone/pair")
  private val ARTIFACT = Regex("^/api/phone/jobs/[^/]+/artifacts/[^/]+$")
  private val RECORD = Regex("^/api/phone/jobs/[^/]+/(contour|compare)$")

  /** Every phone route but `hello` and `pair` is signed (server-contract 4.3); `/api/health` (D-15) never is. */
  fun isSigned(url: HttpUrl): Boolean = url.encodedPath.startsWith(PHONE) && url.encodedPath !in UNSIGNED

  fun isArtifact(url: HttpUrl): Boolean = ARTIFACT.matches(url.encodedPath)

  /**
   * The largest answer read from [url] (architecture 5.4): 16 MiB for the listing, 64 KiB for other JSON answers. The
   * record-yourself answers (v1-complete) carry a pitch contour per take and get 1 MiB. Artifacts are bounded by
   * their listed size in the transfer instead.
   */
  fun answerCap(url: HttpUrl): Long? = when {
    isArtifact(url) -> null
    url.encodedPath == "/api/phone/library" -> LISTING_CAP
    RECORD.matches(url.encodedPath) -> RECORD_CAP
    else -> JSON_CAP
  }

  const val JSON_CAP: Long = 64L * 1024L
  const val LISTING_CAP: Long = 16L * 1024L * 1024L
  const val RECORD_CAP: Long = 1024L * 1024L
}

/**
 * Reads every JSON answer with its cap (architecture 5.4, security review M-3): a declared length over the cap fails
 * before a byte is read, and a body that grows past it fails as soon as it does, with [MalformedAnswerException].
 */
internal object BodyCapInterceptor : Interceptor {
  private const val SWITCHING_PROTOCOLS = 101

  override fun intercept(chain: Interceptor.Chain): Response {
    val response = chain.proceed(chain.request())
    val cap = Routes.answerCap(chain.request().url)
    if (cap == null || response.code == SWITCHING_PROTOCOLS) return response
    val body = response.body
    val declared = body.contentLength()
    if (declared > cap) {
      response.close()
      throw MalformedAnswerException("The answer is larger than $cap bytes.")
    }
    val capped = CappedSource(body.source(), cap).buffer().asResponseBody(body.contentType(), declared)
    return response.newBuilder().body(capped).build()
  }

  private class CappedSource(delegate: Source, private val cap: Long) : ForwardingSource(delegate) {
    private var read = 0L

    override fun read(sink: Buffer, byteCount: Long): Long {
      val count = super.read(sink, byteCount)
      if (count > 0) {
        read += count
        if (read > cap) throw MalformedAnswerException("The answer is larger than $cap bytes.")
      }
      return count
    }
  }
}
