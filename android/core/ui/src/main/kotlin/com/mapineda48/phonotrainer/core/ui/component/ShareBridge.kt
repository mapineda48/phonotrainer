package com.mapineda48.phonotrainer.core.ui.component

import android.content.res.Resources
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoMotion
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.trace.StylusTrace
import com.mapineda48.phonotrainer.core.designsystem.trace.StylusTraceStyle
import com.mapineda48.phonotrainer.core.designsystem.trace.TracePath
import com.mapineda48.phonotrainer.core.designsystem.trace.TraceSample
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.ui.R
import kotlin.math.PI
import kotlin.math.sin

/**
 * How the bridge looks (design 9.22): one per row of the sheet's state table, from the computer's status after the
 * sheet's fresh probe.
 */
enum class BridgeLook {
  /** `Checking`, `Looking`, or the sheet's own probe still running (≤ 2 s): dotted, the nib pulsing at the phone. */
  CHECKING,

  /** `Connected`: the solid trace, the nib travelling toward the computer, the computer node with a check. */
  CONNECTED,

  /** `NotReachable`, `Offline`, `NotOnWifi`: dotted, the request parked on the phone, the computer node muted. */
  UNREACHABLE,

  /** `NotConnected`: dotted, the computer node outlined with a question mark. */
  NOT_PAIRED,

  /** The queue-wide pause (architecture 8.4): `ConnectAgain`, `ComputerTooOld`, `AppTooOld`, `DifferentComputer`. */
  ATTENTION,
}

/** What the phone node shows besides the phone (design 9.22). */
@Immutable
sealed interface BridgePhone {
  /** Nothing more. */
  data object Idle : BridgePhone

  /** A file being copied into the app: a bar on the phone node ([fraction] 0 to 1; null while the size is unknown). */
  data class Preparing(val fraction: Float?) : BridgePhone

  /** The copy was refused for space while the sheet is open: the phone node carries circle-alert. */
  data object Alert : BridgePhone
}

/**
 * The phone → computer bridge's state (design 9.22): where the request is going and whether it can go now. [checking]
 * is the sheet's fresh probe, at most 2 s (spec 8.4 item 3); [phone] what happens on this phone (a copy, a refusal).
 */
@Immutable
data class ShareBridgeState(
  val status: ComputerStatus,
  val checking: Boolean = false,
  val phone: BridgePhone = BridgePhone.Idle,
) {
  /** The row of design 9.22 this state draws. */
  val look: BridgeLook
    get() = if (checking) BridgeLook.CHECKING else lookOf(status)

  companion object {
    /** The look of [status] alone. */
    fun lookOf(status: ComputerStatus): BridgeLook = when (status) {
      ComputerStatus.Looking, ComputerStatus.Checking -> BridgeLook.CHECKING
      is ComputerStatus.Connected -> BridgeLook.CONNECTED
      is ComputerStatus.NotReachable, ComputerStatus.Offline, ComputerStatus.NotOnWifi -> BridgeLook.UNREACHABLE
      ComputerStatus.NotConnected -> BridgeLook.NOT_PAIRED
      is ComputerStatus.DifferentComputer, ComputerStatus.ConnectAgain, ComputerStatus.ComputerTooOld,
      ComputerStatus.AppTooOld,
      -> BridgeLook.ATTENTION
    }
  }
}

/** Test tags of the bridge and the connection line (`ui.<component>.<element>`, work-plan 2.6). */
object ShareBridgeTags {
  const val BRIDGE = "ui.bridge.bridge"
  const val LINE = "ui.bridge.line"
}

/** The connection line in words: the bold [lead] and the ink2 [body] (design 9.22). */
@Immutable
data class ConnectionWords(val lead: String, val body: String?) {
  /** Both sentences, as TalkBack reads the line. */
  val spoken: String
    get() = if (body == null) lead else "$lead $body"
}

