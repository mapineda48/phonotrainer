package com.mapineda48.phonotrainer.feature.learn

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.mapineda48.phonotrainer.core.designsystem.component.DoubleRule
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoIconButton
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.paperDepth
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.LinkTarget
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.Register
import com.mapineda48.phonotrainer.core.navigation.InsightsKey
import com.mapineda48.phonotrainer.core.navigation.IpaChartKey
import com.mapineda48.phonotrainer.core.navigation.LearnKey
import com.mapineda48.phonotrainer.core.navigation.PhenomenonKey
import com.mapineda48.phonotrainer.core.navigation.PracticeKey
import com.mapineda48.phonotrainer.core.ui.LoadingGate
import com.mapineda48.phonotrainer.core.ui.MarkedWordSpan
import com.mapineda48.phonotrainer.core.ui.component.OccurrenceRow
import com.mapineda48.phonotrainer.core.ui.familyLineName
import com.mapineda48.phonotrainer.core.ui.familyMeaning
import com.mapineda48.phonotrainer.core.ui.familyName
import com.mapineda48.phonotrainer.core.ui.marks.MarkLines

// Pieces several Learn screens draw.

/** A tab root's head (design 9.24): the title in displayMedium, the double rule and the lede. */
@Composable
internal fun TabRootHeader(title: String, lede: String, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  Column(modifier.fillMaxWidth().padding(top = PhonoSpacing.s16), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
    PhonoText(title, style = MaterialTheme.typography.displayMedium, color = colors.ink, modifier = Modifier.semantics { heading() })
    DoubleRule()
    PhonoText(lede, style = MaterialTheme.typography.bodyLarge, color = colors.ink2)
  }
}

/**
 * The top bar of a pushed Learn page (design 9.24), with a title that may wear a change's mark (P-08: "the change name
 * in its line style"): at least 64 dp, a 48 dp back arrow, the title in headlineLarge (wrapping, never truncated) and
 * the [actions].
 */
@Composable
internal fun LearnTopBar(
  title: String,
  backLabel: String,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  family: Family? = null,
  scrolled: Boolean = false,
  actions: @Composable RowScope.() -> Unit = {},
) {
  val colors = PhonoTheme.colors
  Column(modifier.fillMaxWidth().background(if (scrolled) colors.sheet else Color.Transparent)) {
    Row(
      modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = PhonoSpacing.s4),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PhonoIconButton(PhonoIcons.Back, contentDescription = backLabel, onClick = onBack, modifier = Modifier.testTag(LearnTags.BACK))
      Box(Modifier.weight(1f).padding(horizontal = PhonoSpacing.s8, vertical = PhonoSpacing.s8).semantics(mergeDescendants = true) { heading() }) {
        if (family != null) {
          MarkedWordSpan(text = title, family = family, style = MaterialTheme.typography.headlineLarge)
        } else {
          PhonoText(title, style = MaterialTheme.typography.headlineLarge, color = colors.ink)
        }
      }
      actions()
    }
    if (scrolled) Box(Modifier.fillMaxWidth().height(PhonoSizes.hairline).background(colors.hairline))
  }
}

/**
 * A card of Learn's pages with the look of `PaperCard` (design 9.1: sheet paper, 14 dp corners, 16 dp padding, the
 * depth of design 5.3) but without its intrinsic height, which wrapping rows (the IPA chart's groups) cannot answer
 * reliably: measured that way, a group of symbol buttons got less height than it needed and clipped its symbols.
 */
@Composable
internal fun LearnCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  val colors = PhonoTheme.colors
  val shape = RoundedCornerShape(PhonoRadius.large)
  Column(
    modifier = modifier
      .fillMaxWidth()
      .paperDepth(colors, shape)
      .clip(shape)
      .background(colors.sheet)
      .padding(PhonoSpacing.cardPadding),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
    content = content,
  )
}

/** A section title of a page (headlineSmall, a heading). */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
  PhonoText(
    text,
    style = MaterialTheme.typography.headlineSmall,
    color = PhonoTheme.colors.ink,
    modifier = modifier.padding(top = PhonoSpacing.s8).semantics { heading() },
  )
}

/**
 * A family's head on Learn (design 10.1 T3, render `12`): its icon in its hue, its name in its line style, and under
 * it what the line says ("Dashed line · the stop is clipped"); one heading for TalkBack. [family] null is the
 * whole-word changes, with no line.
 */
