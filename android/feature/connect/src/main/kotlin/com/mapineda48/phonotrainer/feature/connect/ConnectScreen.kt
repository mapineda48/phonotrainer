package com.mapineda48.phonotrainer.feature.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.Illustration
import com.mapineda48.phonotrainer.core.designsystem.component.IconWell
import com.mapineda48.phonotrainer.core.designsystem.component.Kicker
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.NoticeKind
import com.mapineda48.phonotrainer.core.designsystem.component.PaperCard
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoTextField
import com.mapineda48.phonotrainer.core.designsystem.component.focusRing
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.illustration.PhonoIllustrations
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoColors
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.theme.tabular
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind

/** What Connect's controls ask for. */
internal class ConnectActions(
  val onBack: () -> Unit = {},
  val onSearchAgain: () -> Unit = {},
  val onOpenWifi: () -> Unit = {},
  val onEnterAddress: () -> Unit = {},
  val onAddressChange: (String) -> Unit = {},
  val onCheck: () -> Unit = {},
  val onSelect: (String) -> Unit = {},
  val onCodeChange: (String) -> Unit = {},
  val onNameChange: (String) -> Unit = {},
  val onConnect: () -> Unit = {},
  /** First run: "Not now" (F-01 behaviour 2). */
  val onNotNow: () -> Unit = {},
  /** Success: "Go to Home" (F-02 behaviour 6). */
  val onGoHome: () -> Unit = {},
  /** The new address accepted: "Done". */
  val onDone: () -> Unit = {},
  /** "Copy" next to a command: the command's text. */
  val onCopy: (String) -> Unit = {},
)

/**
 * Connect to your computer (spec F-02, design 10.2 P-06, render `21`). From the top: the lede; the instruction card for
 * the computer side with the two commands; the search ("Looking for your computer…", the computers that answered as
 * radio rows, the D-39 note above them when two answers share a name or an identity, "No computer answered." with its
 * checklist, or "Connect to your home Wi-Fi first."); "Enter the address instead" and its field; the code with this
 * phone's name and "Connect"; and, on a first run, "Not now". Once connected the page says so and offers Home.
 */
@Composable
internal fun ConnectScreen(state: ConnectUiState, actions: ConnectActions, modifier: Modifier = Modifier) {
  val title = stringResource(if (state.mode == ConnectMode.NEW_ADDRESS) R.string.connect_new_address_title else R.string.connect_title)
  ConnectPage(title = title, onBack = actions.onBack, listTag = ConnectTags.PAGE, modifier = modifier) {
    val done = state.done
    if (done != null) {
      item(key = "done") { SuccessPanel(done, actions) }
      return@ConnectPage
    }
    item(key = "lede") { Lede(ledeOf(state)) }
    if (state.mode != ConnectMode.NEW_ADDRESS) item(key = "steps") { StepsCard(actions.onCopy) }
    searchItems(state, actions)
    if (state.addressOpen) {
      item(key = "address") { AddressSection(state, actions) }
    } else if (showsAddressLink(state)) {
      item(key = "enterAddress") {
        PhonoButton(
          text = stringResource(R.string.connect_enter_address),
          onClick = actions.onEnterAddress,
          kind = PhonoButtonKind.SECONDARY,
          leadingIcon = PhonoIcons.Address,
          fillWidth = true,
          modifier = Modifier.testTag(ConnectTags.ENTER_ADDRESS),
        )
      }
    }
    state.selected?.let { selected -> item(key = "code") { CodeSection(state, selected, actions) } }
    if (state.mode == ConnectMode.FIRST_RUN) item(key = "notNow") { NotNow(actions.onNotNow) }
  }
}

@Composable
private fun ledeOf(state: ConnectUiState): String = when (state.mode) {
  ConnectMode.CONNECT_AGAIN -> stringResource(R.string.connect_again_lede)
  ConnectMode.NEW_ADDRESS ->
    state.pairedName?.let { stringResource(R.string.connect_new_address_lede, it) }
      ?: stringResource(R.string.connect_new_address_lede_unnamed)
  ConnectMode.FIRST_RUN, ConnectMode.FROM_HOME -> stringResource(R.string.connect_lede)
}

/** The separate "Enter the address instead" button, unless the nothing-found block or the conflict note has one. */
private fun showsAddressLink(state: ConnectUiState): Boolean {
  if (state.mode == ConnectMode.NEW_ADDRESS) return false
  val search = state.search
  return search !is SearchUi.NothingFound && !(search is SearchUi.Found && search.conflict)
}