/**
 * The words of the connection line (spec 8.4 item 3, design 9.22), one mapping for the share sheet and "Add a clip".
 * It takes the [Resources] to read `strings_bridge.xml` from, as the other copy objects of `ui/component/` do.
 */
object ConnectionLineCopy {
  fun of(status: ComputerStatus, checking: Boolean, resources: Resources): ConnectionWords =
    when (if (checking) BridgeLook.CHECKING else ShareBridgeState.lookOf(status)) {
      BridgeLook.CHECKING -> ConnectionWords(resources.getString(R.string.bridge_checking), null)
      BridgeLook.CONNECTED -> {
        val ahead = (status as? ComputerStatus.Connected)?.let { it.analyzing + it.inLine } ?: 0
        ConnectionWords(
          resources.getString(R.string.bridge_connected),
          if (ahead > 0) resources.getQuantityString(R.plurals.bridge_connected_in_line, ahead, ahead) else null,
        )
      }
      BridgeLook.UNREACHABLE -> ConnectionWords(
        resources.getString(R.string.bridge_unreachable),
        resources.getString(R.string.bridge_unreachable_body),
      )
      BridgeLook.NOT_PAIRED -> ConnectionWords(
        resources.getString(R.string.bridge_not_paired),
        resources.getString(R.string.bridge_not_paired_body),
      )
      BridgeLook.ATTENTION -> ConnectionWords(
        resources.getString(R.string.bridge_attention, resources.getString(reasonOf(status))),
        resources.getString(R.string.bridge_attention_body),
      )
    }

  private fun reasonOf(status: ComputerStatus): Int = when (status) {
    ComputerStatus.ConnectAgain -> R.string.bridge_reason_connect_again
    ComputerStatus.ComputerTooOld -> R.string.bridge_reason_computer_too_old
    ComputerStatus.AppTooOld -> R.string.bridge_reason_app_too_old
    else -> R.string.bridge_reason_different_computer
  }

  /** The icon at the start of the line (design 9.22). */
  @DrawableRes
  fun iconOf(status: ComputerStatus, checking: Boolean): Int =
    when (if (checking) BridgeLook.CHECKING else ShareBridgeState.lookOf(status)) {
      BridgeLook.CHECKING -> PhonoIcons.ConnChecking
      BridgeLook.CONNECTED -> PhonoIcons.ConnConnected
      BridgeLook.UNREACHABLE -> PhonoIcons.StatusWaiting
      BridgeLook.NOT_PAIRED -> PhonoIcons.ConnNotConnected
      BridgeLook.ATTENTION -> PhonoIcons.StatusAttention
    }
}

private val NodeShape = RoundedCornerShape(PhonoRadius.medium)
private val NodeSize = 48.dp
private val BadgeSize = 26.dp

/** The gentle wave of the bridge: a hand-drawn line between the two nodes, not a measured contour. */
private val BridgeWave: List<TraceSample> = List(BRIDGE_POINTS) { i ->
  val x = i / (BRIDGE_POINTS - 1).toFloat()
  TraceSample(x, 0.5f - 0.22f * sin(x * 2f * PI.toFloat() * 2.2f) * (1f - 0.35f * x), weight = 0.6f)
}

private const val BRIDGE_POINTS = 40
private const val CONNECTED_REACH = 0.86f
private const val NIB_PULSES = 3

/**
 * The bridge of the share sheet and "Add a clip" (WP-23; API frozen at M2, work-plan 4.5; design 9.22): the phone
 * node (ink fill), the trace and the computer node. Solid with the nib travelling once toward the computer when
 * connected; a dotted pencil line otherwise, with the request parked on the phone (hourglass) when the computer cannot
 * be reached. Under reduced motion every movement is a still frame, and nothing loops (A-07). It is a picture: the
 * connection line beside it carries the words, so the bridge is invisible to accessibility (A-04).
 */
