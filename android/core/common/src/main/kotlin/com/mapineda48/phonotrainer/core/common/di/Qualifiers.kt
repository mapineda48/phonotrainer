package com.mapineda48.phonotrainer.core.common.di

import javax.inject.Qualifier

// Qualifiers bound once by :app: the concurrency of architecture 3.3 (CoroutinesModule) and the process start
// instant of architecture 4.6.

/** The `CoroutineScope` (SupervisorJob + Dispatchers.Default) for work that outlives a screen. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** `Dispatchers.IO`: files, Room, DataStore, sockets. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** `Dispatchers.Default`: parsing and other CPU work. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/**
 * The `java.time.Instant` at which this process started, recorded once by the application before anything else runs
 * (architecture 4.6). `StartupCheck` hands it to every `StartupTask`, which acts only on rows and files last updated
 * before it, so a share that cold-starts the app is never mistaken for an interrupted one.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ProcessStart
