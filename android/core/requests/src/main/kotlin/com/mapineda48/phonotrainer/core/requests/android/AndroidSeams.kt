package com.mapineda48.phonotrainer.core.requests.android

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.requests.R
import com.mapineda48.phonotrainer.core.requests.store.AppVisibility
import com.mapineda48.phonotrainer.core.requests.store.RequestWords
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Whether the app is in the foreground, from `ProcessLifecycleOwner` (at least STARTED). */
internal class ProcessVisibility @Inject constructor() : AppVisibility {
  override fun visible(): Boolean =
    runCatching { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }.getOrDefault(false)
}

/** The queue's own words, from this module's `strings.xml`. */
internal class ResourceRequestWords @Inject constructor(
  @param:ApplicationContext private val context: Context,
) : RequestWords {
  override fun linkTitle(site: Site?): String = context.getString(
    when (site) {
      Site.YOUTUBE -> R.string.requests_title_youtube_link
      Site.YOUTUBE_SHORTS -> R.string.requests_title_shorts_link
      Site.X -> R.string.requests_title_x_link
      Site.OTHER, null -> R.string.requests_title_website_link
    },
  )

  override fun fileTitle(video: Boolean): String =
    context.getString(if (video) R.string.requests_title_video else R.string.requests_title_audio)
}