@Composable
fun ShareBridge(state: ShareBridgeState, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  val reduced = PhonoTheme.motion.reduced
  val look = state.look
  Row(
    modifier = modifier
      .fillMaxWidth()
      .testTag(ShareBridgeTags.BRIDGE)
      .clearAndSetSemantics { },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    PhoneNode(state.phone, parked = look == BridgeLook.UNREACHABLE && state.phone == BridgePhone.Idle)
    Box(Modifier.weight(1f).height(NodeSize).padding(horizontal = PhonoSpacing.s4)) {
      val reach = remember { Animatable(if (reduced) CONNECTED_REACH else 0f) }
      LaunchedEffect(look == BridgeLook.CONNECTED, reduced) {
        when {
          look != BridgeLook.CONNECTED -> reach.snapTo(0f)
          reduced -> reach.snapTo(CONNECTED_REACH)
          else -> reach.animateTo(CONNECTED_REACH, tween(PhonoMotion.DRAW_MS, easing = PhonoMotion.Standard))
        }
      }
      StylusTrace(
        samples = BridgeWave,
        progress = if (look == BridgeLook.CONNECTED) reach.value else 0f,
        modifier = Modifier.fillMaxSize(),
        style = StylusTraceStyle.Route,
        showNib = look == BridgeLook.CONNECTED,
        restColor = if (look == BridgeLook.UNREACHABLE) colors.inkMuted else colors.ink2,
      )
      if (look == BridgeLook.CHECKING) CheckingNib(reduced)
    }
    ComputerNode(look, state.status)
  }
}

/** The nib at the phone while the probe runs: three slow pulses (bounded, A-07), still under reduced motion. */
@Composable
private fun CheckingNib(reduced: Boolean) {
  val ink = PhonoTheme.colors.ink
  val pulse = remember { Animatable(1f) }
  LaunchedEffect(reduced) {
    if (reduced) {
      pulse.snapTo(1f)
    } else {
      pulse.animateTo(
        0.35f,
        repeatable(NIB_PULSES * 2 - 1, tween(PhonoMotion.SLOW_MS * 2, easing = PhonoMotion.Standard), RepeatMode.Reverse),
      )
      pulse.snapTo(1f)
    }
  }
  Canvas(Modifier.fillMaxSize()) {
    val pad = StylusTraceStyle.Route.padding.toPx()
    val y = pad + (TracePath.yAt(BridgeWave, 0f) ?: 0.5f) * (size.height - 2 * pad)
    val center = Offset(0f, y)
    drawCircle(ink.copy(alpha = 0.25f * pulse.value), StylusTraceStyle.Route.nibHalo.toPx() / 2f, center)
    drawCircle(ink.copy(alpha = pulse.value), StylusTraceStyle.Route.nibSize.toPx() / 2f, center)
  }
}

@Composable
private fun PhoneNode(phone: BridgePhone, parked: Boolean) {
  val colors = PhonoTheme.colors
  Box(Modifier.size(NodeSize + BadgeSize / 3)) {
    Box(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .size(NodeSize)
        .clip(NodeShape)
        .background(colors.ink),
      contentAlignment = Alignment.Center,
    ) {
      Icon(painterResource(PhonoIcons.AvailOnPhone), null, tint = colors.onInk, modifier = Modifier.size(PhonoSizes.iconBar))
      if (phone is BridgePhone.Preparing) {
        val onInk = colors.onInk
        val fraction = phone.fraction
        Canvas(Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 5.dp).fillMaxWidth().height(4.dp)) {
          val radius = CornerRadius(size.height / 2f)
          drawRoundRect(onInk.copy(alpha = 0.35f), cornerRadius = radius)
          if (fraction != null) {
            drawRoundRect(onInk, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = radius)
          }
        }
      }
    }
    val badge = when {
      phone == BridgePhone.Alert -> PhonoIcons.StatusAttention
      parked -> PhonoIcons.StatusPreparing
      else -> null
    }
    badge?.let { Badge(it, Modifier.align(Alignment.TopEnd)) }
  }
}

