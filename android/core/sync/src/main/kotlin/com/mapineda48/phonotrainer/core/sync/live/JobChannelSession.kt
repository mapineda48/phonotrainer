package com.mapineda48.phonotrainer.core.sync.live

import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.api.ChannelLiveness
import com.mapineda48.phonotrainer.core.domain.api.SyncTrigger
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.remote.ChannelEvent
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteJob
import com.mapineda48.phonotrainer.core.domain.spi.RequestReconciler
import com.mapineda48.phonotrainer.core.network.api.JobChannel
import com.mapineda48.phonotrainer.core.sync.catalogue.CatalogueReconciler
import com.mapineda48.phonotrainer.core.sync.catalogue.ClipRemoval
import com.mapineda48.phonotrainer.core.sync.catalogue.PairedSource
import com.mapineda48.phonotrainer.core.sync.engine.ChannelState
import com.mapineda48.phonotrainer.core.sync.meta.SyncMeta
import java.io.IOException
import java.time.Duration
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The job channel while the app is in the foreground and connected (architecture 5.5, 7.6): `job` events update the
 * `clip` rows and this phone's requests within their arrival (F-03-AC5), a revision other than the one stored asks for
 * a pass (which fetches the listing with its artifacts), `deleted` events remove clips (7.7). Every event proves the
 * computer alive; a lost channel tells the probe machine and is opened again after 1 s, then up to 10 s. Events are
 * applied as they come (the fake computer does not coalesce them like the real one does, and nothing here relies on
 * it); a channel that falls behind is lost and starts again from its snapshot.
 */
@Singleton
internal class JobChannelSession @Inject constructor(
  private val channel: JobChannel,
  private val liveness: ChannelLiveness,
  private val pairing: PairingStore,
  private val catalogue: CatalogueReconciler,
  private val removal: ClipRemoval,
  private val reconciler: Provider<RequestReconciler>,
  private val trigger: Provider<SyncTrigger>,
  private val meta: SyncMeta,
  private val log: PhonoLog,
) : ChannelState {
  private val opened = MutableStateFlow(false)

  override val open: StateFlow<Boolean> = opened.asStateFlow()

  /** Keeps the channel open until the caller's coroutine is cancelled (the app left the foreground or disconnected). */
  suspend fun run() {
    var wait = FIRST_RETRY
    while (true) {
      try {
        collectOnce()
        // The flow never completes normally while collected; treat a completion as a loss.
        throw IOException("The channel ended.")
      } catch (cancelled: CancellationException) {
        opened.value = false
        throw cancelled
      } catch (lost: IOException) {
        if (opened.value) wait = FIRST_RETRY
        opened.value = false
        liveness.channelLost()
        log.d { "job channel lost: ${lost.message}" }
      } catch (other: OtherComputerOnChannel) {
        opened.value = false
        log.w("sync.channel_other_computer")
        wait = LAST_RETRY
      } catch (failed: RuntimeException) {
        // An event this phone could not apply: the channel starts again from its snapshot.
        opened.value = false
        log.w("sync.channel_event_failed", failed)
      }
      delay(wait.toMillis())
      wait = minOf(wait.multipliedBy(2), LAST_RETRY)
    }
  }

  private suspend fun collectOnce() {
    val paired = pairing.current() ?: throw IOException("No computer is paired.")
    val source = PairedSource(paired.serverId.value, paired.serverName)
    var libraryId: String? = null
    channel.open().collect { event ->
      liveness.channelAlive()
      when (event) {
        is ChannelEvent.Hello -> {
          if (event.serverId != paired.serverId) throw OtherComputerOnChannel()
          libraryId = event.libraryId.value
          opened.value = true
          val listed = meta.lastListing()
          if (listed == null || listed.second != event.libraryId.value) request(SyncReason.JOB_CHANGED)
        }
        is ChannelEvent.Snapshot -> {
          var changed = false
          for (job in event.jobs) changed = apply(job, source, libraryId) || changed
          if (changed) request(SyncReason.JOB_CHANGED)
        }
        is ChannelEvent.Job -> if (apply(event.job, source, libraryId)) request(SyncReason.JOB_CHANGED)
        is ChannelEvent.Deleted -> removal.deletedOnComputer(listOf(event.id.value))
      }
    }
  }

  private suspend fun apply(job: RemoteJob, source: PairedSource, libraryId: String?): Boolean {
    val library = libraryId ?: return true
    val changed = catalogue.applyLean(job, source, library)
    try {
      reconciler.get().onJob(job)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failed: RuntimeException) {
      log.w("sync.request_not_told_of_job", failed)
    }
    return changed
  }

  private fun request(reason: SyncReason) = trigger.get().request(reason)

  /** The channel said hello as another computer: it is not followed (QT-11). */
  private class OtherComputerOnChannel : RuntimeException("Another computer answered on the channel.")

  private companion object {
    val FIRST_RETRY: Duration = Duration.ofSeconds(1)
    val LAST_RETRY: Duration = Duration.ofSeconds(10)
  }
}