/** The instruction card for the computer side (F-02 behaviour 2): three numbered steps, the commands with "Copy". */
@Composable
private fun StepsCard(onCopy: (String) -> Unit) {
  PaperCard(modifier = Modifier.fillMaxWidth().testTag(ConnectTags.STEPS)) {
    Kicker(stringResource(R.string.connect_steps_title), icon = PhonoIcons.SiteComputer)
    Step(1, stringResource(R.string.connect_step_1)) {
      CommandWell(stringResource(R.string.connect_command_start), onCopy, ConnectTags.COPY_START)
    }
    Hairline()
    Step(2, stringResource(R.string.connect_step_2)) {
      CommandWell(stringResource(R.string.connect_command_pair), onCopy, ConnectTags.COPY_PAIR)
    }
    Hairline()
    Step(3, stringResource(R.string.connect_step_3))
  }
}

@Composable
private fun Step(number: Int, text: String, extra: (@Composable () -> Unit)? = null) {
  Row(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
    Numeral(number)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
      PhonoText(text, style = MaterialTheme.typography.bodyLarge, color = PhonoTheme.colors.ink)
      extra?.invoke()
    }
  }
}

/** The search's part of the list (spec F-02 behaviour 3 and States). */
private fun LazyListScope.searchItems(state: ConnectUiState, actions: ConnectActions) {
  when (val search = state.search) {
    SearchUi.Idle -> Unit
    SearchUi.Searching -> item(key = "looking") { LookingLine() }
    SearchUi.NotOnWifi -> item(key = "notOnWifi") { NotOnWifi(actions) }
    SearchUi.NothingFound -> item(key = "nothing") { NothingFound(actions) }
    is SearchUi.Found -> {
      if (search.conflict) item(key = "conflict") { ConflictNote(actions.onEnterAddress) }
      item(key = "foundTitle") { GroupTitle(stringResource(R.string.connect_found_title)) }
      state.rows.forEach { row ->
        item(key = "row:${row.key}") {
          ComputerRowItem(row, selected = row.key == state.selectedKey, pairedName = state.pairedName, onSelect = actions.onSelect)
        }
      }
      if (search.stillLooking) {
        item(key = "stillLooking") { LookingLine() }
      } else {
        item(key = "searchAgain") {
          PhonoButton(
            text = stringResource(R.string.connect_search_again),
            onClick = actions.onSearchAgain,
            kind = PhonoButtonKind.QUIET,
            leadingIcon = PhonoIcons.ConnChecking,
            modifier = Modifier.testTag(ConnectTags.SEARCH_AGAIN),
          )
        }
      }
    }
  }
}

/**
 * "Connect to your home Wi-Fi first." (F-02 States "Offline") with "Open Wi-Fi settings" and "Search again", stacked
 * (the notice is measured by its intrinsic height, which a flow row under-reports at large text).
 */
@Composable
private fun NotOnWifi(actions: ConnectActions) {
  Notice(
    body = stringResource(R.string.connect_not_on_wifi),
    kind = NoticeKind.CAUTION,
    live = true,
    modifier = Modifier.testTag(ConnectTags.NOT_ON_WIFI),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
      PhonoButton(
        text = stringResource(R.string.connect_open_wifi),
        onClick = actions.onOpenWifi,
        kind = PhonoButtonKind.SECONDARY,
        leadingIcon = PhonoIcons.Wifi,
        modifier = Modifier.testTag(ConnectTags.OPEN_WIFI),
      )
      PhonoButton(
        text = stringResource(R.string.connect_search_again),
        onClick = actions.onSearchAgain,
        kind = PhonoButtonKind.QUIET,
        modifier = Modifier.testTag(ConnectTags.SEARCH_AGAIN),
      )
    }
  }
}

/** The D-39 note (F-02-AC10): above the list, with "Enter the address instead" inside it. Nothing is preselected. */
@Composable
private fun ConflictNote(onEnterAddress: () -> Unit) {
  Notice(
    body = stringResource(R.string.connect_conflict),
    kind = NoticeKind.CAUTION,
    modifier = Modifier.testTag(ConnectTags.CONFLICT),
  ) {
    PhonoButton(
      text = stringResource(R.string.connect_enter_address),
      onClick = onEnterAddress,
      kind = PhonoButtonKind.SECONDARY,
      leadingIcon = PhonoIcons.Address,
      modifier = Modifier.testTag(ConnectTags.CONFLICT_ADDRESS),
    )
  }
}

/**
 * One computer as a radio row: its name, "PhonoTrainer, phone API {n}" and a status word (no address is shown in the
 * list, spec F-02 behaviour 3). A row that cannot be picked is muted and says why.
 */
