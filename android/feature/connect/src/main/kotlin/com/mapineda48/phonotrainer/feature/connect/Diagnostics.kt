package com.mapineda48.phonotrainer.feature.connect

import android.content.res.Resources
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.DecoderInfo
import com.mapineda48.phonotrainer.core.ui.component.ConnectionStatusCardCopy
import com.mapineda48.phonotrainer.core.ui.component.ProbeOutcomeCopy

/** A decoder in words: "AV1 up to 3840 × 2160 · hardware" (architecture 11.7). */
internal object DecoderWords {
  /** The codec's everyday name for its MIME type (as Android names them); an unknown type is shown as it is. */
  fun codec(mime: String): String = when (mime.lowercase()) {
    "video/av01" -> "AV1"
    "video/avc" -> "H.264"
    "video/hevc" -> "HEVC"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/x-vnd.on2.vp8" -> "VP8"
    else -> mime
  }

  fun of(decoder: DecoderInfo, resources: Resources): String = resources.getString(
    R.string.details_decoder,
    codec(decoder.mime),
    decoder.maxWidth,
    decoder.maxHeight,
    resources.getString(if (decoder.hardware) R.string.details_decoder_hardware else R.string.details_decoder_software),
  )
}

/**
 * The text of "Copy diagnostics" (spec F-22, F-22-AC4; architecture 20.3, security review L-7). It is likely to be pasted
 * into a public issue, so it holds words only: the status without the computer's name, the last probe in words, the
 * class of the computer's address (never the address), whether the address changed, the API version, the pending
 * counts, the background limit, the decoders, and the app and Android versions. Never this phone's address, never a
 * server or device id, never the credential, never a name.
 */
internal object DiagnosticsText {
  fun build(state: DetailsUiState, platform: PlatformInfo, resources: Resources): String {
    val d = state.diagnostics
    val now = state.now
    val lines = buildList {
      add(resources.getString(R.string.diag_heading))
      platform.appVersion?.let { add(resources.getString(R.string.diag_app, it)) }
      add(resources.getString(R.string.diag_android, platform.androidRelease, platform.sdk))
      add(resources.getString(R.string.diag_status, resources.getString(statusWords(state.status))))
      val probe = d.lastProbe
      add(
        if (probe == null) {
          resources.getString(R.string.diag_last_probe_never)
        } else {
          val words = ProbeOutcomeCopy.of(probe, resources)
          val at = d.lastProbeAt
          val ago = if (at != null && now != null) ConnectionStatusCardCopy.ago(at, now, resources) else resources.getString(R.string.diag_unknown)
          resources.getString(R.string.diag_last_probe, words, ago)
        },
      )
      add(
        d.address?.let { resources.getString(R.string.diag_address, resources.getString(AddressClass.of(it.host).words)) }
          ?: resources.getString(R.string.diag_address_none),
      )
      val changed = d.addressChangedAt
      add(
        if (changed != null && now != null) {
          resources.getString(R.string.diag_address_changed, ConnectionStatusCardCopy.ago(changed, now, resources))
        } else {
          resources.getString(R.string.diag_address_unchanged)
        },
      )
      add(resources.getString(R.string.diag_api, d.apiVersion?.toString() ?: resources.getString(R.string.diag_unknown)))
      add(resources.getString(R.string.diag_counts, d.waitingRequests, d.requestsOnComputer, d.wantedDownloads))
      add(
        resources.getString(
          R.string.diag_background,
          resources.getString(if (d.backgroundRestricted) R.string.diag_background_limited else R.string.diag_background_free),
        ),
      )
      val decoders = state.decoders.orEmpty()
      add(
        if (decoders.isEmpty()) {
          resources.getString(R.string.diag_decoders_none)
        } else {
          resources.getString(R.string.diag_decoders, decoders.joinToString("; ") { DecoderWords.of(it, resources) })
        },
      )
    }
    return lines.joinToString("\n")
  }

  /** The status without the computer's name (a name can be personal; the operator may have named the computer). */
  fun statusWords(status: ComputerStatus): Int = when (status) {
    ComputerStatus.NotConnected -> R.string.diag_status_not_connected
    ComputerStatus.Offline -> R.string.diag_status_offline
    ComputerStatus.NotOnWifi -> R.string.diag_status_not_on_wifi
    ComputerStatus.Looking -> R.string.diag_status_looking
    ComputerStatus.Checking -> R.string.diag_status_checking
    is ComputerStatus.Connected -> R.string.diag_status_connected
    is ComputerStatus.NotReachable -> R.string.diag_status_not_reachable
    is ComputerStatus.DifferentComputer -> R.string.diag_status_different
    ComputerStatus.ConnectAgain -> R.string.diag_status_connect_again
    ComputerStatus.ComputerTooOld -> R.string.diag_status_computer_too_old
    ComputerStatus.AppTooOld -> R.string.diag_status_app_too_old
  }
}