@Composable
private fun Badge(@DrawableRes icon: Int, modifier: Modifier) {
  val colors = PhonoTheme.colors
  Box(
    modifier = modifier
      .offset(x = 2.dp)
      .size(BadgeSize)
      .clip(CircleShape)
      .background(colors.sheet)
      .border(PhonoSizes.ring, colors.ink, CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    Icon(painterResource(icon), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconInline))
  }
}

@Composable
private fun ComputerNode(look: BridgeLook, status: ComputerStatus) {
  val colors = PhonoTheme.colors
  val muted = look == BridgeLook.UNREACHABLE
  val icon = when (look) {
    BridgeLook.CHECKING -> PhonoIcons.AvailOnComputer
    BridgeLook.CONNECTED -> PhonoIcons.ConnConnected
    BridgeLook.UNREACHABLE -> PhonoIcons.ConnUnreachable
    BridgeLook.NOT_PAIRED -> PhonoIcons.Help
    BridgeLook.ATTENTION -> when (status) {
      ComputerStatus.ConnectAgain -> PhonoIcons.ConnConnectAgain
      ComputerStatus.ComputerTooOld, ComputerStatus.AppTooOld -> PhonoIcons.ConnUpdate
      else -> PhonoIcons.ConnDifferent
    }
  }
  Box(
    modifier = Modifier
      .size(NodeSize)
      .clip(NodeShape)
      .background(colors.sheet)
      .border(PhonoSizes.ring, if (muted) colors.outline else colors.ink, NodeShape),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      painterResource(icon),
      null,
      tint = if (muted) colors.inkMuted else colors.ink,
      modifier = Modifier.size(PhonoSizes.iconBar),
    )
  }
}

/**
 * The connection line under the bridge, mapped from [status] (design 9.22): the state's icon, the bold sentence and
 * the ink2 one. [checking] is the sheet's own probe (≤ 2 s). The line is one TalkBack node and a polite live region,
 * so the answer that arrives after the probe is announced once.
 */
@Composable
fun ConnectionLine(status: ComputerStatus, modifier: Modifier = Modifier, checking: Boolean = false) {
  val colors = PhonoTheme.colors
  val words = ConnectionLineCopy.of(status, checking, LocalContext.current.resources)
  Row(
    modifier = modifier
      .fillMaxWidth()
      .testTag(ShareBridgeTags.LINE)
      .clearAndSetSemantics {
        contentDescription = words.spoken
        liveRegion = LiveRegionMode.Polite
      },
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
  ) {
    Icon(
      painterResource(ConnectionLineCopy.iconOf(status, checking)),
      null,
      tint = colors.ink,
      modifier = Modifier.padding(top = 2.dp).size(PhonoSizes.iconBar),
    )
    Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s2)) {
      PhonoText(
        words.lead,
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
        color = colors.ink,
      )
      words.body?.let { PhonoText(it, style = MaterialTheme.typography.bodyLarge, color = colors.ink2) }
    }
  }
}

@PreviewLightDark
@Composable
internal fun ShareBridgePreview() {
  PhonoPreviewSurface {
    Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s16)) {
      val previews = listOf(
        ShareBridgeState(ComputerStatus.Checking, checking = true),
        ShareBridgeState(ComputerStatus.Connected("Studio PC", analyzing = 1, inLine = 1, lowOnSpace = false)),
        ShareBridgeState(ComputerStatus.NotReachable("Studio PC", null, false, ProbeOutcome.NoAnswer(3))),
        ShareBridgeState(ComputerStatus.NotConnected),
        ShareBridgeState(ComputerStatus.ConnectAgain),
        ShareBridgeState(ComputerStatus.Offline, phone = BridgePhone.Preparing(0.4f)),
        ShareBridgeState(ComputerStatus.Offline, phone = BridgePhone.Alert),
      )
      previews.forEach { state ->
        ShareBridge(state)
        ConnectionLine(state.status, checking = state.checking)
      }
    }
  }
}
