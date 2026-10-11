package com.mapineda48.phonotrainer.core.content

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.content.json.decodeReference
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The reference snapshot over time: bundled, replaced by sync, kept across restarts (architecture 7.1, 15.4). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReferenceRepositoryTest {
  @get:Rule
  val folder = TemporaryFolder()

  private val bundledVersion = decodeReference(SourceAssets.reference).apiVersion
  private val file by lazy { File(folder.root, "reference/reference.json") }

  private fun TestScope.repository(store: ReferenceStore = ReferenceStore(file)) =
    ReferenceRepositoryImpl(SourceAssets(), store, this, StandardTestDispatcher(testScheduler))

  @Test
  fun itStartsWithTheBundledSnapshot() = runTest {
    val repository = repository()
    advanceUntilIdle()

    assertThat(repository.reference.value.apiVersion).isEqualTo(bundledVersion)
    assertThat(repository.reference.value.labels).hasSize(16)
    assertThat(file.exists()).isFalse()
  }

  @Test
  fun aNewBodyIsStoredAndShown() = runTest {
    val repository = repository()
    val body = referenceWithVersion(bundledVersion + 1)

    repository.replace(body, bundledVersion + 1)

    assertThat(repository.reference.value.apiVersion).isEqualTo(bundledVersion + 1)
    assertThat(file.readText()).isEqualTo(body)
    assertThat(file.parentFile?.list()?.toList()).containsExactly("reference.json")
  }

  @Test
  fun theStoredBodyIsServedAfterARestart() = runTest {
    repository().replace(referenceWithVersion(bundledVersion + 1), bundledVersion + 1)

    val restarted = repository()
    advanceUntilIdle()

    assertThat(restarted.reference.value.apiVersion).isEqualTo(bundledVersion + 1)
  }

  @Test
  fun theStoredBodyWinsEvenWhenOlderThanTheBundledOne() = runTest {
    // It is what the paired computer serves; an app update must not swap it for its own snapshot.
    ReferenceStore(file).write(referenceWithVersion(bundledVersion - 1))

    val repository = repository()
    advanceUntilIdle()

    assertThat(repository.reference.value.apiVersion).isEqualTo(bundledVersion - 1)
  }

  @Test
  fun aBodyThatDoesNotDecodeIsRefused() = runTest {
    val repository = repository()

    repository.replace("""{"api_version": 9, "families": []}""", 9)
    repository.replace("<html>", 9)

    assertThat(repository.reference.value.apiVersion).isEqualTo(bundledVersion)
    assertThat(file.exists()).isFalse()
  }

  @Test
  fun aBodyForAnotherVersionThanTheListingsIsRefused() = runTest {
    val repository = repository()

    repository.replace(referenceWithVersion(bundledVersion + 1), bundledVersion + 2)

    assertThat(repository.reference.value.apiVersion).isEqualTo(bundledVersion)
    assertThat(file.exists()).isFalse()
  }

  @Test
  fun aCorruptStoredBodyFallsBackToTheBundledSnapshot() = runTest {
    file.parentFile?.mkdirs()
    file.writeText("""{"api_version": 9""")

    val repository = repository()
    advanceUntilIdle()

    assertThat(repository.reference.value.apiVersion).isEqualTo(bundledVersion)
  }

  @Test
  fun aBodyThatCannotBeStoredIsNotShown() = runTest {
    // Shown but not stored, it would vanish at the next start while sync believes it current.
    val blocked = File(folder.root, "blocked").apply { writeText("a file where the directory should be") }
    val repository = repository(ReferenceStore(File(blocked, "reference.json")))

    repository.replace(referenceWithVersion(bundledVersion + 1), bundledVersion + 1)

    assertThat(repository.reference.value.apiVersion).isEqualTo(bundledVersion)
  }

  @Test
  fun aReplaceWaitsForTheStoredBodySoAnOlderOneNeverWins() = runTest {
    ReferenceStore(file).write(referenceWithVersion(bundledVersion - 1))
    val repository = repository()

    // The stored body has not been read yet when sync replaces it.
    launch { repository.replace(referenceWithVersion(bundledVersion + 1), bundledVersion + 1) }
    advanceUntilIdle()

    assertThat(repository.reference.value.apiVersion).isEqualTo(bundledVersion + 1)
    assertThat(decodeReference(file.readText()).apiVersion).isEqualTo(bundledVersion + 1)
  }

  @Test
  fun anUnreadableStoreReadsAsNone() {
    val directoryInsteadOfFile = File(folder.root, "reference.json").apply { mkdirs() }

    assertThat(ReferenceStore(directoryInsteadOfFile).read()).isNull()
    assertThat(ReferenceStore(File(folder.root, "absent.json")).read()).isNull()
  }
}
