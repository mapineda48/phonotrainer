package com.mapineda48.phonotrainer.core.common.id

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import org.junit.Test

/** Request ids are random lower-case UUIDs in the canonical form the computer accepts (server-contract 6). */
class UuidsTest {
  @Test
  fun randomIdsAreCanonicalLowerCaseVersion4() {
    repeat(100) {
      val id = Uuids.random()
      assertThat(Uuids.isCanonical(id)).isTrue()
      assertThat(id).isEqualTo(id.lowercase())
      assertThat(id[14]).isEqualTo('4')
    }
  }

  @Test
  fun randomIdsDoNotRepeat() {
    val ids = List(10_000) { Uuids.random() }
    assertThat(ids.toSet()).hasSize(10_000)
  }

  @Test
  fun canonicalFormIsCheckedCaseInsensitively() {
    assertThat(Uuids.isCanonical("5a0c4c5e-0b6e-4bd6-9f53-1f1b0bbf7c11")).isTrue()
    assertThat(Uuids.isCanonical("5A0C4C5E-0B6E-4BD6-9F53-1F1B0BBF7C11")).isTrue()
    listOf(
      null,
      "",
      "not-a-uuid",
      "5a0c4c5e0b6e4bd69f531f1b0bbf7c11",
      "{5a0c4c5e-0b6e-4bd6-9f53-1f1b0bbf7c11}",
      "5a0c4c5e-0b6e-4bd6-9f53-1f1b0bbf7c1",
      "5a0c4c5e-0b6e-4bd6-9f53-1f1b0bbf7c111",
      "5a0c4c5g-0b6e-4bd6-9f53-1f1b0bbf7c11",
      " 5a0c4c5e-0b6e-4bd6-9f53-1f1b0bbf7c11",
    ).forEach { assertThat(Uuids.isCanonical(it)).isFalse() }
  }

  @Test
  fun aSourceGivesRequestIds() {
    var n = 0
    val source = UuidSource { "00000000-0000-4000-8000-00000000000${n++}" }

    assertThat(source.newRequestId()).isEqualTo(RequestId("00000000-0000-4000-8000-000000000000"))
    assertThat(source.newRequestId()).isEqualTo(RequestId("00000000-0000-4000-8000-000000000001"))
    assertThat(Uuids.isCanonical(UuidSource.RANDOM.next())).isTrue()
  }
}
