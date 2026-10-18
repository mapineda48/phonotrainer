package com.mapineda48.phonotrainer.feature.connect

import android.content.Context
import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/**
 * The default for "Name of this phone on your computer" (spec F-02 behaviour 5): the phone's model name, which the
 * learner can edit. Never the device name the owner may have set in Android's settings (it often carries a person's
 * name, spec 11.1).
 */
internal fun interface PhoneModel {
  fun name(): String
}

@Module
@InstallIn(SingletonComponent::class)
internal object ConnectModule {
  @Provides
  fun phoneModel(@ApplicationContext context: Context): PhoneModel = PhoneModel {
    Build.MODEL?.trim()?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.connect_default_phone_name)
  }
}
