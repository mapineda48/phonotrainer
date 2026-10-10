package com.mapineda48.phonotrainer.core.network.api

/** Which hosts may be checked as a candidate computer; tests allow loopback (only in test sources, hygiene rule). */
interface AddressPolicy {
  fun allowsCandidate(host: String): Boolean
}
