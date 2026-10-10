package com.mapineda48.phonotrainer.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.JobId
import java.io.File

/** A short's progress (design 9.7, over the stylus trace). Placeholder: a linear bar. */
@Composable
fun ShortProgress(progress: Float, modifier: Modifier = Modifier) {
  val label = stringResource(R.string.ui_short_progress)
  LinearProgressIndicator(
    progress = { progress.coerceIn(0f, 1f) },
    modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
  )
}

/**
 * A clip's art (design 7.4, architecture 11.6): the [poster] when the phone has one, else the generated fallback
 * chosen per [jobId], or the audio art for an audio clip. Placeholder: a labelled box that reads neither input.
 */
@Composable
fun PosterArt(poster: File?, jobId: JobId, audioOnly: Boolean, modifier: Modifier = Modifier) {
  Box(modifier = modifier) {
    Text(
      text = stringResource(if (audioOnly) R.string.ui_poster_audio else R.string.ui_poster_video),
      style = MaterialTheme.typography.labelSmall,
    )
  }
}

@Preview
@Composable
private fun MediaPreview() {
  PhonoTheme { ShortProgress(progress = 0.4f) }
}
