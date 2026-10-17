package com.mapineda48.phonotrainer.core.ui.component

import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.CardVariant
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.NoticeKind
import com.mapineda48.phonotrainer.core.designsystem.component.PaperCard
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoProgressBar
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.RouteLine
import com.mapineda48.phonotrainer.core.designsystem.component.RouteNode
import com.mapineda48.phonotrainer.core.designsystem.component.Stepper
import com.mapineda48.phonotrainer.core.designsystem.component.StepState
import com.mapineda48.phonotrainer.core.designsystem.component.StepperStep
import com.mapineda48.phonotrainer.core.designsystem.component.focusRing
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.remote.Capabilities
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.R
import java.time.Instant

/**
 * What a request card shows (design 9.19); [compact] is the Library's and Home's mini-row with its inline primary
 * action (spec F-04 item 1). The optional fields are additive to the frozen API (work-plan 4.5) and default to "not
 * known": [status] (the moment's `ComputerStatus`, for the waiting reason of D-61, "Last known: …" and "…when
 * connected"), [capabilities] (the paired computer's, for the link limit and the upload limit of the failure copy) and
 * [now] (the time ages are counted from; the system clock when null).
 */
@Immutable
data class RequestRowState(
  val request: Request,
  val compact: Boolean = false,
  val status: ComputerStatus? = null,
  val capabilities: Capabilities? = null,
  val now: Instant? = null,
)

/** Test tags of the request card (`ui.<component>.<element>`, work-plan 2.6). */
object RequestRowTags {
  const val OPEN = "ui.request.open"
  const val STATE = "ui.request.state"
  const val SOURCE = "ui.request.source"
  const val BODY = "ui.request.body"
  const val REASON = "ui.request.reason"
  const val PROGRESS = "ui.request.progress"
  const val ROUTE = "ui.request.route"
  const val STEPPER = "ui.request.stepper"
  const val WATCH = "ui.request.watch"

  /** The button of [action]: "ui.request.action.SEND_NOW". */
  fun action(action: RequestAction): String = "ui.request.action.${action.name}"
}

/** The label of a request action, the same words everywhere it appears (frozen with `RequestRow`, wave-1 review 9). */
@StringRes
fun RequestAction.labelRes(): Int = when (this) {
  RequestAction.CANCEL_PREPARING -> R.string.request_action_cancel_preparing
  RequestAction.SEND_NOW -> R.string.request_action_send_now
  RequestAction.STOP_SENDING -> R.string.request_action_stop_sending
  RequestAction.REMOVE -> R.string.request_action_remove
  RequestAction.CANCEL -> R.string.request_action_cancel
  RequestAction.UNDO_CANCEL -> R.string.request_action_undo_cancel
  RequestAction.TRY_AGAIN -> R.string.request_action_try_again
  RequestAction.TRY_AUDIO_ONLY -> R.string.request_action_try_audio_only
  RequestAction.OPEN_LINK -> R.string.request_action_open_link
  RequestAction.SHOW_DETAILS -> R.string.request_action_show_details
  RequestAction.CONNECT_AGAIN -> R.string.request_action_connect_again
  RequestAction.WHY -> R.string.request_action_why
}

/**
 * A request card (design 9.19, render `17`; WP-22, API frozen at M2, work-plan 4.5): the state line (icon + words), the
 * source line, the sentence and reason lines, the bar, the route line or the stepper, and the actions grid (two
 * columns, one at large text). A failure is the alert card: the 4 dp ink bar, circle-alert in `critical`, the title
 * and body of its spec 8.6 row ([RequestFailureCopy]) and "Remove" as the danger button. The card shows only what
 * `Request.actions` holds (architecture 8.4, the one authority); [onAction] carries out one, [onOpen] opens the
 * request (or its clip), and [onWatch] is "Watch" on a request that is on this phone ([onOpen] when null).
 *
 * [RequestRowState.compact] draws the mini-row of the Library's "In progress" and Home: the state icon, the title, the
 * state words and the inline primary action.
 */
