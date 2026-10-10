package com.mapineda48.phonotrainer.core.domain.model

/**
 * The error model of architecture 19: errors cross module boundaries as these values (or `Result`-like returns), never
 * as raw exceptions; raw exception text is never shown.
 */
sealed interface AppError {
  /** From :core:network, mapped once. */
  sealed interface Computer : AppError {
    data object NotOnWifi : Computer

    data object Offline : Computer

    /** Timeout, refused, no route, reset, VpnBlocks. */
    data class Unreachable(val reason: ProbeOutcome) : Computer

    data object DifferentComputer : Computer

    data object CredentialRejected : Computer

    /** 401 clock_skew: re-sync and retry once. */
    data class ClockSkew(val serverTime: Long) : Computer

    data object PhoneAccessOff : Computer

    data object ComputerTooOld : Computer

    data object AppTooOld : Computer

    /** Stable server codes. */
    data class Refused(val code: String, val detail: String) : Computer

    /** The cleartext guard refused (a defect). */
    data object Guarded : Computer

    data object Malformed : Computer
  }

  sealed interface Storage : AppError {
    data class NoSpace(val neededBytes: Long) : Storage

    data object Io : Storage
  }

  sealed interface Content : AppError {
    data object Corrupt : Content

    data object Missing : Content
  }

  sealed interface Media : AppError {
    data object VideoDecode : Media

    data object AudioMissing : Media
  }

  sealed interface Permission : AppError {
    data object Microphone : Permission

    data object Notifications : Permission
  }

  data class Unexpected(val code: String) : AppError
}
