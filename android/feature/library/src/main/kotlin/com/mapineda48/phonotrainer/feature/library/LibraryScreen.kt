package com.mapineda48.phonotrainer.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import com.mapineda48.phonotrainer.core.designsystem.component.ChipKind
import com.mapineda48.phonotrainer.core.designsystem.component.Kicker
import com.mapineda48.phonotrainer.core.designsystem.component.MenuButton
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.NoticeKind
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoChip
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoIconButton
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoTextField
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoTopBar
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.illustration.PhonoIllustrations
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ClipFilter
import com.mapineda48.phonotrainer.core.domain.model.ClipSort
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.ui.EmptyState
import com.mapineda48.phonotrainer.core.ui.EmptyStateAction
import com.mapineda48.phonotrainer.core.ui.LoadingGate
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.a11y.paneTitle
import com.mapineda48.phonotrainer.core.ui.component.ClipRow
import com.mapineda48.phonotrainer.core.ui.component.RequestRow
import com.mapineda48.phonotrainer.core.ui.component.RequestRowState

/** What the Library's controls ask for; the route turns each into a ViewModel call or a navigation. */
internal class LibraryActions(
  val onBack: () -> Unit = {},
  val onAdd: () -> Unit = {},
  val onSearch: (String) -> Unit = {},
  val onFilter: (ClipFilter) -> Unit = {},
  val onShowAll: () -> Unit = {},
  val onSort: (ClipSort) -> Unit = {},
  val onRetry: () -> Unit = {},
  val onOpenClip: (JobId) -> Unit = {},
  val onWatch: (JobId) -> Unit = {},
  val onKeep: (JobId, Boolean) -> Unit = { _, _ -> },
  val onDownload: (JobId) -> Unit = {},
  val onRemoveFromPhone: (JobId) -> Unit = {},
  val onRemoveVideo: (JobId) -> Unit = {},
  val onRequestAction: (Request, RequestAction) -> Unit = { _, _ -> },
  val onOpenRequest: (RequestId) -> Unit = {},
  val onStartedOnComputer: () -> Unit = {},
  val onConnect: () -> Unit = {},
  val onLearn: () -> Unit = {},
)

/**
 * The Library (spec F-04, design 10.2 P-01, render `15`): top bar "All clips" with Add; the lede; the "In progress"
 * group and the "started on your computer" line; search, the two filter chips, the live count with the sort menu; the
 * clip rows. One list scrolls it all, so its saveable state keeps the scroll position across Back and tab switches.
 */
@Composable
internal fun LibraryScreen(state: LibraryUiState, actions: LibraryActions, modifier: Modifier = Modifier) {
  val title = stringResource(R.string.library_title)
  var menuFor by rememberSaveable { mutableStateOf<String?>(null) }
  var removing by rememberSaveable { mutableStateOf<String?>(null) }
  val listState = rememberLazyListState()
  Column(
    modifier = modifier
      .fillMaxSize()
      .paneTitle(title)
      .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
      .imePadding(),
  ) {
    PhonoTopBar(
      title = title,
      onBack = actions.onBack,
      backLabel = stringResource(R.string.library_back),
      scrolled = listState.canScrollBackward,
      actions = {
        PhonoIconButton(
          icon = PhonoIcons.Add,
          contentDescription = stringResource(R.string.library_add),
          onClick = actions.onAdd,
          modifier = Modifier.testTag(LibraryTags.ADD),
        )
      },
    )
    if (state.loading) {
      // Outside the list on purpose: a lazy list keeps a restored scroll position (after a process death) only until
      // its first layout with items, so it must not lay out placeholder items before the catalogue arrives.
      Column(Modifier.fillMaxSize().padding(horizontal = PhonoSpacing.gutter)) {
        Lede()
        if (state.offline) OfflineBanner(state)
        Box(Modifier.testTag(LibraryTags.LOADING)) {
          LoadingGate(loading = true, label = stringResource(R.string.library_loading)) {}
        }
      }
    } else {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag(LibraryTags.LIST),
        contentPadding = PaddingValues(start = PhonoSpacing.gutter, end = PhonoSpacing.gutter, bottom = PhonoSpacing.s24),
        verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
      ) {
        item(key = "lede") { Lede() }
        if (state.offline) item(key = "offline") { OfflineBanner(state) }
        when {
          state.failed -> item(key = "error") { CatalogueError(actions.onRetry) }
          else -> {
            inProgress(state, actions)
            if (state.empty) {
              item(key = "empty") { LibraryEmpty(state.paired, actions) }
            } else {
              controls(state, actions)
              if (state.clips.isEmpty()) {
                item(key = "no-match") { NoMatch(state, actions) }
              }
              items(state.clips, key = { "clip:" + it.clip.id.value }) { card ->
                val id = card.clip.id
                Column {
                  ClipRow(
                    state = card,
                    onWatch = { actions.onWatch(id) },
                    onOpen = { actions.onOpenClip(id) },
                    onMore = { menuFor = id.value },
                    modifier = Modifier.testTag(LibraryTags.row(id)),
                    moreMenu = {
                      RowMenu(
                        clip = card.clip,
                        expanded = menuFor == id.value,
                        onDismiss = { menuFor = null },
                        onPick = { item ->
                          menuFor = null
                          when (item) {
                            RowMenuItem.WATCH -> actions.onWatch(id)
                            RowMenuItem.OPEN -> actions.onOpenClip(id)
                            RowMenuItem.KEEP -> actions.onKeep(id, true)
                            RowMenuItem.STOP_KEEPING -> actions.onKeep(id, false)
                            RowMenuItem.DOWNLOAD -> actions.onDownload(id)
                            RowMenuItem.REMOVE_FROM_PHONE -> removing = id.value
                            RowMenuItem.REMOVE_VIDEO -> actions.onRemoveVideo(id)
                          }
                        },
                      )
                    },
                  )
                  Box(Modifier.fillMaxWidth().height(PhonoSizes.hairline).background(PhonoTheme.colors.hairline))
                }
              }
            }
          }
        }
      }
    }
  }
  val target = removing?.let { id -> state.clips.firstOrNull { it.clip.id.value == id }?.clip }
  if (target != null) {
    RemoveFromPhoneDialog(
      title = target.title,
      onConfirm = {
        removing = null
        actions.onRemoveFromPhone(target.id)
      },
      onDismiss = { removing = null },
    )
  }
}