@Composable
fun RequestRow(
  state: RequestRowState,
  onAction: (RequestAction) -> Unit,
  onOpen: () -> Unit,
  modifier: Modifier = Modifier,
  onWatch: (() -> Unit)? = null,
) {
  val resources = LocalResources.current
  val fallbackNow = remember { Instant.now() }
  val words = remember(state, resources) { RequestRowCopy.words(state, state.now ?: fallbackNow, resources) }
  if (state.compact) {
    CompactRow(state, words, onAction, onOpen, modifier)
  } else {
    FullCard(state, words, onAction, onOpen, onWatch ?: onOpen, modifier)
  }
}

@Composable
private fun FullCard(
  state: RequestRowState,
  words: RequestRowWords,
  onAction: (RequestAction) -> Unit,
  onOpen: () -> Unit,
  onWatch: () -> Unit,
  modifier: Modifier,
) {
  val request = state.request
  val colors = PhonoTheme.colors
  val resources = LocalResources.current
  PaperCard(
    modifier = modifier.testTag(RequestRowTags.OPEN),
    variant = if (words.alert) CardVariant.ALERT else CardVariant.PLAIN,
    onClick = onOpen,
    onClickLabel = stringResource(R.string.request_open_label),
  ) {
    RequestStateLine(words, Modifier.testTag(RequestRowTags.STATE))
    IconLine(words.sourceIcon, words.source, Modifier.testTag(RequestRowTags.SOURCE))
    words.body?.let {
      PhonoText(it, style = MaterialTheme.typography.bodyLarge, color = colors.ink, modifier = Modifier.testTag(RequestRowTags.BODY))
    }
    words.reasons.forEach { reason -> IconLine(reason.icon, reason.text, Modifier.testTag(RequestRowTags.REASON)) }
    words.progress?.let { PhonoProgressBar(progress = it, modifier = Modifier.testTag(RequestRowTags.PROGRESS)) }
    when (words.visual) {
      RequestVisual.ROUTE -> RouteLine(
        nodes = listOf(
          RouteNode(stringResource(R.string.request_route_phone), PhonoIcons.AvailOnPhone),
          RouteNode(stringResource(R.string.request_route_computer), PhonoIcons.AvailOnComputer),
          RouteNode(stringResource(R.string.request_route_ready), PhonoIcons.TabWatch),
        ),
        current = 0,
        description = stringResource(R.string.request_route_description),
        modifier = Modifier.padding(vertical = PhonoSpacing.s4).testTag(RequestRowTags.ROUTE),
      )
      RequestVisual.STEPPER -> {
        val stages = RequestRowStages.of(request, resources)
        val current = stages.firstOrNull { it.state == StepState.CURRENT }
        Stepper(
          steps = stages.map { StepperStep(stringResource(RequestRowStages.shortName(it.stage)), it.state) },
          description = RequestRowStages.description(stages, resources),
          modifier = Modifier.padding(vertical = PhonoSpacing.s4).testTag(RequestRowTags.STEPPER),
        )
        current?.let {
          Notice(
            body = stringResource(RequestRowStages.teaching(it.stage)),
            kind = NoticeKind.TIP,
            lead = RequestRowStages.teachingLead(stages, resources),
          )
        }
      }
      RequestVisual.NONE -> Unit
    }
    RequestActionGrid(
      state = state,
      actions = words.actions,
      alert = words.alert,
      onAction = onAction,
      onWatch = onWatch.takeIf { request.state == RequestState.ON_PHONE },
    )
  }
}

/**
 * The actions of a request card (design 9.19): "Watch" first for a request on this phone ([onWatch]), then [actions]
 * (`Request.actions`, in the card's order) as 48 dp buttons, two columns, one at large text. Each label is
 * [RequestRowCopy.label] ("…when connected" while the computer is out of reach); a failure's ([alert]) first action is
 * the primary and its "Remove" the danger outline. The request detail draws the same grid under its status.
 */
