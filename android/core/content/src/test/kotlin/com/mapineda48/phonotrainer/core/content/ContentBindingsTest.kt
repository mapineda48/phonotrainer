package com.mapineda48.phonotrainer.core.content

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.common.di.DefaultDispatcher
import com.mapineda48.phonotrainer.core.common.di.IoDispatcher
import com.mapineda48.phonotrainer.core.domain.api.DidacticContent
import com.mapineda48.phonotrainer.core.domain.api.ReferenceRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ContentModule's bindings resolve in a Hilt graph, as the app's does, and the store lands in `filesDir`. */
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class ContentBindingsTest {
  @get:Rule
  val hilt = HiltAndroidRule(this)

  @Inject
  lateinit var didactic: DidacticContent

  @Inject
  lateinit var references: ReferenceRepository

  @Before
  fun inject() {
    hilt.inject()
  }

  @Test
  fun theBundledContentIsBound() {
    assertThat(didactic.search("schwa").map { it.key }).contains("schwa")
    assertThat(references.reference.value.labels).hasSize(16)
  }

  @Test
  fun aReplacedReferenceIsStoredInTheAppFiles() = runTest {
    val next = references.reference.value.apiVersion + 1

    references.replace(referenceWithVersion(next), next)

    val filesDir = ApplicationProvider.getApplicationContext<Context>().filesDir
    assertThat(references.reference.value.apiVersion).isEqualTo(next)
    assertThat(File(filesDir, "reference/reference.json").isFile).isTrue()
  }

  /** What :app's CoroutinesModule provides, run in place for the test. */
  @Module
  @InstallIn(SingletonComponent::class)
  object TestCoroutinesModule {
    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Unconfined

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.Unconfined

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
  }
}
