package com.mapineda48.phonotrainer.e2e.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.connectivity.ConnectivitySeamsModule
import com.mapineda48.phonotrainer.core.database.DatabaseModule
import com.mapineda48.phonotrainer.core.database.learning.LearningDatabase
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.datastore.DataStoreModule
import com.mapineda48.phonotrainer.core.datastore.PairingPreferences
import com.mapineda48.phonotrainer.core.datastore.SettingsPreferences
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.spi.DiscoveryTransport
import com.mapineda48.phonotrainer.core.domain.spi.NetworkMonitor
import com.mapineda48.phonotrainer.core.network.NetworkPolicyModule
import com.mapineda48.phonotrainer.core.network.api.AddressPolicy
import com.mapineda48.phonotrainer.core.network.api.ComputerNetworkBinder
import com.mapineda48.phonotrainer.core.testing.TestClock
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeNetworkMonitor
import com.mapineda48.phonotrainer.di.ClockModule
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.io.File
import java.util.UUID
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.plus

// The one global set of test modules of architecture 21.5 (integrator-owned), with `FakeComputerModule` in its own
// file. Repositories stay real: the B1 stubs until their owners merge, the real implementations afterwards. A
// `@TestInstallIn` module applies to every test of this source set and cannot be uninstalled, so nothing here may fake
// a repository; a test that truly needs one uses a per-class `@UninstallModules` plus `@BindValue` and says why.

/** Replaces `NetworkPolicyModule`: loopback and the emulator's host alias are candidates too; no network binding. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [NetworkPolicyModule::class])
object TestNetworkModule {
  @Provides
  fun addressPolicy(): AddressPolicy = object : AddressPolicy {
    override fun allowsCandidate(host: String): Boolean =
      host == "127.0.0.1" || host == "10.0.2.2" || host.startsWith("10.") || host.startsWith("192.168.")
  }

  @Provides
  fun networkBinder(): ComputerNetworkBinder = object : ComputerNetworkBinder {
    override fun bind(networkHandle: Long?) = Unit
  }
}

/**
 * Replaces `ConnectivitySeamsModule`: a scripted network (Wi-Fi by default; inject [FakeNetworkMonitor] to switch to
 * offline or mobile only) and [FakeDiscovery], which this test's fake computer answers.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ConnectivitySeamsModule::class])
object TestConnectivitySeamsModule {
  @Provides
  @Singleton
  fun fakeNetworkMonitor(): FakeNetworkMonitor = FakeNetworkMonitor()

  @Provides
  fun networkMonitor(fake: FakeNetworkMonitor): NetworkMonitor = fake

  @Provides
  fun discovery(fake: FakeDiscovery): DiscoveryTransport = fake
}

/** Replaces `DatabaseModule`: in-memory databases, fresh for every test. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DatabaseModule::class])
object TestDatabaseModule {
  @Provides
  @Singleton
  fun libraryDatabase(@ApplicationContext context: Context): LibraryDatabase =
    Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()

  @Provides
  @Singleton
  fun learningDatabase(@ApplicationContext context: Context): LearningDatabase =
    Room.inMemoryDatabaseBuilder(context, LearningDatabase::class.java).build()
}

/** Replaces `ClockModule`: a clock tests move by hand. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ClockModule::class])
object TestClockModule {
  @Provides
  @Singleton
  fun testClock(): TestClock = TestClock()

  @Provides
  fun clock(testClock: TestClock): Clock = testClock
}

/**
 * Replaces `DataStoreModule`: each test gets its own preference files, because every test builds a new Hilt
 * component and DataStore refuses two live instances on one file in a process; it also makes each test start from
 * the default settings.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DataStoreModule::class])
object TestDataStoreModule {
  @Provides
  @Singleton
  @SettingsPreferences
  fun settings(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): DataStore<Preferences> =
    store(context, scope, "settings")

  @Provides
  @Singleton
  @PairingPreferences
  fun pairing(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): DataStore<Preferences> =
    store(context, scope, "pairing")

  private fun store(context: Context, scope: CoroutineScope, name: String): DataStore<Preferences> {
    val dir = File(context.cacheDir, "e2e-datastore/${UUID.randomUUID()}")
    return PreferenceDataStoreFactory.create(scope = scope + Dispatchers.IO) { File(dir, "$name.preferences_pb") }
  }
}