@Composable
fun RequestActionGrid(
  state: RequestRowState,
  actions: List<RequestAction>,
  alert: Boolean,
  onAction: (RequestAction) -> Unit,
  modifier: Modifier = Modifier,
  onWatch: (() -> Unit)? = null,
) {
  if (actions.isEmpty() && onWatch == null) return
  val request = state.request
  ActionGrid(
    modifier = modifier,
    buttons = buildList {
      onWatch?.let { add(GridButton(stringResource(R.string.request_watch), PhonoIcons.Play, PhonoButtonKind.PRIMARY, RequestRowTags.WATCH, it)) }
      actions.forEachIndexed { index, action ->
        add(
          GridButton(
            text = stringResource(RequestRowCopy.label(action, request, state.status)),
            icon = RequestRowCopy.icon(action),
            kind = emphasis(action, index, alert),
            tag = RequestRowTags.action(action),
            onClick = { onAction(action) },
          ),
        )
      }
    },
  )
}

/** One button of the actions grid. */
private class GridButton(val text: String, val icon: Int, val kind: PhonoButtonKind, val tag: String, val onClick: () -> Unit)

/** The actions grid of design 9.19: two columns of 48 dp buttons; one column at large text, so labels never clip. */
@Composable
private fun ActionGrid(buttons: List<GridButton>, modifier: Modifier = Modifier) {
  val columns = if (LocalDensity.current.fontScale >= LARGE_TEXT) 1 else 2
  Column(
    modifier = modifier.fillMaxWidth().padding(top = PhonoSpacing.s4),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
  ) {
    buttons.chunked(columns).forEach { row ->
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
        row.forEach { button ->
          Box(Modifier.weight(1f)) {
            PhonoButton(
              text = button.text,
              onClick = button.onClick,
              kind = button.kind,
              leadingIcon = button.icon,
              fillWidth = true,
              modifier = Modifier.testTag(button.tag),
            )
          }
        }
        repeat(columns - row.size) { Box(Modifier.weight(1f)) }
      }
    }
  }
}

/**
 * The emphasis of a card's button (design 9.19, render `17`): a failure's first action is the primary and its "Remove"
 * the danger outline; otherwise "Cancel", "Remove" and "Why?" are quiet and the rest have the ring.
 */
private fun emphasis(action: RequestAction, index: Int, alert: Boolean): PhonoButtonKind = when {
  alert && action == RequestAction.REMOVE -> PhonoButtonKind.DANGER
  alert && index == 0 -> PhonoButtonKind.PRIMARY
  alert -> PhonoButtonKind.SECONDARY
  action == RequestAction.CANCEL || action == RequestAction.REMOVE || action == RequestAction.WHY ||
    action == RequestAction.CANCEL_PREPARING -> PhonoButtonKind.QUIET
  else -> PhonoButtonKind.SECONDARY
}

/**
 * The state line of a card (design 9.19): the state's icon (circle-alert in `critical` for a failure; the loader turns
 * while motion is allowed) and the words in titleLarge 800. Not a semantics boundary of its own, so the card's one
 * TalkBack node reads it with the rest of the card's words.
 */
@Composable
fun RequestStateLine(words: RequestRowWords, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  val spins = words.stateIcon == PhonoIcons.StatusAnalyzing && !PhonoTheme.motion.reduced
  val angle = if (spins) {
    val turn by rememberInfiniteTransition(label = "request").animateFloat(
      0f, FULL_TURN, infiniteRepeatable(tween(TURN_MS, easing = LinearEasing), RepeatMode.Restart), label = "turn",
    )
    turn
  } else {
    0f
  }
  Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
    Icon(
      painterResource(words.stateIcon),
      null,
      tint = if (words.stateIcon == PhonoIcons.StatusAttention) colors.critical else colors.ink,
      modifier = Modifier.padding(top = 2.dp).size(PhonoSizes.iconBar).rotate(angle),
    )
    PhonoText(words.state, style = PhonoTheme.type.requestState, color = colors.ink)
  }
}

private const val FULL_TURN = 360f
private const val TURN_MS = 1_000

/**
 * An icon line of the card: the 16 dp icon and bodyMedium ink2 words (the source line, the reason lines); not a
 * semantics boundary, so the card reads as one node.
 */
@Composable
private fun IconLine(icon: Int, text: String, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
  ) {
    Icon(painterResource(icon), null, tint = colors.ink, modifier = Modifier.padding(top = 2.dp).size(PhonoSizes.iconInline))
    PhonoText(text, style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
  }
}