/** "In progress" (this phone's non-final requests, compact rows) and the "started on your computer" line. */
private fun LazyListScope.inProgress(state: LibraryUiState, actions: LibraryActions) {
  if (state.inProgress.isEmpty() && state.startedOnComputer == 0) return
  if (state.inProgress.isNotEmpty()) {
    item(key = "in-progress") {
      Kicker(
        text = stringResource(R.string.library_in_progress),
        count = state.inProgress.size.toString(),
        modifier = Modifier.padding(vertical = PhonoSpacing.s8).testTag(LibraryTags.IN_PROGRESS),
      )
    }
    items(state.inProgress, key = { "request:" + it.id.value }) { request ->
      Box(Modifier.testTag(LibraryTags.request(request.id))) {
        RequestRow(
          state = RequestRowState(request, compact = true),
          onAction = { actions.onRequestAction(request, it) },
          onOpen = { actions.onOpenRequest(request.id) },
        )
      }
    }
  }
  if (state.startedOnComputer > 0) {
    item(key = "started-on-computer") {
      val count = state.startedOnComputer
      val words = if (state.inProgress.isEmpty()) {
        pluralStringResource(R.plurals.library_started, count, count)
      } else {
        pluralStringResource(R.plurals.library_started_more, count, count)
      }
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = PhonoSizes.minTarget)
          .clickable(role = Role.Button, onClick = actions.onStartedOnComputer)
          .semantics { contentDescription = words }
          .testTag(LibraryTags.STARTED_ON_COMPUTER),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
      ) {
        Icon(painterResource(PhonoIcons.SiteComputer), null, tint = PhonoTheme.colors.ink, modifier = Modifier.size(PhonoSizes.iconButton))
        PhonoText(words, style = MaterialTheme.typography.bodyMedium, color = PhonoTheme.colors.ink, modifier = Modifier.weight(1f))
        Icon(painterResource(PhonoIcons.ChevronRight), null, tint = PhonoTheme.colors.ink, modifier = Modifier.size(PhonoSizes.iconButton))
      }
    }
  }
  item(key = "in-progress-gap") { Box(Modifier.height(PhonoSpacing.s12)) }
}