@Composable
private fun ComputerRowItem(row: ComputerRow, selected: Boolean, pairedName: String?, onSelect: (String) -> Unit) {
  val colors = PhonoTheme.colors
  val resources = LocalResources.current
  val shape = RoundedCornerShape(PhonoRadius.large)
  val pickable = row.kind.selectable
  val status = remember(row, pairedName, resources) { ConnectCopy.rowStatus(row, pairedName, resources) }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .testTag(ConnectTags.row(row.key))
      .then(if (pickable) Modifier.focusRing(shape) else Modifier)
      .heightIn(min = PhonoSizes.row)
      .clip(shape)
      .background(colors.sheet)
      .border(if (selected) 2.5.dp else PhonoSizes.hairline, if (selected) colors.ink else colors.hairline, shape)
      .then(
        if (pickable) {
          Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(row.key) })
        } else {
          Modifier.semantics(mergeDescendants = true) {}
        },
      )
      .padding(PhonoSpacing.s14)
      .alpha(if (pickable) 1f else PhonoColors.DISABLED_ALPHA),
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
  ) {
    Box(
      modifier = Modifier.size(RADIO).border(2.dp, if (selected) colors.ink else colors.outline, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      if (selected) Box(Modifier.size(RADIO_DOT).clip(CircleShape).background(colors.ink))
    }
    Column(Modifier.weight(1f).testTag(ConnectTags.ROW), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
      PhonoText(row.computer.name, style = MaterialTheme.typography.titleMedium, color = colors.ink)
      PhonoText(
        stringResource(R.string.connect_row_version, row.computer.apiVersion),
        style = MaterialTheme.typography.bodySmall,
        color = colors.ink2,
      )
      PhonoText(status, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
    }
  }
}

/** "No computer answered." (F-02 States "Empty", render `21`): the illustration, the checklist, two ways on. */
@Composable
private fun NothingFound(actions: ConnectActions) {
  val colors = PhonoTheme.colors
  val phoneOn = stringResource(R.string.connect_command_phone_on)
  val items = listOf(
    withCommand(stringResource(R.string.connect_check_phone_access, phoneOn), phoneOn),
    withCommand(stringResource(R.string.connect_check_same_wifi), ""),
    withCommand(stringResource(R.string.connect_check_routers), ""),
    withCommand(stringResource(R.string.connect_check_vpn), ""),
  )
  Column(
    modifier = Modifier.fillMaxWidth().testTag(ConnectTags.NOTHING_FOUND),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
  ) {
    Illustration(PhonoIllustrations.NoComputer)
    PhonoText(
      stringResource(R.string.connect_none_title),
      style = MaterialTheme.typography.displaySmall,
      color = colors.ink,
      modifier = Modifier.semantics {
        heading()
        liveRegion = LiveRegionMode.Polite
      },
    )
    PhonoText(stringResource(R.string.connect_none_body), style = MaterialTheme.typography.bodyLarge, color = colors.ink)
    PaperCard(modifier = Modifier.fillMaxWidth().testTag(ConnectTags.CHECKLIST)) { NumberedList(items) }
    PhonoButton(
      text = stringResource(R.string.connect_search_again),
      onClick = actions.onSearchAgain,
      leadingIcon = PhonoIcons.ConnChecking,
      large = true,
      fillWidth = true,
      modifier = Modifier.testTag(ConnectTags.SEARCH_AGAIN),
    )
    PhonoButton(
      text = stringResource(R.string.connect_enter_address),
      onClick = actions.onEnterAddress,
      kind = PhonoButtonKind.SECONDARY,
      leadingIcon = PhonoIcons.Address,
      large = true,
      fillWidth = true,
      modifier = Modifier.testTag(ConnectTags.ENTER_ADDRESS),
    )
  }
}

/** "Computer address and port" with "Check" and the answer (F-02 behaviour 4; States for a typed address). */
@Composable
private fun AddressSection(state: ConnectUiState, actions: ConnectActions) {
  val resources = LocalResources.current
  val check = state.addressCheck
  val checking = check == AddressCheck.Checking
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s10)) {
    GroupTitle(stringResource(R.string.connect_address_label))
    PhonoTextField(
      value = state.address,
      onValueChange = actions.onAddressChange,
      label = stringResource(R.string.connect_address_label),
      placeholder = stringResource(R.string.connect_address_placeholder),
      leadingIcon = PhonoIcons.Address,
      error = if (check == AddressCheck.Invalid) stringResource(R.string.connect_address_invalid) else null,
      keyboardOptions = ADDRESS_KEYBOARD,
      keyboardActions = KeyboardActions(onGo = { actions.onCheck() }),
      modifier = Modifier.testTag(ConnectTags.ADDRESS),
    )
    PhonoButton(
      text = stringResource(R.string.connect_address_check),
      onClick = actions.onCheck,
      kind = PhonoButtonKind.SECONDARY,
      busy = checking,
      enabled = !checking && state.address.isNotBlank(),
      modifier = Modifier.testTag(ConnectTags.CHECK),
    )
    if (check != null && check != AddressCheck.Invalid) {
      val words = remember(check, resources) { ConnectCopy.addressAnswer(check, resources) }
      if (ConnectCopy.addressIsProblem(check)) {
        Notice(body = words, kind = NoticeKind.CAUTION, live = true, modifier = Modifier.testTag(ConnectTags.ADDRESS_RESULT))
      } else {
        IconLine(
          if (checking) PhonoIcons.ConnChecking else PhonoIcons.ConnConnected,
          words,
          Modifier.testTag(ConnectTags.ADDRESS_RESULT),
          live = true,
        )
      }
    }
    // After nothing was found, the typed computer has no list to sit in: it is shown here.
    val typed = state.typed
    if (typed != null && state.search !is SearchUi.Found) {
      ComputerRowItem(typed, selected = typed.key == state.selectedKey, pairedName = state.pairedName, onSelect = actions.onSelect)
    }
  }
}

