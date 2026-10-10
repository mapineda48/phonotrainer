package com.mapineda48.phonotrainer.core.notifications

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import com.mapineda48.phonotrainer.core.domain.api.Notifier
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.NotificationPermission
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.TransferKind
import com.mapineda48.phonotrainer.core.domain.model.TransferProgress
import com.mapineda48.phonotrainer.core.notifications.android.TransferNotifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.mapineda48.phonotrainer.core.designsystem.R as DesignR

// Working stubs of the contract freeze (work-plan 2.3): nothing is posted. M3 brings the channels, the builders and
// the permission state (architecture 13).

class StubNotifier @Inject constructor() : Notifier {
  override fun clipReady(clip: JobId, title: String, shorts: Int) = Unit

  override fun needsAttention(request: RequestId, title: String, site: Site?, error: RequestError) = Unit

  override val permissionState: StateFlow<NotificationPermission> = MutableStateFlow(NotificationPermission.NOT_ASKED)
}

/** Builds a bare ongoing notification; M3 gives it its channel, text and public version. */
class StubTransferNotifier @Inject constructor(@param:ApplicationContext private val context: Context) : TransferNotifier {
  override fun transferNotification(kind: TransferKind, progress: TransferProgress): Notification =
    NotificationCompat.Builder(context, TRANSFERS_CHANNEL)
      .setSmallIcon(DesignR.drawable.ic_stat_phono)
      .setOngoing(true)
      .setSilent(true)
      .build()

  private companion object {
    const val TRANSFERS_CHANNEL = "transfers"
  }
}

@Module
@InstallIn(SingletonComponent::class)
interface NotificationsModule {
  @Binds
  fun notifier(impl: StubNotifier): Notifier

  @Binds
  fun transferNotifier(impl: StubTransferNotifier): TransferNotifier
}
