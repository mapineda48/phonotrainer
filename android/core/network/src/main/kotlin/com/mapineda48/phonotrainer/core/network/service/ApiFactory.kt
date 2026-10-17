package com.mapineda48.phonotrainer.core.network.service

import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.network.dto.ComputerJson
import com.mapineda48.phonotrainer.core.network.guard.Ipv4
import com.mapineda48.phonotrainer.core.network.http.ComputerHttp
import com.mapineda48.phonotrainer.core.network.http.Profile
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * One [ComputerApi] per address and timeout profile, all on the shared client of [http] (architecture 5.1, 5.4). The
 * address is the base URL: a candidate for `hello`, `legacyHealth` and `pair`, the current address for everything
 * else. Only IPv4 literals with a port make an API; anything else is refused before Retrofit sees it.
 */
internal class ApiFactory(private val http: ComputerHttp) {
  private val converter = ComputerJson.asConverterFactory(JSON.toMediaType())
  private val apis = ConcurrentHashMap<Pair<HostPort, Profile>, ComputerApi>()

  /** The API at [at] with the timeouts of [profile]; null when [at] is not an IPv4 literal and a port. */
  fun api(at: HostPort, profile: Profile): ComputerApi? {
    if (!Ipv4.isLiteral(at.host) || at.port !in PORTS) return null
    val key = at to profile
    apis[key]?.let { return it }
    if (apis.size >= MAX_APIS) apis.clear()
    return apis.getOrPut(key) { create(at, profile) }
  }

  fun noAnswerAfterS(profile: Profile): Int = http.noAnswerAfterS(profile)

  private fun create(at: HostPort, profile: Profile): ComputerApi {
    val base = HttpUrl.Builder().scheme("http").host(at.host).port(at.port).build()
    return Retrofit.Builder()
      .baseUrl(base)
      .callFactory(http.client(profile))
      .addConverterFactory(converter)
      .build()
      .create(ComputerApi::class.java)
  }

  companion object {
    const val JSON = "application/json; charset=utf-8"
    private val PORTS = 1..65_535

    // A few addresses (the current one, a candidate, the next address after a change) times six profiles.
    private const val MAX_APIS = 32
  }
}
