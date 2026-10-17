package com.mapineda48.phonotrainer.core.player.decoder

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.DecoderInfo
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList

/** Architecture 7.4, 11.7, Android review L2: size and rate, rotated sizes, performance points, hardware preferred. */
@RunWith(RobolectricTestRunner::class)
class DecoderCapabilitiesTest {
  /** A decoder that supports up to [maxW] × [maxH] (as stored, not rotated) at up to [maxFps]. */
  private fun decoder(
    mime: String,
    maxW: Int,
    maxH: Int,
    maxFps: Double = 60.0,
    hardware: Boolean = false,
    name: String = "$mime.${if (hardware) "hw" else "sw"}",
    performance: ((Int, Int, Double) -> Boolean)? = null,
  ) = VideoDecoder(
    name = name,
    mime = mime,
    hardware = hardware,
    maxWidth = maxW,
    maxHeight = maxH,
    maxFps = maxFps,
    sizeAndRate = { w, h, fps -> w <= maxW && h <= maxH && fps <= maxFps },
    performance = performance,
  )

  private fun capabilities(vararg decoders: VideoDecoder) = MediaCodecDecoderCapabilities { decoders.toList() }

  @After
  fun tearDown() = ShadowMediaCodecList.reset()

  @Test
  fun sizeAndRateBothCount() {
    val caps = capabilities(decoder(H264, 1920, 1920, maxFps = 30.0))

    assertThat(caps.canPlay("h264", 1280, 720, 30.0)).isTrue()
    assertThat(caps.canPlay("h264", 1280, 720, 60.0)).isFalse()
    assertThat(caps.canPlay("h264", 1920, 1080)).isTrue()
  }

  @Test
  fun aVideoIsCheckedAsStoredAndRotated() {
    // A decoder whose height limit is below the long side cannot play the video rotated, so it does not qualify.
    val landscapeOnly = capabilities(decoder(H264, 1920, 1088))
    val both = capabilities(decoder(H264, 1920, 1920))

    assertThat(landscapeOnly.canPlay("h264", 1920, 1080)).isFalse()
    assertThat(landscapeOnly.canPlay("h264", 1080, 1920)).isFalse()
    assertThat(both.canPlay("h264", 1080, 1920)).isTrue()
  }

  @Test
  fun listedPerformancePointsDecide() {
    // A software AV1 decoder advertising 4K whose performance points stop at 720p30.
    val av1 = decoder(AV1, 3840, 3840, maxFps = 120.0) { w, h, fps -> w.toLong() * h <= 1280L * 720 && fps <= 30 }
    val caps = capabilities(av1)

    assertThat(caps.canPlay("av1", 1280, 720, 30.0)).isTrue()
    assertThat(caps.canPlay("av1", 1920, 1080, 30.0)).isFalse()
    assertThat(caps.canPlay("av1", 1280, 720, 60.0)).isFalse()
  }

  @Test
  fun onlyTenEightyPOrSmaller() {
    val caps = capabilities(decoder(H264, 4096, 4096, hardware = true))

    assertThat(caps.canPlay("h264", 1920, 1080)).isTrue()
    assertThat(caps.canPlay("h264", 2560, 1440)).isFalse()
    assertThat(caps.canPlay("h264", 1440, 2560)).isFalse()
  }

  @Test
  fun codecNamesAndMimeTypesAreBothUnderstood() {
    val caps = capabilities(decoder(VP9, 1920, 1920), decoder(HEVC, 1920, 1920), decoder(VP8, 1920, 1920))

    assertThat(caps.canPlay("vp9", 1280, 720)).isTrue()
    assertThat(caps.canPlay("hevc", 1280, 720)).isTrue()
    assertThat(caps.canPlay("vp8", 1280, 720)).isTrue()
    assertThat(caps.canPlay("video/x-vnd.on2.vp9", 1280, 720)).isTrue()
    assertThat(caps.canPlay("av1", 1280, 720)).isFalse()
    assertThat(caps.canPlay("mpeg4", 1280, 720)).isFalse()
    assertThat(caps.canPlay("h264", 0, 720)).isFalse()
  }

  @Test
  fun anyQualifyingDecoderOfTheCodecIsEnough() {
    val caps = capabilities(
      decoder(H264, 1280, 1280, hardware = true),
      decoder(H264, 1920, 1920, hardware = false),
    )

    assertThat(caps.canPlay("h264", 1920, 1080)).isTrue()
  }

  @Test
  fun theSummaryListsThePreferredDecoderOfEachCodecHardwareFirst() {
    val caps = capabilities(
      decoder(H264, 4096, 4096, hardware = false, name = "c2.android.avc.decoder"),
      decoder(H264, 1920, 1920, maxFps = 60.0, hardware = true, name = "c2.vendor.avc.decoder"),
      decoder(AV1, 2048, 2048, maxFps = 30.0, hardware = false, name = "c2.android.av1.decoder"),
      decoder(AV1, 3840, 3840, maxFps = 30.0, hardware = false, name = "c2.dav1d.decoder"),
      decoder(VP8, 1920, 1920, hardware = false),
    )

    assertThat(caps.summary()).containsExactly(
      DecoderInfo(AV1, 3840, 3840, 30.0, false),
      DecoderInfo(H264, 1920, 1920, 60.0, true),
      DecoderInfo(VP8, 1920, 1920, 60.0, false),
    ).inOrder()
  }

  @Test
  fun thePlatformListGivesDecodersOnlyNeverEncoders() {
    ShadowMediaCodecList.addCodec(codec("c2.test.avc.decoder", MediaFormat.MIMETYPE_VIDEO_AVC, encoder = false, hardware = true))
    ShadowMediaCodecList.addCodec(codec("c2.test.avc.encoder", MediaFormat.MIMETYPE_VIDEO_AVC, encoder = true, hardware = true))
    ShadowMediaCodecList.addCodec(codec("c2.test.aac.decoder", MediaFormat.MIMETYPE_AUDIO_AAC, encoder = false, hardware = false))

    val decoders = PlatformVideoDecoders().decoders()

    assertThat(decoders.map { it.name to it.mime }).containsExactly("c2.test.avc.decoder" to H264)
    assertThat(decoders.single().hardware).isTrue()
  }

  private fun codec(name: String, mime: String, encoder: Boolean, hardware: Boolean): android.media.MediaCodecInfo {
    val video = mime.startsWith("video/")
    val capabilities = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
      .setMediaFormat(if (video) MediaFormat.createVideoFormat(mime, 1920, 1080) else MediaFormat.createAudioFormat(mime, 44_100, 2))
      .setIsEncoder(encoder)
    if (video) {
      capabilities
        .setProfileLevels(arrayOf(CodecProfileLevel().apply { profile = CodecProfileLevel.AVCProfileHigh; level = CodecProfileLevel.AVCLevel41 }))
        .setColorFormats(intArrayOf(YUV_420_FLEXIBLE))
    }
    return MediaCodecInfoBuilder.newBuilder()
      .setName(name)
      .setIsEncoder(encoder)
      .setIsHardwareAccelerated(hardware)
      .setIsSoftwareOnly(!hardware)
      .setCapabilities(capabilities.build())
      .build()
  }

  private companion object {
    const val AV1 = MediaCodecDecoderCapabilities.AV1
    const val H264 = MediaCodecDecoderCapabilities.H264
    const val VP9 = MediaCodecDecoderCapabilities.VP9
    const val HEVC = MediaCodecDecoderCapabilities.HEVC
    const val VP8 = MediaCodecDecoderCapabilities.VP8
    const val YUV_420_FLEXIBLE = 0x7F420888
  }
}
