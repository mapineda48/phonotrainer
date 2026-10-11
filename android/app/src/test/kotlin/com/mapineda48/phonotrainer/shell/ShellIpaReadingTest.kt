package com.mapineda48.phonotrainer.shell

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.testing.fake.content.FakeDidacticContent
import com.mapineda48.phonotrainer.core.testing.fake.content.FakeReferenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.content.SampleDidactic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The reading the shell provides to `IpaText` (A-05): inventory tokens and phone names, built off the main thread. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellIpaReadingTest {
  private val reference = FakeReferenceRepository()
  private val didactic = FakeDidacticContent()
  private var referenceCreated = 0
  private var didacticCreated = 0

  private fun TestScope.source() = ShellIpaReading(
    reference = { reference.also { referenceCreated += 1 } },
    didactic = { didactic.also { didacticCreated += 1 } },
    scope = backgroundScope,
    dispatcher = StandardTestDispatcher(testScheduler),
  )

  @Test
  fun symbolsAreReadAsWrittenUntilTheContentIsRead() = runTest {
    val source = source()

    // Creating the source creates neither: the collection does, on the dispatcher, not the caller (the main thread).
    assertThat(referenceCreated).isEqualTo(0)
    assertThat(didacticCreated).isEqualTo(0)
    assertThat(source.reading.value.nameOf("ɾ")).isNull()
    assertThat(source.reading.value.split("taɪm")).containsExactly("t", "a", "ɪ", "m").inOrder()

    runCurrent()

    assertThat(referenceCreated).isEqualTo(1)
    assertThat(didacticCreated).isEqualTo(1)
    assertThat(source.reading.value.nameOf("ɾ")).isEqualTo(SampleDidactic.phoneTap.name)
    assertThat(source.reading.value.nameOf("ˈɾ")).isEqualTo(SampleDidactic.phoneTap.name)
    assertThat(source.reading.value.split("taɪm")).containsExactly("t", "aɪ", "m").inOrder()
  }

  @Test
  fun onlyASnapshotWithOtherTokensGivesANewReading() = runTest {
    val source = source()
    runCurrent()
    val first = source.reading.value

    reference.state.value = reference.state.value.copy(apiVersion = reference.state.value.apiVersion + 1)
    runCurrent()
    assertThat(source.reading.value).isSameInstanceAs(first)

    reference.state.value = reference.state.value.copy(ipaTokens = listOf("tʃ"))
    runCurrent()
    assertThat(source.reading.value).isNotSameInstanceAs(first)
    assertThat(source.reading.value.split("taɪm")).containsExactly("t", "a", "ɪ", "m").inOrder()
    assertThat(source.reading.value.split("tʃɪp")).containsExactly("tʃ", "ɪ", "p").inOrder()
    assertThat(source.reading.value.nameOf("ɾ")).isEqualTo(SampleDidactic.phoneTap.name)
  }
}
