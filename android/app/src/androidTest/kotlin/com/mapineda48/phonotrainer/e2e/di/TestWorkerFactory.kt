package com.mapineda48.phonotrainer.e2e.di

import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The worker factory of the test WorkManager (architecture 21.5). The runner initialises WorkManager once per
 * process, before any test's Hilt component exists, so this factory looks up the **running test's**
 * [HiltWorkerFactory] each time a worker is created: `@HiltWorker` workers of :core:sync and :core:requests get this
 * test's bindings (its fake computer, in-memory databases and clock). A worker Hilt does not know falls back to
 * WorkManager's default (reflection) factory.
 */
object TestWorkerFactory : WorkerFactory() {
  override fun createWorker(
    appContext: Context,
    workerClassName: String,
    workerParameters: WorkerParameters,
  ): ListenableWorker? = EntryPoints.get(appContext.applicationContext, WorkerFactoryEntryPoint::class.java)
    .hiltWorkerFactory()
    .createWorker(appContext, workerClassName, workerParameters)

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface WorkerFactoryEntryPoint {
    fun hiltWorkerFactory(): HiltWorkerFactory
  }
}
