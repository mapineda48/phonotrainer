package com.mapineda48.phonotrainer.core.common.di

import javax.inject.Qualifier

// Concurrency qualifiers of architecture 3.3, bound once by :app's CoroutinesModule.

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
