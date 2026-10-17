package com.mapineda48.phonotrainer.core.player.decoder

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.VideoCapabilities.PerformancePoint
import android.media.MediaCodecList
import com.mapineda48.phonotrainer.core.domain.api.DecoderCapabilities
import com.mapineda48.phonotrainer.core.domain.model.DecoderInfo
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * One video decoder of the phone, reduced to what the decision of architecture 7.4 and 11.7 needs: its MIME type,
 * whether it is hardware, its largest size, and two checks that tests can fake.
 */
internal class VideoDecoder(
  val name: String,
  val mime: String,
  val hardware: Boolean,
  val maxWidth: Int,
  val maxHeight: Int,
  val maxFps: Double?,
  /** `VideoCapabilities.areSizeAndRateSupported(w, h, fps)`. */
  private val sizeAndRate: (width: Int, height: Int, fps: Double) -> Boolean,
  /** Whether a listed performance point covers the size and rate; null when the decoder lists none. */
  private val performance: ((width: Int, height: Int, fps: Double) -> Boolean)? = null,
) {
  /** Size and rate are supported, and, when the decoder lists performance points, one of them covers them. */
  fun supports(width: Int, height: Int, fps: Double): Boolean =
    sizeAndRate(width, height, fps) && (performance?.invoke(width, height, fps) ?: true)
}

/** Where the decoders come from: [MediaCodecList] on the phone, a fixed list in tests. */
internal fun interface VideoDecoderSource {
  fun decoders(): List<VideoDecoder>
}

/**
 * The phone's video decoders from `MediaCodecList(REGULAR_CODECS)`: decoders only, aliases left out (an alias is
 * another name for a decoder already listed).
 */
internal class PlatformVideoDecoders @Inject constructor() : VideoDecoderSource {
  override fun decoders(): List<VideoDecoder> =
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
      .filter { !it.isEncoder && !it.isAlias }
      .flatMap { info -> info.supportedTypes.filter { it.startsWith("video/") }.mapNotNull { decoder(info, it) } }

  private fun decoder(info: MediaCodecInfo, type: String): VideoDecoder? {
    val video = runCatching { info.getCapabilitiesForType(type).videoCapabilities }.getOrNull() ?: return null
    val points = video.supportedPerformancePoints?.takeIf { it.isNotEmpty() }
    return VideoDecoder(
      name = info.name,
      mime = type.lowercase(Locale.ROOT),
      hardware = info.isHardwareAccelerated,
      maxWidth = video.supportedWidths.upper,
      maxHeight = video.supportedHeights.upper,
      maxFps = video.supportedFrameRates.upper.toDouble(),
      sizeAndRate = { w, h, fps -> video.areSizeAndRateSupported(w, h, fps) },
      performance = points?.let { listed ->
        { w, h, fps -> listed.any { it.covers(PerformancePoint(w, h, ceil(fps).toInt())) } }
      },
    )
  }
}

/**
 * Which videos this phone can play (architecture 7.4, 11.7; Android review L2). A video of a codec, stored size and
 * rate can play when it is 1080p or smaller and a decoder of that codec supports the size and rate in both
 * orientations (a portrait video is checked as stored and rotated), its listed performance points included:
 * `isSizeSupported` alone says nothing about speed, and software AV1 decoders advertise 4K. The sync engine asks
 * before downloading a video (WP-08) and the player before preparing one; when the answer is no, the clip plays its
 * audio with "This video can't play on this phone. Listening still works." (spec 14.4).
 *
 * [summary] is the short decoder list of Connection details and device QA (NFR-14): one line per codec the computer
 * sends, from the preferred decoder: hardware first, then the largest size.
 */
@Singleton
internal class MediaCodecDecoderCapabilities @Inject constructor(
  private val source: VideoDecoderSource,
) : DecoderCapabilities {
  private val decoders: List<VideoDecoder> by lazy { source.decoders() }

  override fun canPlay(codec: String, width: Int, height: Int, fps: Double): Boolean {
    val mime = mimeOf(codec) ?: return false
    if (width <= 0 || height <= 0 || max(width, height) > MAX_LONG_SIDE || min(width, height) > MAX_SHORT_SIDE) {
      return false
    }
    val rate = if (fps > 0 && fps.isFinite()) fps else DEFAULT_FPS
    return decoders.any { it.mime == mime && it.supports(width, height, rate) && it.supports(height, width, rate) }
  }

  override fun summary(): List<DecoderInfo> = SUMMARY_ORDER.mapNotNull { mime ->
    decoders.filter { it.mime == mime }
      .sortedWith(compareByDescending<VideoDecoder> { it.hardware }.thenByDescending { it.maxWidth.toLong() * it.maxHeight })
      .firstOrNull()
      ?.let { DecoderInfo(it.mime, it.maxWidth, it.maxHeight, it.maxFps, it.hardware) }
  }

  companion object {
    private const val VIDEO = "video/"
    const val AV1: String = "video/av01"
    const val H264: String = "video/avc"
    const val VP9: String = "video/x-vnd.on2.vp9"
    const val HEVC: String = "video/hevc"
    const val VP8: String = "video/x-vnd.on2.vp8"

    /** "1080p or smaller" (architecture 7.4), in either orientation. */
    private const val MAX_LONG_SIDE = 1920
    private const val MAX_SHORT_SIDE = 1080
    private const val DEFAULT_FPS = 30.0

    /** The codecs a computer sends (server-contract 6.3 `media.codec`), in the order Connection details lists them. */
    private val SUMMARY_ORDER = listOf(AV1, H264, VP9, HEVC, VP8)

    /** The listing's `codec` (ffprobe `codec_name`) or a MIME type, as a MIME type; null when unknown. */
    fun mimeOf(codec: String): String? {
      val name = codec.trim().lowercase(Locale.ROOT)
      if (name.startsWith(VIDEO)) return name
      return when (name) {
        "av1", "av01" -> AV1
        "h264", "avc", "avc1" -> H264
        "vp9", "vp09" -> VP9
        "hevc", "h265", "hvc1", "hev1" -> HEVC
        "vp8" -> VP8
        else -> null
      }
    }
  }
}
