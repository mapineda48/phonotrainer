package com.mapineda48.phonotrainer.core.testing.a11y

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The audit fails on what architecture 18 forbids and passes what it allows. */
@RunWith(RobolectricTestRunner::class)
class A11yAuditTest {
  @get:Rule
  val compose = createComposeRule()

  private lateinit var density: Density

  @Test
  fun aLabelledTargetOf48dpIsClean() {
    compose.setContent {
      density = LocalDensity.current
      Box(Modifier.size(48.dp).clickable {}.semantics { contentDescription = "Play" })
    }
    A11yAudit.assertClean(compose, density)
  }

  /**
   * Compose expands a pointer target smaller than 48 dp to the minimum touch size, so a small labelled target passes
   * and the audit reads the touch bounds, not the drawn size; an unlabelled target fails whatever its size.
   */
  @Test
  fun anUnlabelledTargetFails() {
    compose.setContent {
      density = LocalDensity.current
      Column {
        Box(Modifier.size(48.dp).clickable {})
        Box(Modifier.size(24.dp).clickable {}) { Text("Next") }
      }
    }
    val failure = runCatching { A11yAudit.assertClean(compose, density) }.exceptionOrNull()
    assertThat(failure).isInstanceOf(IllegalStateException::class.java)
    assertThat(failure).hasMessageThat().contains("has no label")
    assertThat(failure).hasMessageThat().doesNotContain("Next")
  }
}