/** Search, the filter chips, the live count and the sort menu. */
@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.controls(state: LibraryUiState, actions: LibraryActions) {
  item(key = "search") {
    val label = stringResource(R.string.library_search)
    // The tag names the field's container; the editable node inside it is the one with SetText (tests match it as
    // `hasSetTextAction() and hasAnyAncestor(hasTestTag(LibraryTags.SEARCH))`).
    Box(Modifier.testTag(LibraryTags.SEARCH)) {
      PhonoTextField(
        value = state.search,
        onValueChange = actions.onSearch,
        label = label,
        placeholder = label,
        leadingIcon = PhonoIcons.Search,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      )
    }
  }
  item(key = "filters") {
    FlowRow(
      modifier = Modifier.fillMaxWidth().padding(vertical = PhonoSpacing.s8),
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.chipGap),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.chipGap),
    ) {
      LibraryRules.FILTERS.forEach { filter ->
        val selected = state.filter == filter
        PhonoChip(
          text = stringResource(filter.label()),
          kind = if (selected) ChipKind.SOLID else ChipKind.OUTLINE,
          onClick = { actions.onFilter(filter) },
          modifier = Modifier.semantics { this.selected = selected }.testTag(LibraryTags.filter(filter)),
        )
      }
    }
  }
  item(key = "count") {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      val count = if (state.clips.size == state.total) {
        pluralStringResource(R.plurals.library_count_all, state.total, state.total)
      } else {
        pluralStringResource(R.plurals.library_count_of, state.total, state.clips.size, state.total)
      }
      PhonoText(
        count,
        style = MaterialTheme.typography.bodyMedium,
        color = PhonoTheme.colors.ink2,
        modifier = Modifier
          .weight(1f, fill = false)
          .semantics { liveRegion = LiveRegionMode.Polite }
          .testTag(LibraryTags.COUNT),
      )
      SortMenu(state.sort, actions.onSort)
    }
  }
}

@Composable
private fun SortMenu(sort: ClipSort, onSort: (ClipSort) -> Unit) {
  var open by rememberSaveable { mutableStateOf(false) }
  val name = stringResource(sort.label())
  val description = stringResource(R.string.library_sort_label, name)
  Box {
    MenuButton(
      text = name,
      onClick = { open = true },
      modifier = Modifier.semantics { contentDescription = description }.testTag(LibraryTags.SORT),
    )
    DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = PhonoTheme.colors.sheet) {
      LibraryRules.SORTS.forEach { option ->
        DropdownMenuItem(
          text = { PhonoText(stringResource(option.label()), style = MaterialTheme.typography.bodyLarge, color = PhonoTheme.colors.ink) },
          onClick = {
            open = false
            onSort(option)
          },
          leadingIcon = if (option == sort) {
            { Icon(painterResource(PhonoIcons.StepDone), null, tint = PhonoTheme.colors.ink) }
          } else {
            null
          },
          modifier = Modifier.semantics { selected = option == sort }.testTag(LibraryTags.sortOption(option)),
        )
      }
    }
  }
}

/** A row's "More actions" menu (spec F-04): its items come from [LibraryRules.menu]. */
@Composable
private fun RowMenu(clip: ClipSummary, expanded: Boolean, onDismiss: () -> Unit, onPick: (RowMenuItem) -> Unit) {
  DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = PhonoTheme.colors.sheet) {
    LibraryRules.menu(clip).forEach { item ->
      DropdownMenuItem(
        text = { PhonoText(stringResource(item.label()), style = MaterialTheme.typography.bodyLarge, color = PhonoTheme.colors.ink) },
        onClick = { onPick(item) },
        leadingIcon = { Icon(painterResource(item.icon()), null, tint = PhonoTheme.colors.ink) },
        modifier = Modifier.testTag(LibraryTags.menu(item)),
      )
    }
  }
}

/** The destination's one-sentence lede (design 10). */
@Composable
private fun Lede() {
  PhonoText(
    stringResource(R.string.library_lede),
    style = MaterialTheme.typography.bodyMedium,
    color = PhonoTheme.colors.ink2,
    modifier = Modifier.padding(bottom = PhonoSpacing.s12).testTag(LibraryTags.LEDE),
  )
}

@Composable
private fun OfflineBanner(state: LibraryUiState) {
  val resources = LocalResources.current
  val words = state.lastSync?.let {
    stringResource(R.string.library_offline, LibraryTime.lastSynced(it, state.now, state.zone, resources))
  } ?: stringResource(R.string.library_offline_never)
  Notice(words, modifier = Modifier.padding(bottom = PhonoSpacing.s12).testTag(LibraryTags.OFFLINE))
}