/**
 * The code (F-02 behaviour 5): six cells over one `NumberPassword` field without autocorrect or autofill (security
 * review L-8; paste allowed), the error under the cells with the focus kept there (F-02-AC3), this phone's name and
 * "Connect".
 */
@Composable
private fun CodeSection(state: ConnectUiState, target: ComputerRow, actions: ConnectActions) {
  val colors = PhonoTheme.colors
  val resources = LocalResources.current
  val focus = remember { FocusRequester() }
  LaunchedEffect(state.codeErrorSerial) {
    if (state.codeErrorSerial > 0) focus.requestFocus()
  }
  Column(
    modifier = Modifier.fillMaxWidth().testTag(ConnectTags.CODE_SECTION),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
  ) {
    Hairline()
    GroupTitle(stringResource(R.string.connect_code_title))
    PhonoText(
      stringResource(R.string.connect_code_for, target.computer.name),
      style = MaterialTheme.typography.bodyMedium,
      color = colors.ink2,
    )
    val error = state.codeError?.let { remember(it, resources) { ConnectCopy.codeError(it, resources) } }
    CodeCells(
      code = state.code,
      onChange = actions.onCodeChange,
      onDone = { if (state.canConnect) actions.onConnect() },
      error = error,
      enabled = !state.pairing,
      modifier = Modifier.focusRequester(focus),
    )
    if (error != null) {
      IconLine(
        PhonoIcons.StatusAttention,
        error,
        Modifier.testTag(ConnectTags.CODE_ERROR),
        iconTint = colors.critical,
        live = true,
      )
    }
    PhonoTextField(
      value = state.deviceName,
      onValueChange = actions.onNameChange,
      label = stringResource(R.string.connect_name_label),
      leadingIcon = PhonoIcons.AvailOnPhone,
      keyboardOptions = NAME_KEYBOARD,
      modifier = Modifier.testTag(ConnectTags.DEVICE_NAME),
    )
    PhonoText(stringResource(R.string.connect_name_hint), style = MaterialTheme.typography.bodySmall, color = colors.ink2)
    PhonoButton(
      text = stringResource(R.string.connect_connect),
      onClick = actions.onConnect,
      enabled = state.canConnect,
      busy = state.pairing,
      leadingIcon = PhonoIcons.Pairing,
      large = true,
      fillWidth = true,
      modifier = Modifier.testTag(ConnectTags.CONNECT),
    )
  }
}

