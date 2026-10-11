package com.mapineda48.phonotrainer.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * [DataStore.data] for every read of the three stores: a file that cannot be read (an `IOException`, which includes a
 * damaged file no corruption handler replaced) reads as an empty file, i.e. the defaults or "not paired", as described
 * on [DataStoreModule]. The flow then ends; a later write still goes to the file and reports its own failure to its
 * caller. Any other error is a bug and propagates.
 */
internal fun DataStore<Preferences>.dataOrEmpty(): Flow<Preferences> =
  data.catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
