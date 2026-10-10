package com.mapineda48.phonotrainer.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.ui.a11y.paneTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * The Library stub's state: the search text lives in the [SavedStateHandle], so it survives tab switches, rotation
 * and process death; [instanceId] is new for every ViewModel instance, which is how the shell's per-tab state proof
 * (Android review H1) tells a kept ViewModel from a recreated one. WP-16 replaces this stub and keeps the tags of
 * [LibraryTags] (or updates that proof with a change request).
 */
@HiltViewModel
internal class LibraryStubViewModel @Inject constructor(private val savedState: SavedStateHandle) : ViewModel() {
  val instanceId: String = UUID.randomUUID().toString().take(8)

  val query: StateFlow<String> = savedState.getStateFlow(QUERY, "")

  fun setQuery(text: String) {
    savedState[QUERY] = text
  }

  private companion object {
    const val QUERY = "query"
  }
}

@Composable
internal fun LibraryRoute(onOpenClip: (JobId) -> Unit, viewModel: LibraryStubViewModel = hiltViewModel()) {
  val query by viewModel.query.collectAsStateWithLifecycle()
  LibraryStubScreen(
    query = query,
    instanceId = viewModel.instanceId,
    onQueryChange = viewModel::setQuery,
    onOpenClip = onOpenClip,
  )
}

/** P-01 stub: title, lede, a search field and a long placeholder list (the stub does not filter it). */
@Composable
internal fun LibraryStubScreen(
  query: String,
  instanceId: String,
  onQueryChange: (String) -> Unit,
  onOpenClip: (JobId) -> Unit,
  modifier: Modifier = Modifier,
) {
  val title = stringResource(R.string.library_title)
  Column(modifier = modifier.fillMaxSize().paneTitle(title).padding(horizontal = 16.dp)) {
    Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
    Text(stringResource(R.string.library_lede), style = MaterialTheme.typography.bodyLarge)
    OutlinedTextField(
      value = query,
      onValueChange = onQueryChange,
      label = { Text(stringResource(R.string.library_search)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().testTag(LibraryTags.SEARCH),
    )
    Text(
      text = stringResource(R.string.library_instance, instanceId),
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.testTag(LibraryTags.INSTANCE),
    )
    LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize().testTag(LibraryTags.LIST)) {
      items(ROWS, key = { it }) { number ->
        Text(
          text = stringResource(R.string.library_row, number),
          style = MaterialTheme.typography.bodyLarge,
          modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(LibraryTags.row(number))
            .clickable { onOpenClip(JobId("stub-$number")) }.padding(vertical = 16.dp),
        )
      }
    }
  }
}

private val ROWS = (1..60).toList()

@Preview
@Composable
private fun LibraryStubScreenPreview() {
  PhonoTheme { LibraryStubScreen(query = "walk", instanceId = "a1b2c3d4", onQueryChange = {}, onOpenClip = {}) }
}