/** Six 48 dp-or-wider cells drawn over one text field (design 10.2 P-06: ink ring on the cell being typed). */
@Composable
internal fun CodeCells(
  code: String,
  onChange: (String) -> Unit,
  onDone: () -> Unit,
  error: String?,
  enabled: Boolean,
  modifier: Modifier = Modifier,
) {
  val colors = PhonoTheme.colors
  var focused by remember { mutableStateOf(false) }
  val label = stringResource(R.string.connect_code_label)
  val progress = stringResource(R.string.connect_code_progress, code.length)
  BasicTextField(
    value = code,
    onValueChange = onChange,
    enabled = enabled,
    singleLine = true,
    keyboardOptions = CODE_KEYBOARD,
    keyboardActions = KeyboardActions(onDone = { onDone() }),
    cursorBrush = SolidColor(colors.ink),
    textStyle = MaterialTheme.typography.titleLarge.copy(color = colors.ink),
    modifier = modifier
      .fillMaxWidth()
      .testTag(ConnectTags.CODE)
      .onFocusChanged { focused = it.isFocused }
      .semantics {
        contentDescription = label
        stateDescription = progress
        if (error != null) error(error)
        // Security review L-8: no autofill. A NumberPassword field is offered to autofill as a password by default;
        // ContentDataType.None takes it out of what autofill services see.
        contentDataType = ContentDataType.None
      },
    decorationBox = {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
        repeat(ConnectUiState.CODE_LENGTH) { index ->
          val current = focused && (index == code.length || (index == ConnectUiState.CODE_LENGTH - 1 && code.length == ConnectUiState.CODE_LENGTH))
          val ringWidth = when {
            error != null -> 2.dp
            current -> 2.5.dp
            else -> PhonoSizes.ring
          }
          val ringColor = when {
            error != null -> colors.critical
            current -> colors.ink
            else -> colors.outline
          }
          val shape = RoundedCornerShape(PhonoRadius.medium)
          Box(
            modifier = Modifier
              .weight(1f)
              .heightIn(min = CELL_HEIGHT)
              .clip(shape)
              .background(colors.sheet)
              .border(ringWidth, ringColor, shape)
              .padding(vertical = PhonoSpacing.s8),
            contentAlignment = Alignment.Center,
          ) {
            PhonoText(
              code.getOrNull(index)?.toString().orEmpty(),
              style = MaterialTheme.typography.titleLarge.tabular(),
              color = colors.ink,
              textAlign = TextAlign.Center,
            )
          }
        }
      }
    },
  )
}

/** "Connected to {name}" (F-02 behaviour 6), or the new address accepted (F-02-AC6). */
@Composable
private fun SuccessPanel(done: ConnectDone, actions: ConnectActions) {
  val colors = PhonoTheme.colors
  val (name, body, button, onClick) = when (done) {
    is ConnectDone.Paired -> SuccessWords(done.name, R.string.connect_success_body, R.string.connect_success_home, actions.onGoHome)
    is ConnectDone.NewAddress -> SuccessWords(done.name, R.string.connect_new_address_done, R.string.connect_done, actions.onDone)
  }
  Column(
    modifier = Modifier.fillMaxWidth().padding(top = PhonoSpacing.s24).testTag(ConnectTags.SUCCESS),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s16),
  ) {
    IconWell(icon = PhonoIcons.ConnConnected, filled = true, size = WELL)
    PhonoText(
      stringResource(R.string.connect_success_title, name),
      style = MaterialTheme.typography.displaySmall,
      color = colors.ink,
      modifier = Modifier.semantics {
        heading()
        liveRegion = LiveRegionMode.Polite
      },
    )
    PhonoText(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = colors.ink)
    PhonoButton(
      text = stringResource(button),
      onClick = onClick,
      large = true,
      fillWidth = true,
      trailingIcon = PhonoIcons.ArrowRight,
      modifier = Modifier.testTag(ConnectTags.SUCCESS_ACTION),
    )
  }
}

private data class SuccessWords(val name: String, val body: Int, val button: Int, val onClick: () -> Unit)

/** First run (F-01 behaviour 2): "Not now" and "You can connect later from Home." */
@Composable
private fun NotNow(onNotNow: () -> Unit) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
    Hairline()
    PhonoButton(
      text = stringResource(R.string.connect_not_now),
      onClick = onNotNow,
      kind = PhonoButtonKind.QUIET,
      modifier = Modifier.testTag(ConnectTags.NOT_NOW),
    )
    PhonoText(stringResource(R.string.connect_not_now_body), style = MaterialTheme.typography.bodyMedium, color = PhonoTheme.colors.ink2)
  }
}

/** The pairing code's keyboard (security review L-8): digits, kept out of keyboard history, no suggestions. */
internal val CODE_KEYBOARD = KeyboardOptions(
  keyboardType = KeyboardType.NumberPassword,
  autoCorrectEnabled = false,
  imeAction = ImeAction.Done,
)

/** The address keyboard: digits, dots and a colon, no suggestions. */
internal val ADDRESS_KEYBOARD = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Go)

private val NAME_KEYBOARD = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done)

private val RADIO = 22.dp
private val RADIO_DOT = 12.dp
private val CELL_HEIGHT = 56.dp
private val WELL = 64.dp
