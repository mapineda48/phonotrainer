package com.mapineda48.phonotrainer.core.notifications

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.mapineda48.phonotrainer.core.domain.api.NotificationPermissionAsker
import com.mapineda48.phonotrainer.core.domain.api.Notifier
import com.mapineda48.phonotrainer.core.notifications.android.TransferNotifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the application calls once from `Application.onCreate` (architecture 13): the channels are created at start,
 * and the permission state is read again whenever an activity of the app resumes (after the system prompt, or back
 * from the system's notification settings). Until it is called the notifier still creates the channels before it posts
 * and reads the permission before every post.
 */
@Singleton
class NotificationsStartup @Inject constructor(private val monitor: NotificationPermissionMonitor) {
  fun onApplicationCreate(application: Application) {
    NotificationChannels.ensure(application)
    monitor.refresh()
    application.registerActivityLifecycleCallbacks(RefreshOnResume(monitor))
  }

  private class RefreshOnResume(private val monitor: NotificationPermissionMonitor) :
    Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) {
      monitor.refresh()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
  }
}

@Module
@InstallIn(SingletonComponent::class)
interface NotificationsModule {
  @Binds
  fun notifier(impl: AndroidNotifier): Notifier

  @Binds
  fun transferNotifier(impl: AndroidNotifier): TransferNotifier

  @Binds
  fun permissionAsker(impl: AndroidNotificationPermissionAsker): NotificationPermissionAsker
}