@Composable
internal fun FamilyHeader(family: Family?, title: String, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  val line = family?.let {
    stringResource(R.string.learn_family_line, LearnLogic.capitalize(familyLineName(it)), familyMeaning(it))
  }
  val description = listOfNotNull(title, line).joinToString(". ")
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(top = PhonoSpacing.s20, bottom = PhonoSpacing.s4)
      .clearAndSetSemantics {
        heading()
        contentDescription = description
      },
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
      val shown = family ?: Family.OTHER
      Icon(
        painterResource(MarkLines.iconOf(shown)),
        null,
        tint = if (family != null) colors.family.of(family) else colors.ink,
        modifier = Modifier.size(PhonoSizes.iconBar),
      )
      if (family != null) {
        MarkedWordSpan(text = title, family = family, style = MaterialTheme.typography.headlineMedium)
      } else {
        PhonoText(title, style = MaterialTheme.typography.headlineMedium, color = colors.ink)
      }
    }
    line?.let { PhonoText(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink2) }
  }
}

/**
 * The family pill of a phenomenon page's chips row (render `13`): the family's tint, a ring in its hue, its icon and
 * name. Not a link (the page is already the change's); TalkBack reads "t/d processes, dashed line".
 */
@Composable
internal fun FamilyPill(family: Family, name: String, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  val description = stringResource(R.string.learn_family_pill, name, familyLineName(family))
  Row(
    modifier = modifier
      .heightIn(min = 32.dp)
      .clip(CircleShape)
      .background(colors.family.tintOf(family))
      .border(PhonoSizes.ring, colors.family.of(family), CircleShape)
      .clearAndSetSemantics { contentDescription = description }
      .padding(horizontal = PhonoSpacing.s12, vertical = PhonoSpacing.s6),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
  ) {
    Icon(painterResource(MarkLines.iconOf(family)), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconInline))
    PhonoText(name, style = MaterialTheme.typography.labelLarge, color = colors.ink)
  }
}

/** The family a pill names when the reference gives its own name; [familyName]'s words otherwise. */
@Composable
internal fun familyTitle(family: Family, name: String?): String = name ?: familyName(family)

/** The register's word after the practice badge ("Safe to produce · universal"). */
@Composable
internal fun registerWord(register: Register): String = stringResource(
  when (register) {
    Register.UNIVERSAL -> R.string.phenomenon_register_universal
    Register.CASUAL -> R.string.phenomenon_register_casual
    Register.MARKED -> R.string.phenomenon_register_marked
  },
)

/** What the register means (web `REGISTER_MEANING`). */
@Composable
internal fun registerMeaning(register: Register): String = stringResource(
  when (register) {
    Register.UNIVERSAL -> R.string.phenomenon_register_universal_meaning
    Register.CASUAL -> R.string.phenomenon_register_casual_meaning
    Register.MARKED -> R.string.phenomenon_register_marked_meaning
  },
)

/**
 * A list of examples (the phenomenon page's, the symbol sheet's): the loading rule of the spec while the index is read,
 * the F-15 error line when it cannot be, [empty] when there is none, else one [OccurrenceRow] each with a hairline
 * between them.
 */
@Composable
internal fun ExampleList(
  examples: ExamplesUi,
  loadingLabel: String,
  onPlay: (Occurrence) -> Unit,
  onOpenLesson: (Occurrence) -> Unit,
  modifier: Modifier = Modifier,
  empty: @Composable () -> Unit,
) {
  val colors = PhonoTheme.colors
  Column(modifier.fillMaxWidth()) {
    when (examples) {
      ExamplesUi.Loading -> LoadingGate(loading = true, label = loadingLabel) {}
      ExamplesUi.Failed -> CountsFailedLine()
      is ExamplesUi.Ready -> if (examples.rows.isEmpty()) {
        empty()
      } else {
        examples.rows.forEachIndexed { index, row ->
          if (index > 0) Box(Modifier.fillMaxWidth().height(PhonoSizes.hairline).background(colors.hairline))
          OccurrenceRow(
            state = row,
            onPlay = { onPlay(row.occurrence) },
            onOpenLesson = { onOpenLesson(row.occurrence) },
            modifier = Modifier.testTag(LearnTags.example(index)),
          )
        }
      }
    }
  }
}

/** F-15 Error: "Your clip counts could not be loaded; the explanations still work." */
@Composable
internal fun CountsFailedLine(modifier: Modifier = Modifier) {
  PhonoText(
    stringResource(R.string.learn_counts_failed),
    style = MaterialTheme.typography.bodyMedium,
    color = PhonoTheme.colors.ink2,
    modifier = modifier.testTag(LearnTags.COUNTS_FAILED),
  )
}

/** Where "Learn more" of a "What's this?" sheet goes (architecture 15.1's link table). */
internal fun LinkTarget.toKey(): NavKey = when (this) {
  LinkTarget.Learn -> LearnKey
  is LinkTarget.Phenomenon -> PhenomenonKey(label)
  is LinkTarget.Ipa -> IpaChartKey(symbol)
  is LinkTarget.Practice -> PracticeKey(focus?.value)
  LinkTarget.Insights -> InsightsKey
}