@Composable
private fun CatalogueError(onRetry: () -> Unit) {
  Notice(
    body = stringResource(R.string.library_error),
    kind = NoticeKind.CAUTION,
    live = true,
    modifier = Modifier.testTag(LibraryTags.ERROR),
    action = {
      PhonoButton(
        text = stringResource(R.string.library_retry),
        onClick = onRetry,
        kind = PhonoButtonKind.SECONDARY,
        modifier = Modifier.testTag(LibraryTags.RETRY),
      )
    },
  )
}

/** First run (not paired, no clips) and empty (paired, the computer has none): the words and actions of Home (F-03). */
@Composable
private fun LibraryEmpty(paired: Boolean, actions: LibraryActions) {
  if (paired) {
    EmptyState(
      illustration = PhonoIllustrations.HomeNoClips,
      title = stringResource(R.string.library_empty_title),
      body = stringResource(R.string.library_empty_body),
      modifier = Modifier.testTag(LibraryTags.EMPTY),
      actions = listOf(EmptyStateAction(stringResource(R.string.library_add), actions.onAdd)),
    )
  } else {
    EmptyState(
      illustration = PhonoIllustrations.WelcomeClips,
      title = stringResource(R.string.library_first_title),
      body = stringResource(R.string.library_first_body),
      modifier = Modifier.testTag(LibraryTags.EMPTY),
      actions = listOf(
        EmptyStateAction(stringResource(R.string.library_connect), actions.onConnect),
        EmptyStateAction(stringResource(R.string.library_explore_learn), actions.onLearn, PhonoButtonKind.SECONDARY),
      ),
    )
  }
}

/** No row matches: the search's words and "Clear the search" (web), or the filter's words and "Show every clip". */
@Composable
private fun NoMatch(state: LibraryUiState, actions: LibraryActions) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(vertical = PhonoSpacing.s24).testTag(LibraryTags.NO_MATCH),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
  ) {
    if (state.search.isNotBlank()) {
      PhonoText(stringResource(R.string.library_no_match, state.search.trim()), style = MaterialTheme.typography.bodyLarge, color = PhonoTheme.colors.ink)
      PhonoButton(
        text = stringResource(R.string.library_clear_search),
        onClick = { actions.onSearch("") },
        kind = PhonoButtonKind.QUIET,
        modifier = Modifier.testTag(LibraryTags.CLEAR_SEARCH),
      )
    } else {
      val words = if (state.filter == ClipFilter.ON_THIS_PHONE) R.string.library_none_on_phone else R.string.library_none_elsewhere
      PhonoText(stringResource(words), style = MaterialTheme.typography.bodyLarge, color = PhonoTheme.colors.ink)
      PhonoButton(
        text = stringResource(R.string.library_show_all),
        onClick = actions.onShowAll,
        kind = PhonoButtonKind.QUIET,
        modifier = Modifier.testTag(LibraryTags.SHOW_ALL),
      )
    }
  }
}

internal fun ClipFilter.label(): Int = when (this) {
  ClipFilter.NOT_ON_THIS_PHONE -> R.string.library_filter_not_on_phone
  else -> R.string.library_filter_on_phone
}

internal fun ClipSort.label(): Int = when (this) {
  ClipSort.NEWEST -> R.string.library_sort_newest
  ClipSort.OLDEST -> R.string.library_sort_oldest
  ClipSort.TITLE -> R.string.library_sort_title
  ClipSort.LONGEST -> R.string.library_sort_longest
}

internal fun RowMenuItem.label(): Int = when (this) {
  RowMenuItem.WATCH -> R.string.library_menu_watch
  RowMenuItem.OPEN -> R.string.library_menu_open
  RowMenuItem.KEEP -> R.string.library_menu_keep
  RowMenuItem.STOP_KEEPING -> R.string.library_menu_stop_keeping
  RowMenuItem.DOWNLOAD -> R.string.library_menu_download
  RowMenuItem.REMOVE_FROM_PHONE -> R.string.library_menu_remove
  RowMenuItem.REMOVE_VIDEO -> R.string.library_menu_remove_video
}

private fun RowMenuItem.icon(): Int = when (this) {
  RowMenuItem.WATCH -> PhonoIcons.Play
  RowMenuItem.OPEN -> PhonoIcons.ClipPage
  RowMenuItem.KEEP, RowMenuItem.STOP_KEEPING -> PhonoIcons.Keep
  RowMenuItem.DOWNLOAD -> PhonoIcons.StatusDownloading
  RowMenuItem.REMOVE_FROM_PHONE -> PhonoIcons.Remove
  RowMenuItem.REMOVE_VIDEO -> PhonoIcons.AvailVideo
}
