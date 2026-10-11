package com.mapineda48.phonotrainer.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.common.log.LeveledPhonoLog
import com.mapineda48.phonotrainer.core.datastore.credential.FakeAndroidKeyStore
import com.mapineda48.phonotrainer.core.datastore.credential.KeystoreCredentialCipher
import com.mapineda48.phonotrainer.core.datastore.pairing.DataStorePairingStore
import com.mapineda48.phonotrainer.core.datastore.pairing.DeviceKeyResult
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.model.Appearance
import com.mapineda48.phonotrainer.core.domain.model.CoachMarkId
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.domain.model.remote.PairGrant
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A damaged or unreadable DataStore file never takes the app down (wave-1 architecture review, finding 4).
 * `settings.preferences_pb` is the file Auto Backup restores, so a partial or foreign restore must start the app with
 * the defaults, as a fresh install does; a damaged `pairing.preferences_pb` reads as "not paired". The stores are built
 * through the real [DataStoreModule] providers on the files the app uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DamagedFilesTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val scheduler = TestCoroutineScheduler()
  private val dispatcher = UnconfinedTestDispatcher(scheduler)

  /** What would reach the platform's uncaught-exception handler, i.e. kill the process, in the app. */
  private val uncaught = CopyOnWriteArrayList<Throwable>()

  /** Like the app's `@ApplicationScope` (a `SupervisorJob` with no handler); the handler records what would crash. */
  private val appScope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> uncaught += e })

  /** The scope the DataStore instances live in; cancelled to close them so a file can be opened again. */
  private val storeScope = CoroutineScope(SupervisorJob() + dispatcher)

  /** The release form of the log (event code and error class only). */
  private val logLines = CopyOnWriteArrayList<String>()
  private val log = LeveledPhonoLog({ _, _, message, _ -> logLines += message }, verbose = false)

  @Before
  fun setUp() = FakeAndroidKeyStore.install()

  @After
  fun tearDown() {
    FakeAndroidKeyStore.uninstall()
    appScope.cancel()
    storeScope.cancel()
  }

  // -- Damaged files --------------------------------------------------------------------------------------------------

  @Test
  fun aSettingsFileThatIsNotPreferencesStartsWithTheDefaults() = runTest(scheduler) {
    damage(DataStoreFiles.SETTINGS, GARBAGE)
    val repository = DataStoreSettingsRepository(settingsStore(), appScope)

    assertThat(repository.loadedWithinSeconds()).isTrue()
    assertThat(repository.settings.value).isEqualTo(UserSettings())
    assertThat(uncaught).isEmpty()
    assertThat(logLines).containsExactly("datastore.settings_corrupt (androidx.datastore.core.CorruptionException)")
  }

  @Test
  fun aTruncatedSettingsFileStartsWithTheDefaultsAndIsReplacedByTheNextWrite() = runTest(scheduler) {
    val file = context.preferencesDataStoreFile(DataStoreFiles.SETTINGS)
    writeValidThenTruncate(file)
    val repository = DataStoreSettingsRepository(settingsStore(), appScope)

    assertThat(repository.loadedWithinSeconds()).isTrue()
    assertThat(repository.settings.value).isEqualTo(UserSettings())

    repository.update { it.copy(appearance = Appearance.DARK, welcomeDone = true) }
    assertThat(repository.settings.value.appearance).isEqualTo(Appearance.DARK)
    // The file is whole again: a store with no corruption handler reads it.
    storeScope.coroutineContext.job.cancelAndJoin()
    val reopened = plainStore(file)
    assertThat(reopened.data.first()[stringPreferencesKey("appearance")]).isEqualTo("DARK")
    assertThat(uncaught).isEmpty()
    assertThat(logLines).containsExactly("datastore.settings_corrupt (androidx.datastore.core.CorruptionException)")
  }

  @Test
  fun aDamagedSettingsFileShowsEveryCoachMarkAgain() = runTest(scheduler) {
    damage(DataStoreFiles.SETTINGS, GARBAGE)
    val marks = DataStoreCoachMarkRepository(settingsStore())
    val tapWord = CoachMarkId("tap_word")

    assertThat(marks.shouldShow(tapWord).first()).isTrue()
    marks.dismiss(tapWord)
    assertThat(marks.shouldShow(tapWord).first()).isFalse()
  }

  @Test
  fun aDamagedPairingFileReadsAsNotPairedAndPairingAgainWorks() = runTest(scheduler) {
    damage(DataStoreFiles.PAIRING, GARBAGE)
    val store = DataStorePairingStore(pairingStore(), KeystoreCredentialCipher(), dispatcher)

    assertThat(store.current()).isNull()
    assertThat(store.pairing.first()).isNull()
    assertThat(store.withDeviceKey { it.size }).isEqualTo(DeviceKeyResult.NotPaired)

    store.save(grant(), HostPort("192.0.2.10", 8000), "Pixel test phone", Instant.EPOCH, clockOffsetS = 0)
    assertThat(store.current()?.serverId).isEqualTo(ServerId("srv_one"))
    assertThat(store.withDeviceKey { it.size }).isEqualTo(DeviceKeyResult.Ok(32))
    assertThat(logLines).containsExactly("datastore.pairing_corrupt (androidx.datastore.core.CorruptionException)")
  }

  // -- Files that cannot be read (IOException) ------------------------------------------------------------------------

  @Test
  fun anUnreadableSettingsFileStartsWithTheDefaults() = runTest(scheduler) {
    val repository = DataStoreSettingsRepository(FailingStore(IOException("read failed")), appScope)

    assertThat(repository.loadedWithinSeconds()).isTrue()
    assertThat(repository.settings.value).isEqualTo(UserSettings())
    assertThat(uncaught).isEmpty()
  }

  @Test
  fun anUnreadableSettingsFileShowsTheCoachMarks() = runTest(scheduler) {
    val marks = DataStoreCoachMarkRepository(FailingStore(IOException("read failed")))

    assertThat(marks.shouldShow(CoachMarkId("tap_word")).first()).isTrue()
  }

  @Test
  fun anUnreadablePairingFileReadsAsNotPaired() = runTest(scheduler) {
    val store = DataStorePairingStore(FailingStore(IOException("read failed")), KeystoreCredentialCipher(), dispatcher)

    assertThat(store.current()).isNull()
    assertThat(store.pairing.first()).isNull()
    assertThat(store.withDeviceKey { it.size }).isEqualTo(DeviceKeyResult.NotPaired)
  }

  @Test
  fun anErrorThatIsNotAnIoErrorStillReachesTheScopeButNeverHoldsTheSplash() = runTest(scheduler) {
    val bug = IllegalStateException("a bug, not a damaged file")
    val repository = DataStoreSettingsRepository(FailingStore(bug), appScope)

    assertThat(repository.loadedWithinSeconds()).isTrue()
    assertThat(uncaught).containsExactly(bug)
  }

  // -- Helpers --------------------------------------------------------------------------------------------------------

  private fun settingsStore(): DataStore<Preferences> =
    DataStoreModule.settingsDataStore(context, storeScope, dispatcher, log)

  private fun pairingStore(): DataStore<Preferences> =
    DataStoreModule.pairingDataStore(context, storeScope, dispatcher, log)

  private fun plainStore(file: File): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(scope = appScope) { file }

  private fun damage(name: String, bytes: ByteArray) {
    val file = context.preferencesDataStoreFile(name)
    file.parentFile?.mkdirs()
    file.writeBytes(bytes)
  }

  /** A real settings file cut short, as a partial restore leaves it. */
  private suspend fun writeValidThenTruncate(file: File) {
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val writer = PreferenceDataStoreFactory.create(scope = scope) { file }
    writer.edit {
      it[stringPreferencesKey("appearance")] = "LIGHT"
      it[stringPreferencesKey("captions")] = "IPA"
    }
    scope.coroutineContext.job.cancelAndJoin()
    val bytes = file.readBytes()
    file.writeBytes(bytes.copyOf(bytes.size - 3))
  }

  /** Waits in real time, since the DataStore may read on its own threads; false if [awaitLoaded] never returned. */
  private suspend fun SettingsRepository.loadedWithinSeconds(): Boolean = withContext(Dispatchers.Default) {
    withTimeoutOrNull(LOAD_TIMEOUT_MS) {
      awaitLoaded()
      true
    } ?: false
  }

  private fun grant() = PairGrant(
    deviceId = DeviceId("dev_one"),
    token = "test-token-one".toCharArray(),
    serverId = ServerId("srv_one"),
    name = "Study computer",
    libraryId = LibraryId("lib_one"),
    apiVersion = 6,
    minAppVersion = 1,
    serverTime = 1_791_622_800,
  )

  /** A store whose file cannot be read or written. */
  private class FailingStore(private val error: Throwable) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw error }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw error
  }

  private companion object {
    /**
     * Not a preferences protobuf: its first tag is a varint that never ends. (Text is no good here: the protobuf lite
     * reader stops at an end-group tag such as `t` and reads an empty map, so a text file already starts with defaults.)
     */
    val GARBAGE = ByteArray(16) { 0xFF.toByte() }

    const val LOAD_TIMEOUT_MS = 5_000L
  }
}
