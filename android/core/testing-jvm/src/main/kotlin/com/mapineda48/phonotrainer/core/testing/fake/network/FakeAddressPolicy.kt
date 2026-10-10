package com.mapineda48.phonotrainer.core.testing.fake.network

import com.mapineda48.phonotrainer.core.network.api.AddressPolicy

/** Settable [AddressPolicy]: allows every host until [allowed] says otherwise. */
class FakeAddressPolicy(var allowed: (String) -> Boolean = { true }) : AddressPolicy {
  override fun allowsCandidate(host: String): Boolean = allowed(host)
}
