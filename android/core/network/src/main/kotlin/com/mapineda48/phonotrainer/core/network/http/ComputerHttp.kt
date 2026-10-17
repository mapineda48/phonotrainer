package com.mapineda48.phonotrainer.core.network.http

import com.mapineda48.phonotrainer.core.network.api.ClockOffset
import com.mapineda48.phonotrainer.core.network.api.ComputerNetworkBinder
import com.mapineda48.phonotrainer.core.network.api.VerifiedPeers
import com.mapineda48.phonotrainer.core.network.binding.CurrentNetworkInterceptor
import com.mapineda48.phonotrainer.core.network.binding.NetworkBinding
import com.mapineda48.phonotrainer.core.network.binding.RetiredConnectionRetry
import com.mapineda48.phonotrainer.core.network.guard.CleartextGuard
import com.mapineda48.phonotrainer.core.network.guard.ComputerSocketFactory
import com.mapineda48.phonotrainer.core.network.guard.GuardEventListener
import com.mapineda48.phonotrainer.core.network.guard.NoDns
import com.mapineda48.phonotrainer.core.network.signing.AttemptSigner
import com.mapineda48.phonotrainer.core.network.signing.RequestSigner
import com.mapineda48.phonotrainer.core.network.signing.SigningInterceptor
import java.net.Proxy
import java.time.Duration
import java.util.EnumMap
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
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
 * in their timeouts. The channel's client is the exception: it never reuses a pooled connection and never retries by
 * itself, because OkHttp runs no network interceptor for a WebSocket upgrade (see [create]).
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
        // The upgrade is signed once, by the application interceptor: it is never resent on another connection,
        // and it always opens a fresh socket, bound to the network current at that moment.
        .retryOnConnectionFailure(false)
        .connectionPool(ConnectionPool(0, CHANNEL_POOL_KEEP_ALIVE_MS, TimeUnit.MILLISECONDS))
    }
    return builder.build()
  }

  companion object {
    /**
     * The base client: no DNS ([dns] is replaced only by tests that count lookups), no proxy, no redirects, HTTP/1.1.
     * Application interceptors: the guard first, the retired-connection retry, the signer's checks and its
     * `clock_skew` retry, the caps. Network interceptors, once per attempt (OkHttp's transparent retries included):
     * the retirement of connections from an earlier network (only with the client's own [NetworkBinding]), the PT1
     * signature with a fresh nonce, then the debug log. `NetworkModule` and the tests build it only through here.
     */
    fun create(
      guard: CleartextGuard,
      signer: RequestSigner,
      verified: VerifiedPeers,
      clockOffset: ClockOffset,
      binder: ComputerNetworkBinder,
      pool: ConnectionPool,
      timeouts: NetworkTimeouts,
      debugLog: Interceptor?,
      dns: Dns = NoDns,
    ): ComputerHttp {
      val builder = OkHttpClient.Builder()
        .dns(dns)
        .proxy(Proxy.NO_PROXY)
        .socketFactory(ComputerSocketFactory(binder, guard))
        .connectionPool(pool)
        .eventListener(GuardEventListener(guard))
        .protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .addInterceptor(guard)
        .addInterceptor(RetiredConnectionRetry)
        .addInterceptor(SigningInterceptor(signer, verified, clockOffset))
        .addInterceptor(BodyCapInterceptor)
      (binder as? NetworkBinding)?.let { builder.addNetworkInterceptor(CurrentNetworkInterceptor(it, pool)) }
      builder.addNetworkInterceptor(AttemptSigner(signer, verified))
      debugLog?.let(builder::addNetworkInterceptor)
      return ComputerHttp(builder.build(), timeouts)
    }

    /** The pool every derived client shares; idle connections close before the server's keep-alive ends. */
    fun pool(timeouts: NetworkTimeouts): ConnectionPool =
      ConnectionPool(MAX_IDLE_CONNECTIONS, timeouts.idleConnection.toMillis(), TimeUnit.MILLISECONDS)

    private const val MAX_IDLE_CONNECTIONS = 4
    private const val CHANNEL_POOL_KEEP_ALIVE_MS = 1_000L
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
 *
 * A `101 Switching Protocols` passes uncapped only as the answer to the job channel's WebSocket upgrade, whose body
 * OkHttp never reads (the channel bounds its own messages). OkHttp hands any other 1xx answer on with its body, and
 * Retrofit would buffer the whole body of that non-2xx answer, so it is refused before a byte of it is read.
 */
internal object BodyCapInterceptor : Interceptor {
  private const val SWITCHING_PROTOCOLS = 101
  private const val UPGRADE = "Upgrade"
  private const val WEBSOCKET = "websocket"
  private val INFORMATIONAL = 100..199

  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val response = chain.proceed(request)
    if (response.code in INFORMATIONAL) {
      if (response.code == SWITCHING_PROTOCOLS && isWebSocketUpgrade(request)) return response
      refuse(chain.call(), response, "The answer is an unexpected HTTP ${response.code}.")
    }
    val cap = Routes.answerCap(request.url) ?: return response
    val body = response.body
    val declared = body.contentLength()
    if (declared > cap) refuse(chain.call(), response, "The answer is larger than $cap bytes.")
    val capped = CappedSource(body.source(), cap, chain.call()).buffer().asResponseBody(body.contentType(), declared)
    return response.newBuilder().body(capped).build()
  }

  /** OkHttp's own WebSocket upgrade request carries `Upgrade: websocket`; no other request of this client does. */
  fun isWebSocketUpgrade(request: Request): Boolean = request.header(UPGRADE).equals(WEBSOCKET, ignoreCase = true)

  /**
   * Fails [call] with [MalformedAnswerException], reading nothing more: the call is cancelled first, which closes its
   * socket, so OkHttp does not read the rest of the refused body to keep the connection.
   */
  private fun refuse(call: Call, response: Response, message: String): Nothing {
    call.cancel()
    response.close()
    throw MalformedAnswerException(message)
  }

  private class CappedSource(
    delegate: Source,
    private val cap: Long,
    private val call: Call,
  ) : ForwardingSource(delegate) {
    private var read = 0L

    override fun read(sink: Buffer, byteCount: Long): Long {
      val count = super.read(sink, byteCount)
      if (count > 0) {
        read += count
        if (read > cap) {
          call.cancel()
          throw MalformedAnswerException("The answer is larger than $cap bytes.")
        }
      }
      return count
    }
  }
}