/**
 * The mini-row (Library "In progress", Home; design 10.2 P-01): the state icon, the title, the state words and the
 * inline primary action; the row opens the request. At large text the action goes under the words.
 */
@Composable
private fun CompactRow(
  state: RequestRowState,
  words: RequestRowWords,
  onAction: (RequestAction) -> Unit,
  onOpen: () -> Unit,
  modifier: Modifier,
) {
  val request = state.request
  val colors = PhonoTheme.colors
  val primary = words.primary
  val stacked = LocalDensity.current.fontScale >= LARGE_TEXT
  val text: @Composable (Modifier) -> Unit = { textModifier ->
    Row(textModifier, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10)) {
      Icon(
        painterResource(words.stateIcon),
        null,
        tint = if (words.stateIcon == PhonoIcons.StatusAttention) colors.critical else colors.ink,
        modifier = Modifier.padding(top = 2.dp).size(PhonoSizes.iconButton),
      )
      Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s2)) {
        PhonoText(
          request.title,
          style = MaterialTheme.typography.titleMedium,
          color = colors.ink,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        PhonoText(words.state, style = MaterialTheme.typography.bodyMedium, color = colors.ink2, modifier = Modifier.testTag(RequestRowTags.STATE))
      }
    }
  }
  val button: @Composable () -> Unit = {
    primary?.let { action ->
      PhonoButton(
        text = stringResource(RequestRowCopy.label(action, request, state.status)),
        onClick = { onAction(action) },
        kind = if (words.alert && action == RequestAction.REMOVE) PhonoButtonKind.DANGER else PhonoButtonKind.SECONDARY,
        leadingIcon = RequestRowCopy.icon(action),
        modifier = Modifier.testTag(RequestRowTags.action(action)),
      )
    }
  }
  val open = Modifier
    .focusRing()
    .clickable(onClickLabel = stringResource(R.string.request_open_label), role = Role.Button, onClick = onOpen)
    .testTag(RequestRowTags.OPEN)
  if (stacked) {
    Column(
      modifier = modifier.fillMaxWidth().padding(vertical = PhonoSpacing.s6),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
    ) {
      text(open.fillMaxWidth().heightIn(min = PhonoSizes.minTarget).padding(vertical = PhonoSpacing.s4))
      button()
    }
  } else {
    Row(
      modifier = modifier.fillMaxWidth().padding(vertical = PhonoSpacing.s6),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
    ) {
      text(open.weight(1f).heightIn(min = PhonoSizes.minTarget).padding(vertical = PhonoSpacing.s4))
      button()
    }
  }
}

/** From this font scale the grid is one column and the mini-row stacks its action (design 11, large text). */
private const val LARGE_TEXT = 1.5f

@PreviewLightDark
@Composable
private fun RequestRowPreview() {
  PhonoPreviewSurface {
    RequestRowSamples.all.forEach { (_, request) ->
      RequestRow(
        RequestRowState(request, status = RequestRowSamples.connected, capabilities = RequestRowSamples.capabilities, now = RequestRowSamples.now),
        onAction = {},
        onOpen = {},
      )
    }
  }
}

@PreviewLightDark
@Composable
private fun RequestRowOfflinePreview() {
  PhonoPreviewSurface {
    listOf(RequestRowSamples.waiting, RequestRowSamples.analyzing, RequestRowSamples.downloadForbidden).forEach { request ->
      RequestRow(
        RequestRowState(request, status = RequestRowSamples.unreachable, capabilities = null, now = RequestRowSamples.now),
        onAction = {},
        onOpen = {},
      )
    }
  }
}

@PreviewLightDark
@Composable
private fun RequestRowCompactPreview() {
  PhonoPreviewSurface {
    listOf(RequestRowSamples.downloadForbidden, RequestRowSamples.waiting, RequestRowSamples.analyzing, RequestRowSamples.getting).forEach { request ->
      RequestRow(
        RequestRowState(request, compact = true, status = RequestRowSamples.connected, now = RequestRowSamples.now),
        onAction = {},
        onOpen = {},
      )
    }
  }
}
