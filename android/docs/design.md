# PhonoTrainer for Android: design system ("Phonautograph")

Status: v1 design system, revision 3.2 (2026-10-11). Revision 3 (2026-10-10, change request CR-01) mirrors spec
revision 4. Revision 2 closed the design items of the cross-track review and mirrored spec revision 3. Revision 3
designs what the owner's answers to Q-1..Q-3 add, all v1-complete: deleting a finished analysis from your computer and
its pending state (9.26), the shared-progress line (9.27), and the version-2 transport notes (9.28, not v1). v1-core
behaviour, components and renders are unchanged; v1-core only gains the look of the "Remove “{title}” from this
phone?" dialog, whose copy spec revision 4 added (9.23), and an explicit anatomy, from existing components, for the
Clip page's existing "On this phone" block (9.26). Revision 3.1 (the CR-01 review's fix round 1) changes no layout,
colour or component of revision 3. It does three things. It cites the names and owners the architecture gave to
design's requests (architecture 3.4, 19 and 25.5 C-42; work-plan 4.7, WP-26 and WP-34) and drops the answered
requests. It keeps the requests still open with product under one unambiguous label, "CR-01 design request P-n", which
cannot be mistaken for this document's accessibility rules A-01..A-16 or destinations P-01..P-13. It also adds the
quoted-title rule for clip titles inside CR-01 sentences (9.26; finding 14 of the CR-01 security review). Revision
3.2 (2026-10-11, fix round 2) mirrors the spec's answers D-58 to D-65 (spec 15.7) and closes CR-01 design requests
P-1 to P-4. It adds no component, colour, icon or render: an imported clip's deletion uses D-58's words wherever it is
named (9.18, 9.19, 9.26); a waiting deletion that may have reached the computer drops "Undo" and gains D-65's line,
drawn with the existing second reason line (9.19, 9.26); refused deletions follow D-59 (9.17, 9.18); waiting reasons
follow D-61's order, for requests and deletions alike (9.19); refusal sentences are D-62's (9.19, 9.26, 14); "Forget
this computer?" takes D-60's title and buttons (9.23, 14). Renders 28-34 are unchanged: they draw the non-imported
variant, and their words are the spec's. Owner: design lead.

Inputs: `android/docs/product-spec.md` (the authority on content, hierarchy, states and interactions; this document
never changes behaviour, it decides how things look and move), `android/docs/architecture.md` section 17 (the
`:core:designsystem` / `:core:ui` API this document fills), the web client's identity (`web/src/theme/tokens.css`,
`palette.test.ts`, `web/src/ui/*`, read-only), and the three design concepts judged by a panel (section 0).

What lives where:

| Path | What |
|---|---|
| `android/docs/design.md` | this document |
| `android/docs/design/*.png` | hi-fi mockups, light and dark (section 14) |
| `android/design-src/palette.json` | every colour of the system; the source for `Color.kt`, gated by `check_palette.py` |
| `android/design-src/icons.json` | the icon inventory (section 6) |
| `android/design-src/svg/brand/*.svg` | master geometry of the mark, launcher layers, splash and notification icon |
| `android/design-src/mockups/` | HTML sources of the mockups, the mockup kit (`kit/`) and `render.sh` |
| `android/design-src/tools/` | `check_palette.py`, `svg2vd.py`, `vd2svg.py`, `roundtrip.py`, `lucide_icons.py`, `brand_export.py` (section 13) |
| `android/design-src/export/` | staging for generated Android resources (`res/…`, `assets/licenses/`); nothing in the build references it (architecture 17) |
| `android/design-src/cr01/` | the icons change request CR-01 adds (`icons.json`, `svg/icons/`, `export/res/drawable/`), staged apart so the frozen v1 export (173 files) does not change (6.1) |

Conventions: sizes are dp (layout) and sp (text). Colour names in `code` are tokens of `palette.json`. "Ink" is
the text colour of the theme (near-black on paper, bone on soot), never a hue. Copy in double quotes is the spec's
copy; the mockups shorten a few strings where the spec allows it (section 14).

---------------------------------------------------------------------------------------------------------------

## 0. How this system was chosen

Three concepts were built and scored by three independent judges (brand, robustness, aesthetics):

| Concept | Judge 1 | Judge 2 | Judge 3 | Total | Winner votes |
|---|---|---|---|---|---|
| **Ink and Sound ("Phonautograph")** | 51 | 51 | 49 | **151** | 2 of 3 |
| Field Notebook | 47 | 49 | 50 | 146 | 1 of 3 |
| Night Stage | 46 | 49 | 47 | 142 | 0 |

**Decision: Phonautograph**, by majority and by total. It is the only concept that keeps the web client's identity
intact (paper, ink-first chrome, the exact family hues, Atkinson + Charis, a calm editorial voice) while making the
app rich from the subject itself: its signature element, the stylus trace, is the real pitch contour of the speech.
Its known risk, "too restrained for an owner who asked for NOT minimalist", is answered by the grafts below, which
put illustration, texture and drawn motifs on everyday screens without adding hue to chrome.

Grafts adopted (each is specified where it lives):

| From | Graft | Where |
|---|---|---|
| Night Stage | Six named stages under the request stepper, current one bold, teaching line in a notice well | 9.19 Request card, 10 P-03/P-04 |
| Night Stage | Share confirmation as a phone → computer bridge in two drawn states (solid trace with the nib travelling / dotted pencil line with the request parked on the phone) | 9.22 |
| Night Stage | First-use hint line under the captions; "Now" is a speaker icon + the word, not only a diamond | 9.4, 9.3 |
| Night Stage | "≠ changed" pill for the selected pair in Compare; legend directly above the sorts | 9.10 |
| Night Stage | The ‿ link drawn as a small arc in the Word boundary hue, gated 3:1 | 9.4 |
| Night Stage | Stricter gate: the "no marks on galley / deep sheet" rule is now tested, plus a gate for overlays over worst-case video frames | 3 |
| Night Stage | Critical colour as the third cue on failures, with an outlined destructive "Remove" | 9.19 |
| Night Stage | "Paused at 0:45 · {clip}" context pill above the lesson sheet | 9.9 |
| Field Notebook | The spotlight tied to its word: a hairline tether from the slip to the caption word, drawn only when nothing lies between them | 9.3 |
| Field Notebook | Side-view mouth drawing inside the Compare sound card (tract palette, never a family hue) | 9.10 |
| Field Notebook | Plain English sound-alike cues ("it sounds like “wadder”", "“tem bucks”"); no cross-language anchor in UI cues | 9.3, 9.10 |
| Field Notebook | Pattern-emphasis icons after marked words and the dashed low-confidence outline, shown in a mockup | 9.4, mockup 05 |
| Field Notebook | "On this phone" chip in the feed header when the computer is not reachable | 9.2 |
| Field Notebook | Keep watching card says what the short teaches: "Listen for: [chip] in “pick it up”" | 9.16 |
| Field Notebook | Line-style names next to each family in Watch by change ("dashed line · the stop is clipped") | 9.16 |
| Field Notebook | A horizontal labelled action bar replaces the side rail when the phrase does not fit, not only at large font scales | 9.6 |
| Field Notebook / judge 2 | Launcher tile readable on dark share sheets: kraft drum-paper tile with an ink P | 7.1 |
| Judges 1 and 3 | Engraved-ink spot illustrations on everyday cards (Practice, Insights teaser, summary card) | 7.2 |
| Judge 2 (fixes) | Audio-drum word labels on an opaque strip; halftone never under any text | 7.3, 5.4 |

Rejected, with the reason: rotated rubber-stamp text and a handwriting display face (legibility, 13.5 sp);
checkbox-looking items in non-interactive checklists (TalkBack and affordance confusion: checklists are numbered);
13 sp rail labels (the floor is 14 sp); a spotlight diamond fused to the playhead thumb (the pin stands on its own
stem); glow and ambient colour bleed sampled from video (per-frame cost, API 31-only blur, contrast that depends on
the frame); a persistent "Hold to slow down" chip over the picture (it is a one-time coach mark); a dark-first
identity and re-tuned family hues (the web's hues are kept exactly).

---------------------------------------------------------------------------------------------------------------

## 1. Concept and signature element

### 1.1 Concept: the phonautograph

In 1857 Édouard-Léon Scott de Martinville built the **phonautograph**: a horn and a stylus that scratched the shape
of a voice onto paper blackened with soot. The first recordings of sound were made to be **looked at**, so people
could study speech with their eyes. PhonoTrainer does the same: the computer turns the learner's clips into data,
and the phone turns that data into something to study.

- **Light theme, "Ink on paper"**: newsprint page, fresh sheets for cards, near-black ink.
- **Dark theme, "Scratch on soot"**: smoked black paper with lines scratched in bone. The same identity, not the
  light theme inverted.
- **Mood**: editorial, studious and warm; a phonetics lab notebook crossed with a well-printed magazine. Every
  surface has paper, a rule, a halftone or a line of type to look at. Confident about sound (lines, dots,
  notation), quiet about chrome (ink buttons, no accent colour).
- **Voice** (unchanged from the web): plain, second person, calm; jargon explained in one sentence; describe,
  never grade; sentence case; curly quotes around studied words; "×" for speeds, "→" for changes, "‿" for links.

### 1.2 Signature element: the stylus trace

One continuous ink line whose width changes like a pen stroke and which **is** the pitch of speech. It is drawn
from real data (`f0_track`, phrase timings, intensity when present) wherever it carries meaning, and it is the
brand's one drawn motif everywhere else.

| Where | What the trace does |
|---|---|
| Short progress bar (9.7) | The pitch contour of the whole short. Played part: a solid ink ribbon whose width follows loudness. Part to come: a dotted pencil hairline. Playhead: a pen nib (dot + 25 % halo). Phrase boundaries: ticks. The spotlight word: a **diamond pin on a stem** (shape, never colour), filled while the word plays. |
| Audio-only short (7.3) | The current phrase's pitch line, drawn large on a ruled drum over a spectrogram halftone, each word labelled under its stretch. |
| Today's set (9.16) | A thread of 8 stations (each the spotlight change's family icon, a bookmark on saved shorts) ending in 5 question slugs. |
| Requests (9.19) and the share sheet (9.22) | A route line Phone → Computer → Ready, and under the stepper the six stages; solid = done, dotted = not yet, nib = now. |
| Illustrations (7.2) | The trace narrates each empty state: flowing, decaying, flat or waiting as a dotted line. |
| Launcher icon and splash (7.1) | "The stylus P": one stroke rises as the stem of a P, closes the bowl and leaves as a decaying wave ending in an ink blot. The splash draws it once. |

The trace is **content, not decoration**: like the web's articulator rule, it follows the audio clock even with
Reduce motion, because playback moves it, not an animation.

### 1.3 Supporting motifs

The **slip** (the spotlight card has a perforated top edge, a ticket torn from the set), the **type sort** (each
sound in Compare is a metal sort with a nick), **crop marks** and **stacked sheets** on hero cards, the **double
rule** (2 dp + 1 dp ink) under the masthead and above the bottom bar, the **halftone** (a spectrogram screen used as
print texture), **paper grain**, and a faint rotated **"NEW" stamp** (decorative; the information is in the text).

---------------------------------------------------------------------------------------------------------------

## 2. Colour

Rules (A-01, A-02, web rule): hue is reserved for the four phenomenon families. Chrome is ink and paper. A state is
shown by ink fill, ring, shape, icon and word, never by hue. `good` and `critical` appear only as icons or outlines
next to words. Every value below is in `design-src/palette.json`; change a value there and run
`check_palette.py` (section 3).

### 2.1 Neutral tokens (`PhonoTheme.colors`)

| Token | Light "Ink on paper" | Dark "Scratch on soot" | Use |
|---|---|---|---|
| `page` | `#F8F6EF` | `#121110` | Screen ground; carries the paper grain |
| `sheet` | `#FFFDF8` | `#1C1B19` | Cards, slips, sheets, dialogs, bottom bar, top bar when scrolled |
| `galley` | `#EFEBE2` | `#272522` | Chips, wells, tracks, notices, badges. **Never under a family mark** |
| `deepSheet` | `#E5E0D4` | `#312F2B` | Pressed states, the selected-row wash, rails behind content |
| `ink` | `#1B1A18` | `#F3F0E8` (bone) | Text, icons, primary button fill, selected fill, the trace |
| `ink2` | `#45433E` | `#D0CCC2` | Secondary text, ledes, data series |
| `inkMuted` | `#5D5A53` | `#A6A297` | Tertiary text, dimmed caption phrases, the unplayed trace |
| `onInk` | `#FFFDF8` | `#121110` | Text and icons on ink fills |
| `outline` | `#7C786F` | `#858177` | Essential control boundaries (fields, switches, radios, segmented controls, unselected stepper nodes): ≥ 3:1 everywhere |
| `lineStrong` | `#B6B4AF` | `#565451` | Rings of secondary buttons and chips (their label identifies them) |
| `hairline` | `#DBD9D3` | `#2F2E2C` | Dividers, card rings (= ink at 13 % on page) |
| `focus` | `#1F5FD1` | `#8AB4FF` | The 3 dp focus ring (keyboard, D-pad, switch access) |
| `good` | `#0B7D0B` | `#3FBF55` | Check icons beside a word ("Correct answer") |
| `critical` | `#C0362F` | `#FF6B5E` | Alert icons, danger outlines, invalid-field ring. Never text, never a fill |
| `inverseSurface` / `inverseOnSurface` | `#1B1A18` / `#FFFDF8` | `#F3F0E8` / `#1C1B19` | Snackbars, coach marks, tooltips |
| `scrim` | `#111110` at 46 % | `#000000` at 62 % | Behind sheets and dialogs |
| `letterbox` | `#0E0D0C` | `#0E0D0C` | Behind video that does not fill its box |
| `kraft` / `kraft2` | `#D8CCB2` / `#B9AA8B` | `#4A4436` / `#6B624E` | Illustration fills only, never next to marks |
| `halftone` / `halftoneStrong` | ink at 11 % / 20 % | bone at 10 % / 17 % | Spectrogram textures; never under text |
| `grainAlpha` | 0.06 (multiply) | 0.07 (screen) | Paper grain on `page` only |
| `drumPapers[4]` | `#F1EDE3` `#EEEBE4` `#EFE9DC` `#EBE8E1` | `#1B1A18` `#1D1C1A` `#201E1A` `#1A1A19` | Generated audio-only visual, chosen per clip (7.3) |
| `posterPapers[4]` | `#ECE7DC` `#E6E1D6` `#EFE6D6` `#E4E2DD` | `#24221F` `#27241F` `#22211F` `#29261F` | Poster fallback, chosen per clip (7.4) |

Why the page is `#F8F6EF` and not the web's `#F6F5F2`: the colour-vision-friendly light boundary hue `#3396CB` keeps
3:1 only on a page this light (3.05). Every neutral stays within OKLCH chroma 0.05 (gate G8), so no hue enters
chrome.

### 2.2 Material 3 `ColorScheme` mapping

`PhonoTheme` builds an explicit scheme (dynamic colour off: the palette is the identity, as in Artemi). Every
Material component used with its defaults then lands on a token above; the components in section 9 still set their
colours explicitly.

| M3 role | Light | Dark | Token |
|---|---|---|---|
| primary / onPrimary | `#1B1A18` / `#FFFDF8` | `#F3F0E8` / `#121110` | ink / onInk |
| primaryContainer / onPrimaryContainer | `#E5E0D4` / `#1B1A18` | `#312F2B` / `#F3F0E8` | deepSheet / ink |
| secondary / onSecondary | `#45433E` / `#FFFDF8` | `#D0CCC2` / `#121110` | ink2 / onInk |
| secondaryContainer / onSecondaryContainer | `#EFEBE2` / `#1B1A18` | `#272522` / `#F3F0E8` | galley / ink |
| tertiary / onTertiary | `#45433E` / `#FFFDF8` | `#D0CCC2` / `#121110` | ink2 / onInk (no third hue) |
| tertiaryContainer / onTertiaryContainer | `#EFEBE2` / `#1B1A18` | `#272522` / `#F3F0E8` | galley / ink |
| error / onError | `#C0362F` / `#FFFDF8` | `#FF6B5E` / `#121110` | critical / onInk (icons, outlines only) |
| errorContainer / onErrorContainer | `#EFEBE2` / `#1B1A18` | `#272522` / `#F3F0E8` | galley / ink: no red surfaces |
| background / onBackground | `#F8F6EF` / `#1B1A18` | `#121110` / `#F3F0E8` | page / ink |
| surface / onSurface | `#F8F6EF` / `#1B1A18` | `#121110` / `#F3F0E8` | page / ink |
| surfaceVariant / onSurfaceVariant | `#EFEBE2` / `#45433E` | `#272522` / `#D0CCC2` | galley / ink2 |
| surfaceTint | transparent | transparent | no tonal overlays (tonalElevation is always 0) |
| surfaceDim / surfaceBright | `#E5E0D4` / `#FFFDF8` | `#121110` / `#312F2B` | deepSheet / sheet (dark: page / deepSheet) |
| surfaceContainerLowest, Low, (default), High | `#FFFDF8` | `#1C1B19` | sheet: cards, sheets, nav bar, menus, dialogs are all sheets of the same paper |
| surfaceContainerHighest | `#E5E0D4` | `#312F2B` | deepSheet |
| outline / outlineVariant | `#7C786F` / `#DBD9D3` | `#858177` / `#2F2E2C` | outline / hairline |
| inverseSurface / inverseOnSurface / inversePrimary | `#1B1A18` / `#FFFDF8` / `#FFFDF8` | `#F3F0E8` / `#1C1B19` / `#121110` | snackbar, coach marks |
| scrim | `#111110` | `#000000` | alpha applied by the component (46 % / 62 %) |
| primaryFixed, primaryFixedDim, secondaryFixed…, onPrimaryFixed… (when the Material 3 version has them) | galley, deepSheet, ink, ink2 | same mapping | fixed roles mapped to neutrals |

### 2.3 Family palettes (the only hues)

The web's values, unchanged (`web/src/theme/tokens.css`). Each family is always **line style + icon + name**, colour
is the third cue (A-01). The user picks "Standard" or "Color-vision friendly" in Settings; "Show patterns as well as
colours" defaults to on with the colour-vision-friendly palette.

| Family (icon, line) | Standard light | Standard dark | CVD light | CVD dark |
|---|---|---|---|---|
| Reduction (chevrons down, solid) | `#3152A5` | `#4A6EC4` | `#005487` | `#1574B5` |
| t/d processes (scissors, dashed) | `#A26000` | `#C27405` | `#C07B00` | `#FFAD3C` |
| Assimilation (merge, double) | `#832C5C` | `#A44B79` | `#8A3103` | `#B94300` |
| Word boundary (link, wavy + ‿ arc) | `#009C96` | `#18A7A1` | `#3396CB` | `#6AC7FF` |
| Lexical contraction (shrink, dotted) | ink | ink | ink | ink |
| Other change (dashed circle, dash-dot) | inkMuted | inkMuted | inkMuted | inkMuted |

**Tints** (chip fills, the gap box): an OKLab mix of `sheet` and the family colour, 14 % light / 26 % dark (web
rule). Values (stored in `palette.json` and checked against the mix):

| Tint | Std light | Std dark | CVD light | CVD dark |
|---|---|---|---|---|
| reduction | `#E0E5EE` | `#283041` | `#DDE4E9` | `#22313E` |
| td | `#F3E6D9` | `#43311F` | `#F7EBDB` | `#503D27` |
| assimilation | `#EFDFE1` | `#3D2830` | `#F0E0D6` | `#42271C` |
| boundary | `#E3EFEA` | `#263C38` | `#E4EFF2` | `#31434D` |

Hard rules that keep the gates true on a textured, image-heavy UI:
1. Family marks (underlines, the ‿ arc, family-coloured icons) sit only on `page`, `sheet` or opaque overlays built
   from `sheet`, never on `galley`, `deepSheet`, video or a halftone (they fall to 2.46-2.91:1 there).
2. Inside a tinted chip the icon and the name are **ink**; the family colour is the 1.5 dp ring (decorative,
   redundant with icon + name). A family-coloured icon is used only on `page` or `sheet`.
3. Captions never sit on video. Landscape video is shown whole above the captions; portrait video gets an
   **opaque** `sheet` caption slip, never a translucent scrim.
4. The grain is masked under captions and slips (they paint solid `page`/`sheet`), so no mark sits on a darker grain
   pixel.

### 2.4 Articulator palette (mouth drawings)

The web's tract palette, unchanged (anatomy is told apart by lightness and outline, never by a family hue):

| Token | Light | Dark |
|---|---|---|
| tractAir | `#E7ECEF` | `#0E1417` |
| tractTissue | `#DDD4C1` | `#46402F` |
| tractBone (teeth) | `#FDFDFB` | `#C2BCAE` |
| tractTongue / tractTongueDeep | `#C9755F` / `#8F4633` | `#C06B53` / `#7C3A2A` |
| tractLip | `#D8A695` | `#99624F` |
| tractLine | `#5C5A54` | `#A8A59C` |
| tractFlow | `#6E8FA8` | `#8FB3CC` |
| tractAccent (closure point, label dots) | `#1A1A1A` | `#F5F5F3` |

### 2.5 Illustration palette

Illustrations, the poster fallback and brand art use only these tokens (`palette.json` → `illustration`): `ink`,
`ink2`, `inkMuted`, `paper` (= sheet), `page`, `galley`, `deepSheet`, `kraft`, `kraft2`, and the tract tokens for
anatomy; ink at an opacity for halftone and hatching. Makers draw in the light column; `svg2vd.py --palette
palette.json --theme dark` produces the dark twin by swapping each light hex for its dark token (section 13).

### 2.6 Launcher and splash colours

| Item | Value |
|---|---|
| Launcher tile (background layer) | `#D2C3A3` launcher kraft, drum rules and halftone in ink at 13 % / 24 %, a 22 % white sheen at the top left |
| Launcher mark (foreground) | `#1B1A18` ink |
| Monochrome layer, notification icon | single colour (alpha only) |
| Splash window background | `#F8F6EF` (light) / `#121110` (dark) = page |
| Splash mark | `#1B1A18` (light) / `#F3F0E8` (dark) |

---------------------------------------------------------------------------------------------------------------

## 3. Contrast and palette gates (verified)

`python3 android/design-src/tools/check_palette.py` re-runs the web's `palette.test.ts` gates on `palette.json`
with the same maths (culori 4.0.2: WCAG luminance, OKLab, Machado 2009 simulations at severity 1.0 and 0.6) and adds
the mobile gates. It exits 1 on any failure. Current result: **ALL GATES PASS (499 checks)**. WP-03 ports it to a
JVM unit test over `Color.kt` (or runs it in CI against the JSON that generates `Color.kt`).

| Gate | Rule | Source |
|---|---|---|
| G1 | On `page`, `sheet`, `galley`, `deepSheet`: ink ≥ 7, ink2 ≥ 4.5, inkMuted ≥ 4.5, focus ≥ 3, outline ≥ 3, good ≥ 3, critical ≥ 3 | web gate 1 + mobile |
| G2 | onInk on ink ≥ 7; inverseOnSurface on inverseSurface ≥ 7 | mobile |
| G3 | Each family ≥ 3:1 on every surface marks may sit on (`page`, `sheet`); ink on each tint ≥ 7; stored tint = OKLab mix (±1/255) | web gate 2 + mobile |
| G4 | Every family pair ≥ 15 ΔEok, normal vision | web gate 3 |
| G5 | Every pair under protan, deutan, tritan at severity 1.0 and 0.6: ≥ 8 ΔEok (Standard), ≥ 12 (colour-vision friendly) | web gate 4 |
| G6 | Chroma ≥ 0.10; lightness in [0.43, 0.77] light, [0.48, 0.67] dark Standard, [0.48, 0.82] dark CVD | web gate 5 |
| G7 | Teeth lightness differs from lip, tongue, deep tongue by > 0.15 | web gate 6 |
| G8 | Hue reservation: every non-family token has OKLCH chroma ≤ 0.05 (except focus, good, critical) | mobile (A-02) |
| G9 | Overlays composited over white, mid-grey and black frames: caption slip (opaque sheet) keeps text and marks; media chips (sheet at 94 %) and coach marks (inverse at 96 %) keep ink ≥ 7 | mobile (night-stage graft) |
| G10 | Illustration ink on page, sheet and kraft ≥ 3; launcher mark on tile ≥ 7; tile ≥ 3 against dark and black share sheets and ≥ 1.5 against white; splash mark ≥ 7 | mobile (F-25-AC1) |
| G11 | Pitch line on every drum paper ≥ 7; drum papers neutral | mobile (F-25) |

Measured text and control contrast (WCAG ratio):

| Theme · surface | ink | ink2 | inkMuted | focus | outline | good | critical |
|---|---|---|---|---|---|---|---|
| Light · page | 16.08 | 9.14 | 6.36 | 5.37 | 4.07 | 4.91 | 5.10 |
| Light · sheet | 17.11 | 9.72 | 6.77 | 5.71 | 4.33 | 5.23 | 5.42 |
| Light · galley | 14.62 | 8.30 | 5.78 | 4.88 | 3.70 | 4.47 | 4.63 |
| Light · deepSheet | 13.20 | 7.50 | 5.22 | 4.41 | 3.34 | 4.03 | 4.18 |
| Dark · page | 16.56 | 11.76 | 7.40 | 9.03 | 4.85 | 7.90 | 6.75 |
| Dark · sheet | 15.11 | 10.73 | 6.75 | 8.24 | 4.43 | 7.21 | 6.16 |
| Dark · galley | 13.42 | 9.53 | 5.99 | 7.32 | 3.93 | 6.40 | 5.47 |
| Dark · deepSheet | 11.73 | 8.33 | 5.24 | 6.39 | 3.44 | 5.59 | 4.78 |

onInk on ink: 17.11 (light), 16.56 (dark). Snackbar text: 17.11 / 15.11.

Family marks on page / sheet (and, for the record, on the forbidden galley):

| Palette · theme | reduction | td | assimilation | boundary | min ink on tint | min pair ΔEok · worst simulated |
|---|---|---|---|---|---|---|
| Standard light | 6.74 / 7.17 (6.13) | 4.61 / 4.91 (4.19) | 7.83 / 8.33 (7.12) | 3.13 / 3.33 (2.85) | 13.47 | 18.3 · deutan 12.1 |
| Standard dark | 3.88 / 3.54 (3.14) | 5.20 / 4.75 (4.22) | 3.47 / 3.16 (2.81) | 6.36 / 5.80 (5.15) | 10.34 | 18.2 · deutan 9.3 |
| CVD light | 7.42 / 7.89 (6.74) | 3.20 / 3.40 (2.91) | 7.70 / 8.19 (7.00) | 3.05 / 3.25 (2.77) | 13.50 | 21.0 · tritan 18.3 |
| CVD dark | 3.77 / 3.44 (3.05) | 10.14 / 9.26 (8.22) | 3.47 / 3.17 (2.81) | 10.08 / 9.20 (8.17) | 9.04 | 25.4 · tritan 23.8 |

Overlays (G9): media chip ink over white / grey / black frames 17.12 / 16.03 / 14.97 (light), 12.82 / 14.12 / 15.37
(dark); coach mark ≥ 13.85. Launcher (G10): mark on tile 10.00; tile vs white 1.74, vs `#1C1B1F` 9.85, vs black
12.08. Splash 16.08 / 16.56. Drum papers: pitch line ≥ 14.21.

What the gates do not cover, and the rule that does: disabled content (45 % opacity, exempt by WCAG, always paired
with a reason when it matters), decorative textures (never under text), video frames (nothing sits on them except
opaque overlays).

---------------------------------------------------------------------------------------------------------------

## 4. Typography

### 4.1 Faces and files (all SIL OFL 1.1, GPL-compatible, bundled with licence text)

| Face | Role | File (`res/font/`) | Source |
|---|---|---|---|
| **Atkinson Hyperlegible Next** (variable, wght 200-800) | All UI: body, captions, buttons, labels, numbers | `atkinson_hyperlegible_next.ttf` (from `AtkinsonHyperlegibleNext[wght].ttf`) | google/fonts `ofl/atkinsonhyperlegiblenext` (upstream googlefonts/atkinson-hyperlegible-next); the web's UI face, chosen for low vision |
| **Charis 7.000** (Regular, Bold), unmodified | Every IPA string | `charis_regular.ttf`, `charis_bold.ttf` (renamed files of `Charis-Regular.ttf`, `Charis-Bold.ttf`, bytes unchanged) | SIL Global release 7.000; the only face that places t̚ ɾ̃ n̩ ŋ̍ ᵻ ‿ correctly. Not subset, not converted (Reserved Font Name) |
| **Newsreader** (variable, opsz 6-72, wght 200-800, roman + italic) | Display voice only: masthead, screen titles, the studied word, big numbers, step numerals, italic accents | `newsreader.ttf`, `newsreader_italic.ttf` (from `Newsreader[opsz,wght].ttf`, `Newsreader-Italic[opsz,wght].ttf`) | google/fonts `ofl/newsreader` (Production Type) |

Licences go to `assets/licenses/` (`atkinson-hyperlegible-next-OFL.txt`, `charis-OFL.txt`, `newsreader-OFL.txt`, with
the provenance manifest `FONTS.txt`) and to `THIRD-PARTY-NOTICES.md` (the "7.3 Fonts and icons" subsection WP-03
owns, work-plan 3.2). Variable fonts load from `res/font` on API 26+ with
`Font(R.font.x, weight, variationSettings = FontVariation.Settings(FontVariation.weight(w), FontVariation.Setting("opsz", size)))`.

**Glyph rule** (the font files stay unmodified; nothing is left to the system fallback font). Checked against the
staged files' character maps: Atkinson Hyperlegible Next and Newsreader have no arrows (U+2190 "←", U+2192 "→",
U+2194 "↔"), no "✓" (U+2713), no "✕" (U+2715) and no "▾" (U+25BE); Atkinson also lacks U+00AD (soft hyphen) and
U+00B5 (µ), Newsreader U+2009 (thin space). Charis has the three arrows, drawn at a weight that sits well beside
Atkinson. So:

| Where the glyph appears | How it is drawn |
|---|---|
| Inside IPA (`/ˈwɔtɚ/ → [ˈwɔɾɚ]`, the "≠ changed · /t/ → [ɾ]" pill) | `IpaText` (4.3): the whole string is Charis already |
| Inside running UI text: change names ("nt → n"), contraction chips ("wanna ← want to"), examples in prose ("“did you” → “didja”") | the arrow alone is set in Charis (same size and weight as the text) through a span; the string itself is unchanged, so TalkBack reads what it read before |
| Trailing arrow of an action label ("All clips →", "See Insights →", "Next question →", "Next change →", "Learn more →", "Learn about {change} →") | not text: the `ic_arrow_right` icon (18 dp, ink, after the word, 4 dp gap), decorative (no content description); the string resource holds the words only |
| "▾" of a menu or the feed's mode button ("Mix ▾", "Newest first ▾") | `ic_chevron_down` (18 dp) after the word, decorative (the control's role says it opens a menu) |
| "✓" in a status ("✓ Seen", "✓ played 2×", "Clip data ✓") | `ic_step_done` (16 dp); decorative where the words carry the state ("Seen", "played 2×"); content description "ready" in the per-clip download line, where it replaces a word |
| "✕" on a removable chip ("Only: flapping ✕") | `ic_close` (16 dp) at the chip's end; the chip's action label comes from the spec ("Practice everything instead" is the visible alternative) |
| U+00AD, U+00B5, U+2009 | never in a string resource (a plain space in display numbers; "µs" is not used) |

Compose implementation note (`:core:designsystem`, WP-03): `PhonoText(text: String, style: TextStyle, …)` is the
`Text` every feature uses for UI copy; it builds an `AnnotatedString` once per string (`remember(text)`) that wraps
each U+2190-U+2194 in `SpanStyle(fontFamily = PhonoFonts.Charis)` and passes the rest through untouched (`IpaText`
stays separate). Trailing arrows, chevrons, checks and crosses come from the components that own them:
`PhonoButton(…, trailingIcon = PhonoIcons.ArrowRight)`, `LinkButton(…, trailingArrow = true)`, `MenuButton` (chevron),
`StatusLine` (check), `FilterChip(onRemove)` (cross), each icon with `contentDescription = null` unless the table
says otherwise. Two JVM tests guard the rule: one fails when a value in `strings.xml` contains "▾", "✓", "✕",
U+00AD, U+00B5, U+2009 or ends in " →"; one reads the cmap of the bundled TTFs and fails when a character of
`strings.xml` (outside IPA) is in neither the UI face nor, for the arrows, Charis. The mockups follow the same rule
(`kit/theme.css` maps U+2190-2194 to Charis behind both UI faces).

Why a third face: Newsreader is the "print" voice of the phonautograph page; it pairs with Charis without imitating
it. It is fenced in: never for body, labels, buttons or anything under 15 sp; the UI stays Atkinson exactly as on the
web, and IPA stays Charis.

### 4.2 Material 3 type scale (all sizes in sp, scale with the system font size)

| Role | Face, weight | Size / line | Use |
|---|---|---|---|
| displayLarge | Newsreader 650, opsz 44 | 44 / 48 | Big numbers ("7 in 10"), "Done for today" |
| displayMedium | Newsreader 650, opsz 36 | 36 / 40 | Tab-root titles ("Learn", "Practice"), Today hero, Welcome titles |
| displaySmall | Newsreader 600, opsz 30 | 30 / 36 | Empty-state titles ("No computer answered."), Home wordmark |
| headlineLarge | Newsreader 600, opsz 28 | 28 / 34 | Top app bar titles of secondary destinations, question titles, sheet titles |
| headlineMedium | Newsreader 600, opsz 22 | 22 / 28 | Section heads ("Keep watching", "What you met") |
| headlineSmall | Newsreader 600, opsz 20 | 20 / 26 | Sub-sections ("How to hear it") |
| titleLarge | Atkinson 700 | 17 / 24 | Card titles, status lines, request states |
| titleMedium | Atkinson 700 | 16 / 22 | List titles, clip titles, option titles |
| titleSmall | Atkinson 700 | 15 / 20 | Small titles inside cards |
| bodyLarge | Atkinson 400 | 16 / 24 | Body text |
| bodyMedium | Atkinson 400 | 15 / 22 | Ledes, cues, secondary text |
| bodySmall | Atkinson 400 | 14 / 20 | Meta lines, captions of charts, time stamps |
| labelLarge | Atkinson 600 | 16 / 20 | Buttons |
| labelMedium | Atkinson 600 | 14 / 18 | Chips, badges, rail labels, tabs |
| labelSmall | Atkinson 700, uppercase, +0.07 em | 14 / 18 | Kickers ("LISTEN FOR", group heads). Raised from Material's 11 sp |

Extra styles (`PhonoTheme.type`):

| Style | Face | Size / line | Use |
|---|---|---|---|
| spotlightWord | Newsreader 600 | 34 / 40 | The studied word on the slip |
| lessonWord | Newsreader 600 | 40 / 44 | The word heading the lesson |
| captionCurrent | Atkinson 500 | 20 / 28, in 46 dp line boxes | The current phrase; every word is a ≥ 48 dp target |
| captionDim | Atkinson 400 | 15 / 21, inkMuted | Previous and next phrase |
| accentItalic | Newsreader 500 italic | 16-18 / 24 | Datelines, step numerals, counts beside kickers, "Pitch of this phrase" |
| numeric | Atkinson + `tnum` | as the role | Times, percentages, counts (tabular everywhere) |
| ipaInline | Charis 400 | 1.12 × the surrounding size, letter spacing 0 | IPA inside text |
| ipaSlip / ipaForm / ipaSort / ipaSymbol | Charis 400 | 21 / 25 / 27 / 40 | Slip line, Compare forms, type sorts, sound card |

Rules: the minimum size is **14 sp** everywhere; nothing has a fixed height that clips text; display sizes use
Android 14 non-linear scaling (they grow less than body text); sentence case; numbers tabular.

### 4.3 IPA

- Always through one `IpaText` composable (`:core:ui`): Charis for the whole string, never per-glyph fallback
  (combining marks would misplace), `/dictionary/` vs `[what was said]`, `→` between them, ‿ for links.
- Accessibility text built from symbol names: "[ɾ]: voiced alveolar tap" (A-05), with a play action where a sound
  can be played.
- IPA scales with the font scale like any text.

---------------------------------------------------------------------------------------------------------------

## 5. Shape, spacing, elevation, texture

### 5.1 Shape (`Shapes`)

| Role | Radius | Use |
|---|---|---|
| extraSmall | 4 dp | Slip top corners, inner fills, playing-word fill (6 dp) |
| small | 8 dp | Posters, thumbnails, word highlight |
| medium | 12 dp | Buttons, fields, segmented controls, wells, notices, icon wells |
| large | 14 dp | Cards, slips (bottom corners), sound card |
| extraLarge | 28 dp | Bottom sheets (top corners), dialogs |
| full | 50 % | Chips, badges, pills, the rail circles, the nav pill |

Brand shapes: `PerforatedTopShape` (the slip: 8 dp perforation, holes 7 dp every 12 dp, `GenericShape`), `SortShape`
(10 dp radius + a 2 dp nick line under the base), the stack (two copies of a card offset 5/6 and 9/11 dp, rotated
0.6° and 1.1°), crop marks (14 dp corners, 1 dp ink at 55 %, 7 dp outside the card).

### 5.2 Spacing (4 dp grid)

`2, 4, 6, 8, 10, 12, 14, 16, 20, 24, 32`. Screen gutter 16 dp (feed card 14 dp). Card padding 14-16 dp. Section gap
20 dp; card grid gap 10-12 dp; chip gap 8 dp. Touch targets ≥ 48 × 48 dp; quiz answers ≥ 56 dp tall (72 in the
mockups); rail items 64 × 60 dp.

### 5.3 Elevation and depth

Flat and paper-like; Material tonal elevation is always 0.
- **Light**: a sheet sits on paper: 1 dp hairline ring + soft drop (y 10 dp, blur 22 dp, spread −14 dp, ink at
  38 %). Floating things (dialogs, coach marks, the paused-context pill): ring + y 12 dp, blur 30 dp, ink 45 %.
  Compose: `Modifier.shadow(6.dp, shape, ambientColor = Color.Transparent, spotColor = ink.copy(alpha = .38f))`
  plus `border(1.dp, hairline, shape)`.
- **Dark**: no shadows; a 1 dp ring (`hairline`, or `lineStrong` for floating) and the lighter surface step.
- The primary button has a 3 dp darker bottom "bite" (letterpress), drawn inside its shape.
- Depth also comes from the **stack** behind hero cards (Today's set, summary card, question card).

### 5.4 Texture

| Texture | How | Where | Never |
|---|---|---|---|
| Paper grain | Fractal noise generated once from a fixed seed into a tileable 256 px `ImageBitmap`; drawn on `page` with alpha `grainAlpha`, multiply (light) / screen (dark) | Screen ground | Under captions, slips, marks: those paint solid `page`/`sheet` |
| Long fibres | Second, sparser noise (stretched x), 1.2 × grain alpha | Home, Welcome, tab roots | Feed |
| Spectrogram halftone | Dot screen whose radius follows an energy field (three formant bands drifting, gated by syllables), seeded per clip (FNV-1a 32 of the job id) or per screen; cell 7-8 dp; `halftone`/`halftoneStrong` | Today hero corner, lesson sheet corner, question card, audio drum, poster fallback, illustrations | Under any text or icon; under family marks |
| Drum rules | Hairline ink at 13 % every ~26 dp | Audio drum, launcher tile, Home no-clips art | |
| Double rule | 2 dp ink + 3 dp gap + 1 dp ink | Under the masthead / tab-root titles, above the bottom bar | |
| Stamp "NEW" | Ink 2 dp outline, uppercase 14 sp bold, rotated −8°, 14 % opacity | New on this phone card | As information carrier (the text says it) |

All textures are cached bitmaps or a single `drawBehind`; they are invisible to accessibility; under battery saver
the fibres and halftones of non-content surfaces are skipped (the audio drum keeps its halftone).

---------------------------------------------------------------------------------------------------------------

## 6. Iconography

- **Set**: Lucide v1.51.0 (the web's version; ISC, Feather-derived glyphs MIT), exported to VectorDrawables. 24 ×
  24 grid, round caps and joins, stroke 2 for UI glyphs, **2.25 for family and practice glyphs** (the web's
  FamilyIcon weight). No `material-icons-extended` (architecture 2.4).
- **Rule**: an icon is never the only cue. It sits beside a word, or carries a content description; status,
  connection and family icons always travel with words.
- **Colour**: ink by default (tinted at runtime: VDs are `#FF000000`, Compose `Icon(tint = …)`); a family-coloured
  icon only on `page`/`sheet`; `critical` and `good` only for the status uses in section 2.1.
- **Sizes**: 14-16 dp inline (pattern icons after words, chips), 18-20 dp in buttons and rows, 22-24 dp in the
  rail, bars and top bars, 28 dp in empty-state wells.
- **Motion**: only `loader-circle` rotates (when motion is allowed); nothing else animates.
- **Sources**: `tools/lucide_icons.py --lucide <lucide-static or lucide-react icons dir> --svg-dir …` writes one SVG
  per inventory entry; `svg2vd.py` converts it (all 122 round-trip; 121 within 0.02 RMSE, and `ic_site_x` at 0.0204 against its
  source, a residue of Chrome's arc rasterization that `roundtrip.py` records as a per-file tolerance with its
  evidence; section 13).

The full inventory (resource name, Lucide name, stroke, meaning, where used) is `design-src/icons.json`. Summary:

| Group | Resources (Lucide glyph) |
|---|---|
| Families (2.25) | `ic_family_reduction` (chevrons-down), `ic_family_td` (scissors), `ic_family_assimilation` (merge), `ic_family_boundary` (link-2), `ic_family_lexical` (shrink), `ic_family_other` (circle-dashed) |
| Practice (2.25) | `ic_practice_produce` (mic) "Safe to produce", `ic_practice_recognize` (ear) "Recognize only" |
| Navigation | `ic_tab_home` (house), `ic_tab_watch` (square-play), `ic_tab_learn` (graduation-cap), `ic_tab_practice` (target), `ic_settings` (settings-2), `ic_back` (arrow-left), `ic_close` (x), `ic_more_horizontal` (ellipsis), `ic_more_vertical` (ellipsis-vertical), `ic_chevron_*` (right/left/down/up), `ic_arrow_right`, `ic_search`, `ic_help` (circle-help), `ic_external` (external-link), `ic_insights` (chart-column), `ic_ipa_chart` (grid-3x3), `ic_glossary` (book-a), `ic_legend` (book-open), `ic_tour` (route) |
| Notices | `ic_info` (info), `ic_caution` (triangle-alert), `ic_tip` (lightbulb) |
| Request status | `ic_status_preparing` (hourglass), `ic_status_waiting` (clock), `ic_status_sending` (send), `ic_status_analyzing` (loader-circle), `ic_status_downloading` (download), `ic_status_ready` (circle-check), `ic_status_attention` (circle-alert), `ic_status_cancelled` (circle-slash), `ic_step_done` (check), `ic_step_skipped` (skip-forward) |
| Connectivity | `ic_conn_connected` (monitor-check), `ic_conn_checking` (radar), `ic_conn_unreachable` (monitor-off), `ic_conn_offline` (wifi-off), `ic_conn_not_on_wifi` (signal), `ic_conn_not_connected` (unplug), `ic_conn_different` (monitor-x), `ic_conn_connect_again` (key-round), `ic_conn_update` (circle-arrow-up), `ic_conn_low_space` (hard-drive), `ic_sync` (refresh-cw), `ic_pairing` (monitor-smartphone), `ic_wifi` (wifi), `ic_address` (router), `ic_background_limited` (battery-warning) |
| Availability | `ic_avail_on_phone` (smartphone), `ic_avail_video` (film), `ic_avail_on_computer` (monitor), `ic_avail_getting_ready` (monitor-cog), `ic_keep` (pin) |
| Sources (generic, never logos) | `ic_site_youtube` (tv-minimal-play) "YouTube", `ic_site_x` (at-sign) "X", `ic_site_other` (globe) "Other website", `ic_site_phone_file` (file-video), `ic_site_phone_audio` (file-audio) "Phone file", `ic_site_computer` (hard-drive-download) "Computer" |
| Player and rail | `ic_play`, `ic_pause`, `ic_replay` (rotate-ccw), `ic_speed` (gauge), `ic_slow` (snail), `ic_loop` (repeat), `ic_loop_phrase` (repeat-1), `ic_shadow` (mic-vocal), `ic_save` (bookmark), `ic_saved` (bookmark-check), `ic_now` (volume-2), `ic_hold` (hand), `ic_track` (audio-lines), `ic_audio_only` (headphones), `ic_captions` (captions), `ic_listen_first` (eye-off), `ic_phrase_details` (chart-spline), `ic_record` (circle-dot), `ic_stop` (square), `ic_alternate` (arrow-right-left), `ic_clip_page` (clapperboard), `ic_leave_out` (list-x), `ic_mode_mix` (shuffle), `ic_mode_in_order` (list-ordered) |
| Didactic | `ic_intonation_rises` (trending-up), `ic_intonation_falls` (trending-down), `ic_intonation_flat` (move-right), `ic_quiz_which_change` (shapes), `ic_see_mouth` (eye), `ic_obs_ending` (flag), `ic_obs_peak` (mountain), `ic_obs_range` (move-vertical), `ic_obs_length` (timer), `ic_obs_pauses` (circle-pause), `ic_table` (table-2), `ic_chart` (chart-line) |
| Requests | `ic_add` (plus), `ic_paste` (clipboard-paste), `ic_choose_file` (folder-open), `ic_link` (link), `ic_remove` (trash-2), `ic_cancel_analysis` (circle-stop), `ic_log` (scroll-text), `ic_details` (file-text), `ic_share` (share-2) |
| Settings | `ic_appearance_system` (monitor), `ic_appearance_light` (sun), `ic_appearance_dark` (moon), `ic_colors` (palette), `ic_motion` (waves), `ic_notifications` (bell), `ic_notifications_off` (bell-off), `ic_licences` (scale) |

Brand glyphs (not Lucide): the stylus P (`ic_launcher_*`, `ic_notification`, `splash_mark*`), section 7.1.

### 6.1 Icons added by change request CR-01 (v1-complete)

Two new glyphs, from the same Lucide 1.51.0 and through the same tools; everything else CR-01 shows reuses a v1
resource. They are staged in `design-src/cr01/` (inventory `cr01/icons.json`, masters `cr01/svg/icons/`, vectors
`cr01/export/res/drawable/`), not in the v1 export: WP-03's conversion test freezes that export at 173 files and 122
icons (work-plan WP-03), and CR-01 must not move a number WP-03 is built against. The integrator of wave 5, WP-26,
converts them in change request CR-01-A2, the first commit after the v1-core gate (work-plan 4.7 and WP-26;
architecture 25.5 C-42). It uses WP-03's conversion script to put them into `:core:designsystem`, adds their
`PhonoIcons` entries, and updates `bundled.json` and notices 7.3. WP-34's screens then use them.

| Resource | Lucide | Stroke | Meaning | Where |
|---|---|---|---|---|
| `ic_undo` | undo-2 | 2 | Withdraw a deletion from your computer: the clip stays as it was | "Undo" on a waiting deletion and "Keep the clip" after a refused one (one effect, one glyph: both drop the deletion) |
| `ic_browser` | app-window | 2 | The browser client on your computer | the "Browser" node of the reach strip (9.26) |

Reused v1 resources and their new uses are listed in `cr01/icons.json` → `reuses`: `ic_remove` (trash-2) is the
deletion's glyph everywhere (its button, its badge, its Requests title); `ic_avail_on_phone` / `ic_avail_on_computer`
(smartphone / monitor) are the two place heads and place marks; `ic_pairing` (monitor-smartphone) is the shared-progress
line; `ic_status_waiting`, `ic_status_sending`, `ic_status_attention`, `ic_save`, `ic_info` and `ic_step_done` keep
their v1 meaning in the new lines.

Why "Delete from your computer…" keeps the trash glyph and not a monitor: the trash is the danger button's anatomy
(9.1) and the glyph the browser uses for the same act (`Trash2`, "Delete…", `web/src/features/library/LibraryPage.tsx`),
so one act looks the same in both clients; the only monitor-with-a-cross in Lucide, monitor-x, already means "A different
computer answered" (`ic_conn_different`), which is one of the reasons a deletion can be waiting, so the two would meet on
one screen with two meanings. The place is told by the block head, the words and the dialog's reach strip instead.

Verified: `lucide_icons.py --inventory cr01/icons.json` writes both masters from the web's `lucide-react` 1.51.0;
`svg2vd.py` (precision 3, byte-identical to `export_vectors.py`'s icon output, checked on `ic_remove`) converts them;
`lint_vectors.py cr01/export/res` passes (2 paths, longest 144); `roundtrip.py` passes (RMSE 0.0001 and 0.0016). The
mockups' icon table (`kit/icons.js`) gains `undo-2` and `app-window` as extras; the other 129 entries are unchanged.
Licence: the same Lucide ISC/MIT text already in `assets/licenses/lucide-LICENSE.txt`.

---------------------------------------------------------------------------------------------------------------

## 7. Brand art, illustrations and generated visuals

### 7.1 The stylus P: launcher icon, splash, notification

Master geometry: `design-src/svg/brand/mark-stylus-p.svg` (100 × 100 box): one stroke
`M38 52H62C76 52 82 44 82 35C82 25 75 18 62 18H38V78C38 84 41 87 45 85C50 82 51 72 56 72C61 72 61 84 66 84C70 84 71 77 74 76`,
width 10, round caps and joins, plus the blot `circle(80, 75.4, r 7.2)`.

| Asset | Geometry | Colours | Source |
|---|---|---|---|
| Adaptive icon background | 108 × 108 dp, full bleed | launcher kraft `#D2C3A3`, white sheen (radial, 22 % → 0 at the top left), 5 drum rules (y 34-74, ink 13 %, 0.9 dp), halftone corner (bottom right, ink 24 %: 64 dots, written as four paths of the same paint to stay under lint's `VectorPath` limit, 12.3) | `svg/brand/launcher-background.svg` |
| Adaptive icon foreground | the mark scaled 0.66 (mark centre (60, 52) placed at (54.2, 54.6); stroke 6.6 dp): inside the 66 dp safe circle, farthest point 29.4 dp from the centre | ink `#1B1A18` | `svg/brand/launcher-foreground.svg` |
| Monochrome (themed icons, API 33+) | the foreground geometry only | single colour | `svg/brand/launcher-monochrome.svg` |
| Notification icon | 24 dp, the mark scaled 0.27, stroke 10.5 | white on transparent (alpha only) | `svg/brand/notification.svg` |
| Splash icon (API 31+ and the compat library) | 288 dp canvas, mark inside the 192 dp circle (scale 1.78); the drawable keeps the 288 viewport and declares 144 × 144 dp (lint `VectorRaster`, 12.3): the platform and the compat library both draw it into their 288 dp icon view, so the shown size is unchanged | ink on page (light), bone on soot (dark) | `svg/brand/splash-mark.svg` |
| Animated splash | the stroke drawn by `trimPathEnd` 0 → 1 over 600 ms (standard easing), then the blot fades in (fillAlpha 0 → 1, 600 → 700 ms); runs once | as the splash icon | AVD made from the splash VD |

Why kraft and not soot: the soot tile of the concept disappeared on dark share sheets (judge 2). The kraft tile is
the one light, warm, unsaturated tile in a row of saturated circles; on white sheets the ink P carries the figure
(mark on tile 10:1), on dark sheets the tile does (9.85:1). Renders: `docs/design/26-brand-*.png`.

### 7.2 Illustration set ("engraved ink")

Style: 2.5-3 dp ink monoline with round caps; kraft flat fills; halftone dots for light; hatching at **30° and
−35°** for shade (never the 45°/135° lines or dots that mean t/d, boundary and assimilation in charts); Newsreader
italic only if a sound effect letter is needed (as paths); the trace as narrator. No family hue, ever. Light and dark
from one vector (2.5). Decorative: hidden from TalkBack; the empty state's title and sentence carry the meaning.

| Resource | Purpose (spec) | Composition | Size (viewport) |
|---|---|---|---|
| `ill_welcome_listen` | Welcome 1 "Hear English as it's really spoken" | A phonautograph horn collects a voice; the stylus writes it as a trace on ruled paper; the line ends as a dotted wait | 380 × 214 |
| `ill_welcome_clips` | Welcome 2 "Your clips become shorts"; Home first run | The phone hands the computer a kraft envelope with a wave on it; the monitor shows a halftone and a trace | 380 × 214 |
| `ill_welcome_daily` | Welcome 3 "A few minutes a day" | A short stack of slips tied with the trace, a small hourglass, a check token | 380 × 214 |
| `ill_feed_empty` | Watch, first run (F-06) | An empty screen on a stand; the trace waits as a dotted line from a nib | 380 × 214 |
| `ill_home_no_clips` | Home, paired, computer has no clips (F-03) | An empty phonautograph drum, rules only | 380 × 214 |
| `ill_requests_empty` | Requests empty (F-20) | An empty in-tray; a quiet, flat trace | 380 × 214 |
| `ill_no_examples` | Learn, no examples (F-15, F-16) | A magnifier over a flat line | 380 × 214 |
| `ill_practice_empty` | Practice, nothing yet (F-17) | An ear with a dotted trace waiting to be filled | 380 × 214 |
| `ill_no_computer` | Connect: no computer answered (F-02); Connection details | The wave leaves the phone, decays and goes flat before a sleeping monitor; a mug; a question mark | 380 × 214 |
| `ill_saved_empty` | Saved empty (F-09) | A kraft bookmark ribbon over a blank slip | 380 × 214 |
| `ill_set_done` | Summary card at the end of a set (F-08) | Stacked slips with a round check stamp | 200 × 120 |
| `ill_spot_practice` | Home Practice card; Practice title | An ear and a small target | 96 × 72 |
| `ill_spot_insights` | Home Insights teaser | Hatched bars under a dashed reference line and a magnifier | 96 × 72 |

The "Size" column is the viewport. Drawables larger than 200 dp a side declare half their viewport as intrinsic
size (380 × 214 → 190 × 107 dp; lint `VectorRaster`, 12.3); they are always drawn at the size the layout gives them
(full width at 380:214, 9.21), so nothing is rasterized small.

Reference compositions: `design-src/mockups/kit/art.js` (rendered in `docs/design/25-illustrations-*.png`). They
fix the composition and the vocabulary; the makers redraw them as clean vectors (asset brief).

### 7.3 Generated audio-only visual: the drum (F-06 item 7, F-25)

A composable (`AudioOnlyVisual`, architecture 11.6) with the footprint of a 16:9 video (full width × 232-236 dp),
deterministic per clip, never using family hues. Grammar (render: `docs/design/05-*.png`):

1. **Paper**: `drumPapers[hash % 4]` (FNV-1a 32 of the job id, the same hash as the architecture).
2. **Drum rules**: 4 horizontal hairlines (ink 13 %), evenly spaced in the drawing area.
3. **Spectrogram halftone**: cell 8 dp, `halftone` colour, an energy field of three formant bands whose centre and
   sway come from the hash; the envelope comes from the current phrase's word timings (syllable gating), so the
   texture changes per phrase but is the same every time for the same phrase.
4. **Pitch line**: the current phrase's `f0_track` in semitones around the clip median, mapped to the drawing area
   (top 18 % to bottom 26 % of the space above the strip). Played part: ink ribbon 3-11 dp (width follows intensity
   when present, else a gentle fixed swell); part to come: dotted hairline (1 dp dots every 6 dp, inkMuted);
   unvoiced gaps longer than 60 ms break the line (no interpolation). Nib at the playhead (8 dp dot + 12 dp halo at
   25 %). Needle: a 2 dp ink line at 70 % from the top to the strip.
5. **Word strip**: an opaque `sheet` band, 44 dp, at the bottom with each word centred under its time span (14 sp,
   ink2); the current word bold ink with a 2 dp underline. Colliding labels: keep the current word, then drop
   labels from the far ends; no label sits on the halftone.
6. **Chips** on overlay plates (`sheet` at 94 %): "Audio only" (headphones) top left; italic "Pitch of this phrase"
   top right.
7. **No measurable pitch** in the phrase: the line is replaced by a flat dotted hairline and the right chip reads
   "No measurable pitch here".
8. Motion: only playback moves the nib, the ribbon and the needle; Reduce motion changes nothing else (there is no
   decorative animation).

### 7.4 Poster fallback (F-25)

A composable for video clips whose poster has not arrived or does not exist: `posterPapers[hash % 4]` ground, the
clip's seeded halftone (`halftoneStrong`), two crop marks, the title in Newsreader 600 15 sp on an opaque paper
plate at the bottom left (two lines, then ellipsis: the title is also in the row's text), and the duration or kind
chip on an overlay plate. It is generated once and cached (`cacheDir/posters/`, architecture 11.6). It is drawn by
`PosterArt` (`:core:ui`, WP-03, 12.2), the one composable that shows a clip's picture everywhere (Home shelf and Keep
watching, Library rows, the Clip page hero, the short's media area): the poster when there is one, else this
fallback for a video clip, else the audio art (a finished trace with a blot on drum rules) for an audio-only clip.
Home and the Library cannot use a `:feature:watch` composable (architecture 2.2 rule 1), so it does not live there.

### 7.5 The mouth (articulator) drawings

The web's 2D midsagittal model, ported (v1-complete, architecture `:core:articulator`): tract palette only (2.4),
told apart by lightness and outline, a single ink accent at the closure point. The sound card shows a still
(112 × 104 dp) of the selected sound; lesson step 4 and the phenomenon page animate it with playback (content: it
keeps following the audio with Reduce motion, never moves without playback).

---------------------------------------------------------------------------------------------------------------

## 8. Motion: "the pen moves, the paper doesn't"

Tokens (`PhonoMotion`): fast 120 ms · standard 220 ms · slow 360 ms · draw 600-700 ms. Easing: standard
`cubic-bezier(0.2, 0, 0, 1)` (the web's `--ease`); enter (sheets) emphasized-decelerate `(0.05, 0.7, 0.1, 1)`;
exit emphasized-accelerate `(0.3, 0, 0.8, 0.15)`; selection: spring, damping 0.85, stiffness 400 (low bounce). No
infinite decorative loops anywhere (A-07; also battery and test determinism).

| Moment | Motion | With Reduce motion or animator scale 0 |
|---|---|---|
| Playback | The nib advances and the ribbon thickens behind it, driven by the audio clock | Same (content) |
| Word highlight | Ink fill appears in the frame the word starts (≤ 60 ms, SC-09); no tween | Same |
| Spotlight reached | The pin fills and the "Now" pill stamps in (scale 1.15 → 1, 160 ms) once; the tether appears with it | They appear, no scale |
| Swipe between shorts | Vertical pager slide 280 ms (standard); the next slip "prints" (2 dp rise + fade, 160 ms) | Instant (F-06-AC9) |
| Hold to slow down | The trace ahead stretches horizontally 200 ms; a haptic tick (follows system haptics); Speed reads 0.5× | Label change only |
| Lesson opens | Sheet rises 300 ms (enter easing); Compare's sorts are set left to right, 30 ms apart | Appear at once |
| Step opens or closes | Height 220 ms, chevron rotates 90° | Instant |
| Request advances | The route nib slides to the next node 360 ms; stage change announced politely | Jump |
| Share bridge, connected | The nib travels from the phone to the computer once (700 ms) when the request is stored | Static solid line |
| Deletion sent (v1-complete, 9.26) | The mini route turns solid and its nib travels from the phone to the computer once (700 ms) | Static solid line, nib at the middle |
| Sync, pull to refresh | The status card's trace wiggles while working (finite: stops when the work stops) | Static "Syncing…" |
| Analyzing icon | `loader-circle` rotates (1 turn / 1 s) while the state lasts | Static icon |
| Splash | The P is drawn once (700 ms) | Static mark |
| Coach mark, snackbar | Fade + 8 dp rise, 220 ms | Appear at once |
| Selection (toggle, chip, segmented, Speed on the rail) | Ink fill grows from the touch point 120 ms | Instant |

Haptics: a short tick on "your turn" in shadowing (optional, never the only cue), on hold-to-slow start, and on a
quiz answer; all follow system haptic settings.

---------------------------------------------------------------------------------------------------------------

## 9. Components (anatomy and states)

Every component has a light and a dark rendering, a TalkBack description, ≥ 48 dp targets and no state carried by
colour alone. "Selected" is always ink fill, an ink ring or a check; "disabled" is 45 % opacity plus, when it
matters, a reason in words.

### 9.1 Foundation controls (`:core:designsystem`)

| Component | Anatomy | States |
|---|---|---|
| **Button, primary** | Ink fill, onInk label (labelLarge) + optional 18-22 dp icon, 12 dp radius, 3 dp bottom bite; height 48 (sm/md) or 56 (lg) | Pressed: ink2 fill, bite removed, 1 dp down. Focused: 3 dp focus ring, 2 dp offset. Disabled: 45 %. Busy: label kept, a static progress hairline under the label (no spinner without words) |
| **Button, secondary** (default) | Sheet fill, 1.5 dp `lineStrong` ring, ink label | Pressed: deepSheet fill |
| **Button, tonal** | Galley fill, ink label ("Next change") | Pressed: deepSheet |
| **Button, quiet** | Text only, ink, 48 dp target | Pressed: galley wash |
| **Button, danger** | Sheet fill, 1.5 dp `critical` ring, **ink** label with a trash icon ("Remove") | Pressed: galley; never red text, never red fill |
| **Icon button** | 48 dp circle target, 20-24 dp icon, label required (content description) | Pressed: galley circle; toggled: ink fill circle |
| **Segmented control** | 1.5 dp `outline` ring, 3 dp inset; options 42 dp tall (48 dp target), optional 18 dp icon; real radio group | Selected: ink fill + onInk label; focus ring per option |
| **Switch** | 52 × 32 dp track. Off: galley + 2 dp `outline` ring, 16 dp outline thumb. On: ink track, 24 dp onInk thumb **with a check mark** | Disabled 45 %; label + optional description to its left |
| **Radio card** | Sheet card, 22 dp radio dot, title + body, optional preview | Selected: 2.5 dp ink ring + filled dot |
| **Text field** | 56 dp, sheet fill, 1.5 dp `outline` ring, leading icon, placeholder in inkMuted | Focused: 2.5 dp ink ring. Invalid: 2 dp `critical` ring + a critical icon and an ink message below ("That doesn't look like a link.") |
| **Chip** | Pill ≥ 32 dp visual (48 dp target in lists), labelMedium, optional icon | Outline (sheet + `lineStrong`), muted (galley), solid/selected (ink fill + check), zero ("Not in your clips yet": inkMuted text, hairline ring) |
| **Change chip** (`:core:ui`) | Tint fill + 1.5 dp family ring + **ink** family icon + ink name (+ optional practice badge) | Pressed: deepSheet ring wash; on sheet/page only |
| **Practice badge** (`:core:ui`) | Galley pill: mic + "Safe to produce" / ear + "Recognize only" (+ " · universal / casual / marked") | Static; never coloured |
| **Notice** | Galley well, 18 dp icon (info, caution, tip), optional bold lead, ink2 body | Caution adds a 3 dp ink bar at the start; `live` = polite announcement |
| **Progress bar** | 6 dp track (galley + hairline ring), ink fill, label left, value right (tabular) | Indeterminate: a third-width ink segment sliding (motion allowed) or static with "…" text |
| **Kicker / group head** | labelSmall caps + italic Newsreader count + a 1 dp `lineStrong` rule to the end | — |
| **Card** | Sheet, 14 dp radius, ring + soft drop (light) / ring (dark); padding 14-16 dp | Variants: stack (two sheets behind), crop marks, alert (4 dp ink bar at the start) |

### 9.2 Feed header (F-07 item 1)

Anatomy, left to right (52 dp tall, on page): **mode picker** button (mode icon + "Mix" + " · Today's set" in ink2 +
chevron; opens 9.12); **position** "3 of 8" (titleSmall, tabular) with a **bead row** under it in sets (done =
filled dot, current = 16 dp ink capsule, to come = rings; hidden in "In order" where it reads "4 / 15"); **Previous
short** / **Next short** icon buttons (chevron up / down, 48 dp; the visible equivalents of the swipe, F-06 item 4).

States: online (as above); **not reachable / offline**: an "On this phone" chip (smartphone icon + words, galley)
appears after the picker and the picker shortens to the mode name; counts in the picker are of playable shorts;
questions part of a set: "Question 2 of 5"; set done: "Done" with all beads filled; large text (9.6): beads hidden.

### 9.3 Spotlight slip and tether (F-07 item 3)

Anatomy (sheet, perforated top edge, 14 dp bottom corners, padding 12-14 dp):
1. Tags: the change chip (9.1) and the practice badge.
2. Kicker "LISTEN FOR" + the studied word in quotes (spotlightWord, Newsreader 34 sp) underlined in its family
   style at 3 dp + the **Now pill** (ink fill, speaker icon + "Now") while the word plays.
3. IPA line `/dictionary/ → [said]` (ipaSlip, Charis 21 sp).
4. One-line cue from Learn's "How to hear it", shortened: an English sound-alike ("One quick tap: it sounds like
   “wadder”."); for "Recognize only" the cue adds "Just learn to hear it: no need to copy it."
5. "Play the word" (play icon) and "Slowly" (snail, 0.5×) secondary buttons. When the change crosses into the next
   word, "Play the word" plays both (F-07-AC2).

Tap anywhere else on the slip → pause and open the lesson on the word.

**Tether**: a 1.5 dp ink hairline from a hollow diamond on the slip's lower edge, straight down to a filled diamond
caret just above the studied word in the captions. Drawn only when (a) the word is on the first visible caption line
(nothing lies between them), (b) both are on screen, (c) font scale ≤ 1.3. Otherwise only the caret above the word
shows. The progress pin (9.7) uses the same diamond, so slip, word and timeline share one glyph.

States: default; **now** (Now pill + filled pin); **recognize only** (ear badge, cue suffix); **contraction**
spotlight (the chip is "Lexical contraction" with an ink ring, the word "want to" dotted, the IPA shows the reduced
form); **crossing boundary** ("“did” + “you”"); **no spotlight** ("In order" only: the slip is replaced by a single
quiet line "No labelled change here: listen to the rhythm."); **listen first** (the slip shows; captions hidden, 9.4);
**large text** (9.6: compact slip: chip line, badge line, word, IPA; cue and buttons move to the lesson, the slip
itself opens it); **portrait video** (the slip merges into the caption slip, 9.5).

TalkBack: the slip is one node "Listen for flapping in “water”. Safe to produce. /ˈwɔtɚ/ to [ˈwɔɾɚ]: t becomes
voiced alveolar tap. Double-tap to open the lesson." with child buttons.

### 9.4 Captions and marks (F-07 items 4-5)

Layout: on a clean `page` area (grain masked). Previous phrase dimmed (captionDim, inkMuted), current phrase
(captionCurrent 20 sp in 46 dp line boxes, every word a ≥ 48 dp button whose hit area extends into the line gap
without overlapping its neighbours), next phrase dimmed. The intonation badge closes the current phrase.

Marks per family (the web's vocabulary exactly; underline 2 dp, offset 0.26 em, skip-ink off, drawn with
`drawBehind` from each word's box because `TextDecoration` cannot draw these styles):

| Kind | Underline | Colour | With "Show patterns" on |
|---|---|---|---|
| Reduction | solid 2 dp | family | 3 dp + the chevrons-down icon (14 dp) after the word |
| t/d processes | dashed 2 dp (dash 6, gap 4) | family | 3 dp + scissors |
| Assimilation | double (2 × 1.25 dp, 1 dp gap) | family | 4 dp total + merge |
| Word boundary | wavy 1.5 dp (wavelength 8, amplitude 1.5) + the **‿ arc** under the gap to the next word (13 × 7 dp, 2 dp, family colour) | family | 2 dp + link |
| Lexical contraction | dotted 2.5 dp | ink | + shrink; the chip "wanna ← want to" (galley pill, shrink icon) follows the dotted words |
| Other change | dash-dot 2 dp | inkMuted | 3 dp + dashed circle |

Word states (shape, not hue): **playing** = ink fill (6 dp radius) with onInk text, underline kept below the fill;
**selected** (lesson open on it) = 2 dp ink ring; **low confidence / nothing heard** = 1.5 dp dashed inkMuted outline
around the word (never spotlighted, never asked about); **stressed** (most prominent) = bold; **outside an active
filter** (transcript) = 45 % and underline removed, text kept; **spotlight** = the tether caret above it.

Intonation badge: galley pill, arrow icon + "Question · rises" / "Statement · falls" / "· stays flat"; "usually
falls" variant (expected contour differs) gets a 1.5 dp ink2 ring; "uptalk" chip with "What's this?".

Caption modes ("More" sheet): Marks (default), Plain (text only), IPA (Charis under each word; marked words also the
dictionary form), Off (the caption area collapses to a single "Captions are off" line with "Show"). **Listen first**
(opt-in): the current phrase is one line "Listen first: the words appear after it plays" + "Show the words" (never one
placeholder per word).

**First-use hint line** (the Watch coach marks that point at the captions): hand icon + "Tap any word for its
lesson." + "Got it", inline under the captions, never pausing playback; the next tips ("Press and hold the picture
to slow down.", "The line style and the small icon tell you the kind of change…") follow one at a time.

Overflow (A-06, F-07-AC9): when the current phrase does not fit, drop the dimmed phrases first; then the area shows
the lines around the word being said and follows the playhead line by line (jumps with Reduce motion); paused, it
scrolls. Nothing is cut mid-word.

TalkBack per word: "water, flapping, safe to produce, listen for".

### 9.5 Media area (F-06 item 7)

| Case | Rendering |
|---|---|
| Landscape video | Full width, 16:9, letterbox `#0E0D0C` when needed, never cropped; nothing over the picture except the one-time coach mark |
| Portrait video | Fills the card from the header to the progress band; the **caption slip** (opaque sheet, perforated top edge, top-right 14 dp corner) holds tags, spotlight word, IPA and the current phrase at the bottom left; the **rail capsule** (opaque sheet pill, 72 dp wide) floats on the right |
| Audio only | The drum (7.3) in the same footprint as a landscape video |
| Video not on this phone | Poster (or poster fallback) + overlay chip "Video not on this phone · playing the audio" (film icon) |
| Video not available from the computer | Poster fallback + chip "Video not available from your computer" |
| Loading a short | Poster or drum with a small static "Getting the sound ready…" chip after 300 ms; captions and slip show at once |
| Decode failure | Plays as audio with the poster and the chip "This video can't play on this phone. Listening still works." |
| Hold to slow down | While held: a 0.5× overlay chip (snail + "0.5×") top centre; released: gone |

Overlay chips use `sheet` at 94 % (gate G9), 32 dp, labelMedium, icon + words.

### 9.6 Action rail and large-text action bar (F-07 item 6)

Rail (thumb side, right, 64 dp wide): six items, each a 44 dp circle (sheet + `lineStrong` ring, 22 dp icon) in a
64 × 60 dp target with a word under it (labelMedium, two lines allowed): "Replay"; "Speed" showing its value ("1×",
"0.75×", "0.5×"; ink-filled when not 1×); "Loop off" / "Loop: Phrase" (repeat-1, ink-filled) / "Loop: Short"
(repeat, ink-filled); "Shadow"; "Save" / "Saved" (bookmark-check, ink-filled); "More".

**Action bar** (field-notebook graft): when font scale ≥ 1.3 **or** the current phrase needs more than four caption
lines at the available width, the rail becomes a two-row labelled bar under the progress (three cells per row, ≥
64 dp tall, icon + word side by side, the same states), the captions get the full width, and the slip compacts
(9.3). Render: `docs/design/27-watch-large-text-*.png`.

### 9.7 Progress: the stylus trace, play disc and clip line (F-07 items 7-8)

Row: 48 dp ink **play/pause disc** (onInk glyph) · the **trace** (36 dp tall, fills the width) · elapsed time
(bodySmall tabular). Trace grammar: played ribbon 1.6-5.4 dp following loudness; dotted rest (1 dp dots every 5 dp,
inkMuted); phrase boundaries as 1.5 dp inkMuted ticks; the spotlight **pin** (14 dp diamond on a 1.2 dp stem, hollow
→ filled when now); nib = playhead. The slider semantics: "Position 0:45 of 0:58", actions "Back 2 s" / "Forward
2 s"; releasing near a phrase start snaps to it; dragging never marks the short as seen.

Clip line under it: clip title (bold, chevron; opens the Clip page) and "Short 4 of 15 · 0:42–0:58" (ink2). At large
text it wraps to two lines, never truncated.

### 9.8 The short card: composition and states (F-06, F-07)

Reading order: header → media → slip → captions (+ hint line) → rail → progress → clip line → bottom bar. Render
`04` (landscape), `05` (audio, offline, colour-vision palette with patterns), `06` (portrait + coach mark), `27`
(large text).

| State | Rendering |
|---|---|
| Playing | Word fill; disc shows Pause; Now pill when the spotlight plays |
| Paused | Disc shows Play; the trace keeps the nib |
| Listen first, before the first play | Slip + the single "Listen first" line + "Show the words" |
| First run / empty / empty mode | Empty state (9.21) inside the Watch tab: `ill_feed_empty` "Your feed fills up with your clips" / "Your clips are still coming to this phone." + progress / "No shorts with {change} on this phone yet." + "Watch the Mix" |
| Offline / unreachable | "On this phone" chip in the header; everything else unchanged |
| Empty, clips on the way | Empty state "Your clips are still coming to this phone." + progress; once shorts exist, a last card "3 more clips on the way" in the end-card frame of 9.15 |
| Decode failure | Media area 9.5 ("This video can't play on this phone. Listening still works."); `VideoAvailability.CannotPlayOnThisPhone` |
| Corrupt or missing local file | Caution notice in the short's media footprint: "This short couldn't be played." (one time); the clip reads "Needs downloading again" (9.18) and swiping moves on |
| Track missing | Plays the other track; a quiet line under the clip line (bodySmall, ink2, `ic_track` 16 dp) says which, from `FeedPlaybackState.trackFallback` (spec F-06 Partial, D-47): `DIALOGUE_NOT_ON_PHONE` "Playing the original mix: the dialogue track isn't on this phone yet." · `MIX_NOT_ON_PHONE` "Playing the dialogue only: the original mix isn't on this phone yet." · `NO_DIALOGUE_TRACK` "Playing the original mix: this clip has no dialogue-only track."; no line when `null` |
| Volume at 0 | Notice under the captions: speaker icon + "Turn the volume up: this is all about sound." |
| Clip deleted while watching | Snackbar at the next swipe: "A clip was deleted on your computer and removed from this phone." |
| TalkBack | Nothing autoplays; the card is one node with the summary "Short 4 of 15, {clip}. Listen for flapping in “water”. 14 seconds." and the actions Play/Pause, Next short, Previous short, Replay phrase, Slower, Open lesson, Save |

### 9.9 Lesson sheet frame and header (F-10)

A modal bottom sheet over the paused short (scrim 46/62 %), 28 dp top corners, handle; half height (top at ~120 dp)
expandable to full height (`docs/design/11-*.png`). Above it, on the scrim, the **paused-context pill** (sheet, float
shadow): pause icon + "Paused at 0:45 · Two friends at the lake". A halftone corner sits at the top right, right of
the word, never under text.

Header: kicker "WORD LESSON", Save this word (bookmark / bookmark-check), Close; the word (lessonWord, Newsreader 40;
"“did” + “you”" when the change crosses the boundary, the second word in italic ink2); meta "Word 12 of 431 ·
phrase 4 · 0:44.8–0:45.2"; change chips + practice badge; reliability badge "Low confidence" / "Nothing heard here"
(dashed outline badge) when it applies; a row "‹ Word", "Word ›" (secondary, full accessible names "Previous word",
"Next word") and "Next change →" (tonal).

Steps: numbered disclosures, 56 dp rows with a hairline between them; the numeral is italic Newsreader in a 30 dp
ring (open = ink disc); title titleLarge; a status at the end ("✓ played 2×"); chevron. Guidance "Full" opens 1-4,
"Compact" shows titles only. State resets per word.

### 9.10 Lesson step 2: Compare (F-10, render `10`, `11`)

1. **Forms**: two cards side by side: "Dictionary /ˈwɔtɚ/" (sheet, `lineStrong` ring) and "What was said [ˈwɔɾɚ]"
   (ink fill, onInk), each with a "What's this?" button.
2. **Legend** directly above the sorts: "= same · ≠ changed · – dropped · + added".
3. **Type sorts**, one per sound, wrapping onto new rows (never truncated): 80 dp wide (64 when the word has many
   sounds), dictionary sound on top (Charis 27, ink2), the mark in a 30 dp circle, what was said below, and the word
   under the sort. States: **same** (quiet sort), **changed** (2 dp ink border, ink-filled mark), **dropped** (galley
   sort, dictionary sound struck through, a dashed empty slot below), **added** (dashed ink outline, empty dictionary
   slot), **selected** (ink fill + double ring). TalkBack: "t changed to ɾ", "j, not heard".
4. **Across a word boundary**: an italic header per word ("“did”", "“you”") with a dashed divider between the groups,
   and the **gap box** under them (family tint + 1.5 dp family ring, family icon, ink text): "Gap between “did” and
   “you”: 12 ms — they run together: that is the linking (an unlinked boundary sits nearer 60 ms)."
5. **Sound card** (galley well) for the selected sort: the **"≠ changed · /t/ → [ɾ]" pill** (ink), the symbol in
   brackets (Charis 40), its name ("voiced alveolar tap"), "As in “water”: it sounds like “wadder”." and what the
   mouth does, the **mouth still** (112 × 104 dp, tract palette) at the right, and "Play it" (primary), "See the
   mouth", "IPA chart". No cross-language anchor in this card (the Spanish-speaker tips live on the phenomenon page,
   where the spec puts them).
6. Notices: no dictionary form (web copy), espeak data ("Expected by the aligner" row).

### 9.11 Other lesson steps

- **1 Listen**: "Play the word", "Play with “you”" (crossing changes, with the web's sentence), "Play the phrase";
  segmented speed 0.5× / 0.75× / 1×; "Loop: repeat what you play" switch; track segmented "Original mix | Dialogue
  only" when available.
- **3 Why it changes**: one sheet card per change: family icon + name + "What's this?", the description with an IPA
  example, the practice badge + register + the report's why, "Watch more like this" (secondary) and "Learn about
  {change} →" (quiet); low-confidence notices first (caution notice); "Nothing changed: this word was said the way the
  dictionary says it." when empty; footer facts (prominence, divergence…) as bodySmall rows.
- **4 See the mouth** (v1-complete): the articulator panel (tract air ground, 10 dp radius, 1 dp hairline) following
  the playhead; segmented "What was said | Dictionary form"; one 48 dp button per sound; live caption "[ɾ] voiced
  alveolar tap · as in “water”"; footer line (web). Fallback when cut: each sound button shows its name and the
  mouth sentence.
- **5 Practice**: the shadowing intro (web), segmented "The word and the next | The whole phrase", segmented "0.5× |
  0.75×", "Start shadowing" (primary). Running: a card with the mic-vocal icon and the live line ("Round 2 of 4:
  your turn — say it now", announced politely), an **8-phase bar** (listen = square cell, your turn = rounded cell;
  done = ink, current = ink hatch at −35°, to come = outline), "Stop". "Record yourself" opens the record section
  (F-13): privacy line, "Record" (circle-dot) / "Stop" / "Record again", "Play the original first" switch, the
  Original / Yours / Alternate buttons, then the pitch overlay (original solid 2.5 dp ink2 with a circle end, yours
  dashed 6/4 2 dp ink with a square end, direct labels) with "Show as a table", and the observations grouped "How
  they differ / How they're alike / Couldn't measure" with their topic icons; footer "This is a description, not a
  grade." Offline: "Pitch comparison needs your computer. You can still compare by ear."

### 9.12 Feed sheets

- **Mode picker** (render `09`): sheet with title "What the feed shows" + lede; options as 56 dp rows (selected =
  ink fill + check): Mix · Today's set (with progress), Saved (count); "ONE CHANGE" group: per family a header (icon
  + name in its line style + the line-style name) and change chips "Flapping · 34 shorts" (the change's **short
  count**, spec D-48, `ChangeCount.shorts`: the same number as the Home chip and the Learn card; zero = muted "nt → n · 0 · Learn", routes
  to Learn); "ONE CLIP, IN ORDER" group: clip rows with "left at short 4".
- **More sheet**: list rows with icons: "Listen to: Original mix | Dialogue only" (segmented), captions mode
  (segmented Marks / Plain / IPA / Off), "Listen first" (switch), "Pitch, stress and IPA of this phrase", "Record
  yourself", "Open the clip page", "Download the video" (when absent), "Leave this clip out of the Mix", "What do the
  marks mean?" (opens the legend: the four families + whole-word changes with the web's sample words “does”,
  “water”, “did you”, “pick it up”, each drawn with its mark, icon and name, plus low confidence and stressed).
- **Phrase details** (v1-complete): both IPA lines with ‿, the voice line, intonation units, the prominence strip
  (content words solid ink2 bars, function words outlined; legend), the pitch chart (one ink2 line 2 dp, gridlines
  hairline, playhead ink, axis labels inkMuted 14 sp) with its text summary and "Show as a table" (every chart has a
  table twin, A-14).

### 9.13 "What's this?" (F-14)

Control: a 20 dp circle-help icon in a 48 dp target after the term; TalkBack "What's this: {term}". Sheet: title
(headlineLarge), 1-3 sentences, example in Charis, "Learn more →"; for a change, the practice badge + register + why
+ "Watch more like this". Closing returns focus to the control.

### 9.14 Quiz card (F-08 questions in the feed, F-17 sessions; render `07`)

A stacked sheet card: kicker with the kind icon ("Which pronunciation?" audio-lines, "Which change?" shapes, "How
many words?" list-ordered), the question as a headlineLarge sentence ("How was “water” really said?"), a one-line
context lede, "Play" (primary), "Slowly", "With context". Answers in a 2-column grid, each ≥ 56 dp (72 dp in the
mockup), IPA in Charis 26 or a change chip or a number; a real radio group.

After answering: the right answer gets a **3 dp ink ring + bold + check icon (`good`) + "Correct answer"**; a wrong
pick gets a **2 dp `critical` ring + X icon + "Your answer"**; others dim to 70 %. A live line: circle-check +
"Correct!" or circle-alert + "Not quite — the answer was [ɾ]." The explanation well (galley): change chip + badge,
“water” /ˈwɔtɚ/ → [ˈwɔɾɚ], the description; then "Again at 0.5×", "Open the lesson", "Save" (nothing saved unless
tapped), "Learn about {change}", "Watch more like this" (Practice), and "Next question →" (primary). Untimed.

### 9.15 End cards (F-06 item 9, F-08; render `08`)

Stacked sheet card with crop marks. **Set summary**: kicker "TODAY'S SET", "Done for today" (displayLarge) or "Set
done", the `ill_set_done` spot, a tally row between double rules (8 shorts · 5 questions · 4 of 5 right, numerals in
Newsreader), "What you met" change chips with counts ("Flapping ×3"), the line "Nothing plays after this card. A new
set is ready after midnight.", "Back to Home" (primary), "Another set", "Practice". No swipe past it.
Other end cards use the same frame: "End of {clip}" + "You saw 15 shorts with 14 kinds of change." + "Watch it again",
"Next clip: {title}" (a button), "Practice", "Back to Home"; change mode "That's every “flapping” in your clips." +
"Learn about flapping", "Practice flapping"; Saved "That's all your saved shorts." + "Practice"; Mix with nothing
left "You've seen every short. New ones appear when your computer finishes a clip." + "Replay the mix", "Practice",
"Add a clip". No streak, no time-spent badge, ever.

### 9.16 Home blocks (F-03; renders `01`, `02`, `03`)

| # | Block | Anatomy and states |
|---|---|---|
| 1 | **Masthead** | Stylus P (36 dp) + wordmark "Phono*Trainer*" (Newsreader 30, "Trainer" italic) + Settings; double rule; italic dateline ("Sunday, 4 October") + "Home"; lede "What to study today, and how your clips are doing." |
| 2 | **Computer status card** | 9.17 |
| 3 | **Today's set** | Stacked hero card with crop marks; halftone in the top-right corner (never under text); kicker "TODAY'S SET"; "Today · about 5 minutes" (displayMedium); "8 shorts, 5 questions · 2 saved shorts back"; the **stations thread**: a dotted trace with 8 station circles (each the spotlight's family icon in its colour on sheet; saved ones with a 2 dp ink ring and a bookmark) and 5 question slugs; legend "8 shorts · one change each" / "5 questions"; "Start" (primary lg, full width). There is no control that lists the set (spec D-45): the thread is the content. TalkBack reads the card as one sentence (F-03-AC10): "Today's set: 8 shorts with flapping, linking, h-dropping and 2 more changes; 2 saved shorts back; then 5 questions." (each distinct spotlight change named once, in set order, at most three, then "and {N} more changes"; from `SetRepository.today()`'s items in set order: `StudySetItem.Short.spotlight.change`, `savedTag` and `done`, and `StudySetItem.QuestionItem` for the slugs, work-plan A.5). States: mid-set "Today · 3 of 8 done" + "Continue" (done stations filled ink); finished "Done for today" + "What you met: flapping ×3, linking ×2…" + quiet "Another set"; first run "Today's set starts with your first clip." with dashed empty stations; short set "Only 4 shorts today: add a clip for more."; "Preparing today's set…" after 300 ms |
| 4 | **Keep watching** | Poster (16:9, duration chip) + title + "Short 4 of 15" + "Listen for: [change chip] in “pick it up”" (word in its mark) + "Keep watching" (primary, full width) |
| 5 | **Add a clip** | Two cards: dashed-outline icon well + "Paste a link" / "From YouTube, X or another site" and "Choose a file" / "Video or audio on this phone"; section note "Short dialogue teaches the most" |
| 6 | **New on this phone** | Card with the faint "NEW" stamp, circle-check, "Ready to watch: {title}", "New on this phone · 12 shorts", "Watch" |
| 7 | **Your clips** shelf | Horizontal row of 176 dp clip cards (9.18) + "All clips →" |
| 8 | **Watch by change** | One card: per family a header (icon + name in its line style + "solid line · the sound weakens" etc.) and 48 dp change chips "Flapping · 34" (the short count, D-48: the shorts the change mode plays, `ChangeCount.shorts` of `OccurrenceRepository.changeCounts()`); zero = "Not in your clips yet" (muted, opens Learn) |
| 9 | **Practice** | Card: "10 questions · about 3 minutes", "Worth reviewing: t/d deletion · 4 of 9", a 9-cell accuracy meter (filled = right), "12 saved shorts", the `ill_spot_practice` spot, "Start" |
| 10 | **Insights teaser** (v1-complete) | Card: "7 in 10" (displayLarge, italic "in"), "words you hear don't sound the way the dictionary writes them.", "See Insights →", the `ill_spot_insights` spot. Absent if Insights is cut |

Home states: first run (status "Not connected to a computer" + "Connect"; the teaching card with `ill_welcome_clips`,
the numbered steps, "Connect to your computer" (primary) and "Explore Learn"); paired, no clips (empty state with
`ill_home_no_clips`, "Your computer has no clips yet." + "Start with a short clip (30–60 seconds) of natural
dialogue." + "Add a clip"); loading (shelf cards with "Bringing to this phone 40 %"); catalogue error ("Your clips
could not be loaded." + "Try again" in a caution notice; the status card still works); storage nearly full and
background-limited lines (9.17). Pull to refresh draws the trace wiggle under the masthead.

### 9.17 Computer status card and every connectivity state (spec 7.5)

Anatomy: a 44 dp icon well (ink fill when connected, 1.5 dp ink ring otherwise) + title (titleLarge) + subtitle
(time, ink2) + one action at the end; then rows (52 dp, hairline above) for request counts and transfers, each icon +
words (+ bar) (+ inline primary action). The whole card reads as one TalkBack sentence; only transitions are
announced.

| State | Domain state (architecture 3.4) | Icon | Title · subtitle | Action |
|---|---|---|---|---|
| Not connected | `NotConnected` | unplug | "Not connected to a computer" · "Clips you add wait on this phone." | Connect |
| Offline | `Offline` | wifi-off | "Offline · requests wait on this phone" | — |
| Not on Wi-Fi | `NotOnWifi` | signal | "Not on Wi-Fi · your computer is reachable only at home" | — |
| Looking | `Looking` | radar | "Looking for your computer…" | — |
| Checking | `Checking` | radar | "Checking your computer…" (claims nothing) | — |
| Connected | `Connected` + `SyncState.Idle` | monitor-check | "Connected to {name}" · "Checked 10 s ago · analyzing 1 · 2 in line" | Sync now |
| Syncing | `Connected` + `SyncState.Active` | monitor-check + the trace wiggle | "Syncing · bringing 3 clips · 42 %" + bar | — |
| Not reachable | `NotReachable` (`addressMayHaveChanged` adds the second action; `lastProbe` feeds "Why?") | monitor-off | "Your computer isn't reachable" · "last seen 3 h ago · your clips work offline" | Why? (+ "Enter the new address" after a network change) |
| Different computer | `DifferentComputer` | monitor-x | "A different computer answered" | Details |
| Connect again | `ConnectAgain` | key-round | "Your computer no longer recognizes this phone" | Connect again |
| Computer too old / App too old | `ComputerTooOld` / `AppTooOld` | circle-arrow-up | "Update PhonoTrainer on your computer" / "Update this app" | Why? |
| Computer low on space | `Connected` with `lowOnSpace = true` (a row under the Connected title) | hard-drive | "Your computer is low on space" | — |

Rows: "2 waiting on this phone" (clock), "1 analyzing · 40 % · Hear the phones" (loader + bar), "1 in line" (clock),
"1 needs your attention" (circle-alert in `critical` + its primary action, e.g. "Try again"), "Bringing 2 clips to
this phone · 34 MB of 61 MB" (download + bar), "Storage almost full: videos of older clips will be removed first."
(hard-drive → Storage), "Android is limiting PhonoTrainer in the background: clips arrive when you open the app." +
"Battery settings" (battery-warning). Browser-started jobs are never counted. In v1-complete, deletions from your
computer count in "waiting on this phone" while they wait and in "needs your attention" when refused (spec F-03 item
2, F-20-AC13, AC14, D-59); the rows look and read exactly as above, and the attention row's inline action is the
deletion's "Try again" (D-59) unless a failed request also needs attention, whose own action then stays (architecture
8.8). Shared progress never appears on this card (spec 7.13: it is not a request).

Sources of the rows (one owner: `ConnectionStatusCard`, work-plan 4.5): the waiting, analyzing, in-line and attention
counts and the inline action come from this phone's own requests (`RequestRepository.counts()`: `waiting`,
`analyzing`, `inLine`, `needsAttention`; the progress and the action from the request in question; in v1-complete
`counts()` also counts waiting deletions in `waiting` and refused ones in `needsAttention`, and a refused deletion's
inline "Try again" is `DeletionAction.TRY_AGAIN` through `RequestRepository.performDeletion`, read from `deletions()`,
architecture 8.8); "Bringing … · 34 MB of 61 MB" from `SyncState.Active(bringing, percent, sending, doneBytes, totalBytes)`;
the storage line from `StorageRepository.usage` (`budgetReached`, `belowFloor`); the background line from
`ComputerStatusRepository.backgroundRestricted`. The status icons are the `ic_conn_*` set of section 6.

Global banner (credential revoked): a full-width caution card under the top bar: key-round + "Your computer no longer
recognizes this phone. Connect again to keep syncing." + "Connect again".

### 9.18 Clip rows, shelf cards and availability (F-04, F-05; render `15`, `16`)

- **Shelf card** (Home): 176 dp, poster 16:10 (or the audio art: a finished trace with a blot on drum rules; or the
  poster fallback), title (titleSmall, 2 lines), "4 min 21 s · 12 shorts", family icons with counts, availability
  icon + words.
- **Library row**: poster 104 × 66 dp, title, meta "4 min 21 s · 691 words · 14 changes", tags line (source icon +
  word, availability icon + word, "Kept" pin), "You've seen 12 of 15 shorts", "Older rules: re-analyze on your
  computer to update" muted chip when relevant, "From a computer you forgot" / "From another library"; "More actions"
  (ellipsis-vertical) opens a menu with "Watch", "Open clip page", "Keep on this phone" / "Stop keeping", "Download to
  this phone" / "Remove from this phone…", "Remove the video only". The menu never holds "Delete from your computer…"
  (v1-complete; spec F-04, D-54): that action lives only on the Clip page (9.26), so the two removals are never next
  to each other in a short menu.
- **Deletion line** (v1-complete, spec F-04, 7.11): a clip with a deletion from your computer keeps its row and its
  availability, and gains one more line under the tags, in the same style as an availability line (14 sp, weight 700,
  16 dp icon, wraps, never cut): trash + "Waiting to be deleted from your computer", then trash + "Deleting from your
  computer…" once sent. An imported clip (`ClipSummary.source.kind = SourceKind.IMPORT`) reads "Waiting to be removed
  from your computer's list" and "Removing from your computer's list…" instead (spec D-58), with the same icon and
  rings: only the words follow the import mark. The Home shelf card shows the same line under its availability line
  (it wraps to two or three lines in 176 dp). The Clip page header shows it as a pill next to the availability pills, the only header pill with
  a ring: a 1.5 dp **dashed** `outline` ring while it waits on this phone (the trace grammar's "not yet"), a solid one
  once sent (renders `29`, `34`). The feed shows nothing (spec F-06 gives the short card no marker; the clip line opens
  the Clip page, where the state is). The line and the header pill read `ClipSummary.deletion` (`DeletionState.WAITING`
  or `SENDING`; null shows nothing; the Clip page reads it through `ClipDetail.summary`; architecture 3.4 and 8.8), so
  `ClipRow` and `ClipCard` need no other input (the imported words come from `ClipSummary.source`). D-65's line ("It
  may have reached your computer already, so it can't be undone.") is not on rows, shelf cards or the header pill: it
  explains a missing "Undo", and only the Clip page block and Requests carry "Undo" (spec F-04, D-65). A refused
  deletion (`REFUSED`) is never in that field. Library rows, shelf cards and the Clip page header show nothing for it,
  and its paused downloads never show "Download paused: …" on the row; Home's status card counts it in "needs your
  attention", Requests lists it, and the Clip page block shows it with its two actions (spec D-59; architecture 25.5
  C-46).
- **Availability** (icon + words, never colour), one row per domain state (`AvailabilityChip`, architecture 3.4):

  | Words | Icon | Domain state |
  |---|---|---|
  | On this phone | smartphone (`ic_avail_on_phone`) | `ClipAvailability.OnThisPhone` |
  | Bringing to this phone 45 % | download + bar | `ClipAvailability.Bringing(percent)` |
  | Not on this phone | monitor (`ic_avail_on_computer`) | `ClipAvailability.NotOnThisPhone` |
  | Getting ready on your computer | monitor-cog | `ClipAvailability.GettingReadyOnComputer` |
  | Download paused: {reason} ("storage budget reached", "your phone is almost full", or "Downloads continue the next time this phone reaches your computer.") | clock | `ClipAvailability.DownloadPaused(BUDGET_REACHED / FREE_SPACE_FLOOR / NOT_CONNECTED)` |
  | Needs downloading again ("This clip needs downloading again." on the Clip page) | download | `ClipAvailability.NeedsDownloadingAgain` |
  | Video on this phone | film (`ic_avail_video`) | `VideoAvailability.OnThisPhone` |
  | Video downloading 40 % | film + bar | `VideoAvailability.Downloading(pct)` |
  | Video not on this phone | film, ink2 | `VideoAvailability.NotOnThisPhone` (also `PauseReason.NOT_WANTED`: automatic video download off) |
  | Video getting ready on your computer | monitor-cog | `VideoAvailability.GettingReadyOnComputer` |
  | Video not available from your computer | film, ink2 | `VideoAvailability.NotAvailableFromComputer` |
  | This video can't play on this phone | film, ink2 | `VideoAvailability.CannotPlayOnThisPhone` |
  | Audio only | headphones (`ic_audio_only`) | `VideoAvailability.AudioOnlyClip` |
  | Waiting to be deleted from your computer (v1-complete; an extra line, the availability above stays); imported: Waiting to be removed from your computer's list (D-58) | trash (`ic_remove`); header pill with a dashed ring | `ClipSummary.deletion = DeletionState.WAITING` |
  | Deleting from your computer… (v1-complete; an extra line); imported: Removing from your computer's list… (D-58) | trash; header pill with a solid ring | `ClipSummary.deletion = DeletionState.SENDING` |

### 9.19 Request card, stepper and every request state (F-20; renders `17`, `18`)

Card anatomy: a state line (icon + words, titleLarge 800), the source line (site icon + site word + shortened link,
or file icon + name + size + age), an optional sentence (the reason), the **route line** or the **stepper**, and the
actions grid (2 columns, 48 dp). Failures get the **alert card** variant: 4 dp ink bar at the start, the circle-alert
icon in `critical`, and "Remove" as the danger button (critical outline, ink label).

Route line (waiting states): three 44 dp nodes "Phone" (ink fill when the request is here), "Computer", "Ready",
joined by dotted traces; solid trace and the nib for the part done.

Stepper (on your computer): six nodes on a trace (done = ink disc + check; current = 36 dp ring + rotating loader;
next = outline node with its number; skipped = skip-forward; failed = `critical` ring + X; cancelled = circle-slash),
the **six stage names under the nodes** ("Download, Audio, Dialogue, Words, Phones, Save", the current one bold), and
the **teaching line in a notice well** ("Step 5 of 6 · phrase 3 of 10. The phone recognizer listens to each word and
lines it up with its dictionary form."). The request detail shows the full stage names and teaching lines as rows
(the current row on galley with a 3 dp ink bar), the estimate line, the first-run note, "Show the log", "Show details".

Stage names under the nodes map one to one to `Stage` (work-plan 4.2): `DOWNLOAD` "Download", `EXTRACT` "Audio",
`SEPARATE` "Dialogue", `TRANSCRIBE` "Words", `PHONES` "Phones", `SAVE` "Save" (full names in the detail: the spec's
"Download", "Extract the audio", "Separate the dialogue", "Transcribe the words", "Hear the phones", "Compare and
save"); "phrase 3 of 10" comes from `RequestProgress.segment`/`segments`.

Every row below is one `RequestState` (architecture 8.4; names exact), or the derived queue pause; the actions are
`RequestAction` values (architecture 3.4). "Watch" and "Open it" are navigation (`RequestRow`'s `onOpen`), not
request actions. A row shows only what `Request.actions` holds (architecture 8.4, the one authority); for a failure
the title, the body and the order of the actions are those of the failure code's row in spec 8.6 (or 7.7 for the
phone-side copy codes), intersected with `Request.actions`, as `RequestFailureCopy` gives them (architecture 19). The
failure rows below show the common cases; for example a `playlist` or `download_unavailable` link failure offers only
Open the link and Remove.

| State (icon + words) | Domain state · actions | Group | Actions |
|---|---|---|---|
| Preparing the file 40 % (hourglass + bar) | `PREPARING` · `CANCEL_PREPARING` | Preparing | Cancel |
| Couldn't keep the file (circle-alert) | `COPY_FAILED` with `lastError.code` `copy_no_space`, `copy_unreadable` or `copy_interrupted` (architecture 8.3) · `REMOVE`, `TRY_AUDIO_ONLY` (only for a video's `copy_no_space` while the source can still be read, D-49) | Needs your attention | Reason lines from spec 7.7: `copy_no_space` with Try audio only available (a file picked in the app, or a shared one while its sheet is still open) "Not enough space on this phone to keep this video until your computer is reachable. Free {N} MB or choose Audio only." + Try audio only, Remove; without it (a shared file whose sheet has closed) "Not enough space on this phone to keep this video. Free {N} MB, then share it again and choose Audio only." + Remove only ({N} from `RequestError.neededBytes`); `copy_unreadable` "PhonoTrainer couldn't read this file. Open it once in the app that has it, then share it again." + Remove; `copy_interrupted` "Couldn't keep the file: the copy didn't finish. Share it again." + Remove. Try audio only returns the same request to "Preparing the file" for the audio track (no second request) |
| The copy kept on this phone is missing (circle-alert) | `COPY_MISSING` · `REMOVE` | Needs your attention | Remove ("Share the file again to analyze it.") |
| Waiting on this phone (clock) · reason: the first that applies of "Which reason a waiting row shows" below (spec D-61) | `WAITING` (reason from `ComputerStatus` and `Request.waiting`: `nextAttemptAt`, `attempts` ≥ 3) · `SEND_NOW`, `REMOVE` | Waiting on this phone | Send now (only when connected), Remove |
| Waiting on this phone, a file too large for one background run (clock) | `WAITING` with `waiting.needsAppOpen` (spec D-41), whatever the connection state | Waiting on this phone | as above; a second reason line under the first (bodyMedium, ink2, `ic_info` 16 dp): "Open PhonoTrainer on your home Wi-Fi to send this file: large files are sent while the app is open." Links in the queue are not held by it |
| Waiting on this phone, stopped by the learner (clock) | `WAITING` with `waiting.held` · `SEND_NOW`, `REMOVE` | Waiting on this phone | Send now (only when connected; it clears the hold), Remove; reason line "Stopped by you: it waits here until you tap Send now." (D-46) |
| Sending 40 % · 125 of 312 MB (send + bar) | `SENDING` · `STOP_SENDING`, `REMOVE` | Sending | Stop sending, Remove |
| Sent to your computer (send) | `SENT` · `CANCEL` | On your computer | Cancel (confirm dialog) |
| Waiting in line · 2nd (clock) "Analyses run one at a time." | `IN_LINE` · `CANCEL` | On your computer | Cancel (confirm dialog) |
| Analyzing 61 % · Hear the phones · phrase 3 of 10 (loader + stepper) | `ANALYZING` · `CANCEL` | On your computer | Cancel (confirm dialog) |
| Cancel waiting for your computer (circle-slash) | `CANCEL_WAITING` · `UNDO_CANCEL` | On your computer | Undo |
| Starting again on your computer (rotate-ccw) | the row's state + `rerunPending` (the automatic re-run, architecture 8.4) | On your computer | — |
| Ready on your computer (circle-check) | `READY_ON_COMPUTER` | Getting it on this phone | — |
| Getting it on this phone 70 % (download + bar) | `GETTING_ON_PHONE` | Getting it on this phone | — |
| Done recently (circle-check) | `ON_PHONE` (last 7 days) | Done recently | Watch |
| Couldn't download (link) | `NEEDS_ATTENTION` (download error codes) · `TRY_AGAIN`, `TRY_AUDIO_ONLY`, `OPEN_LINK`, `REMOVE` | Needs your attention | Try again (primary), Try audio only, Open the link, Remove |
| Live streams can't be analyzed (link) | `NEEDS_ATTENTION` (`live_stream`, spec 8.6, D-40) · `TRY_AGAIN`, `OPEN_LINK`, `REMOVE` | Needs your attention | title "Live streams can't be analyzed", reason "This video is live or hasn't started yet. Once it has ended and is saved as a video, try again."; Try again (primary), Open the link, Remove |
| This video is too long for your computer (link) | `NEEDS_ATTENTION` (`too_long`, D-40) · `OPEN_LINK`, `SHOW_DETAILS`, `REMOVE` | Needs your attention | title "This video is too long for your computer", reason "Your computer analyzes link videos up to {limit} long. Share a shorter video; the limit can be changed on your computer (see its README)." ({limit} from `Capabilities.maxLinkDurationS`, "1 hour" / "45 minutes"; unknown: "up to the length set on your computer"); Open the link (primary), Show details, Remove; no Try again, no Try audio only |
| Your computer is low on space (job) | `NEEDS_ATTENTION` (`no_space`, D-49) · by kind: links `TRY_AUDIO_ONLY`, files `TRY_AGAIN`; `REMOVE` | Needs your attention | reason "{free space}"; Try audio only (links) or Try again (files, once space is freed), Remove |
| Analysis failed | `NEEDS_ATTENTION` (analysis error codes) · `TRY_AGAIN`, `SHOW_DETAILS`, `REMOVE` | Needs your attention | Try again, Show details, Remove |
| Refused by your computer at sending | `NEEDS_ATTENTION` (`lastError.atSending`) · `TRY_AUDIO_ONLY`, `REMOVE` | Needs your attention | Try audio only (videos too large: the same request returns to "Preparing the file" while the audio is taken from the kept copy; nothing is re-run on the computer, architecture 8.4), Remove |
| Interrupted ("Your computer stopped during the analysis.") | `NEEDS_ATTENTION` (`interrupted`, after the one automatic re-run) · `TRY_AGAIN`, `REMOVE` | Needs your attention | Try again, Remove |
| Your computer no longer has this file ("Share it again.") | `NEEDS_ATTENTION` (`source_gone`, architecture 8.6) · `REMOVE` | Needs your attention | Remove |
| Cancelled (circle-slash) | `CANCELLED` · `TRY_AGAIN`, `REMOVE` | Needs your attention | Try again, Remove |
| Queue paused: Connect again / Update needed / A different computer answered | derived from `ComputerStatus` `ConnectAgain` / `ComputerTooOld`, `AppTooOld` / `DifferentComputer` (never written into rows) · `CONNECT_AGAIN` / `WHY` | header notice | Connect again / Why? / Details |
| Started on your computer (monitor, collapsed group) | `ComputerJob` (`ClipRepository.browserJobs()`), not a request | read-only | Hide on this phone (failed ones) |
| Offline, computer-side state | any accepted state + `Request.updatedAt` | any | "Last known: Analyzing 40 % (10 min ago)"; actions read "…when connected" |

The request detail's milestone timeline reads `Request.createdAt` ("Added on this phone") and `Request.timeline`
(`sentAt`, `inLineAt`, `analyzingAt`, `readyAt`, `onPhoneAt`).

**Which reason a waiting row shows** (spec D-61; v1-core copy for requests, WP-22; the same list for deletions in
v1-complete). One reason line (clock, 16 dp, bodyMedium ink2): the first row below that applies, so a request and a
deletion waiting side by side never give two reasons for one state. No word is new; the words are spec 7.5's and
F-20's.

| Order | When | Domain state (architecture 3.4) | Reason line |
|---|---|---|---|
| 1 | the queue is paused | `ComputerStatus` `ConnectAgain` / `ComputerTooOld` / `AppTooOld` / `DifferentComputer` | "Your computer no longer recognizes this phone" / "Update PhonoTrainer on your computer" / "Update this app" / "A different computer answered" |
| 1 | a deletion whose last answer was "made for a different computer" (not a refusal, spec D-62) | the deletion's last error code `different_computer` (architecture 8.8) | "A different computer answered" |
| 2 | a deletion, while the computer uses another library | `DeletionWait.DIFFERENT_LIBRARY` | "Your computer is using a different library: this waits until it uses this clip's library again." |
| 3 | not connected, and the phone has no Wi-Fi | `Offline`, `NotOnWifi` | "Not on Wi-Fi" |
| 3 | not connected, otherwise | `Looking`, `Checking`, `NotReachable` | "Your computer isn't reachable" |
| 4 | connected, after a failed attempt | `Connected` + `waiting.attempts` 1 or 2 (`nextAttemptAt` gives {n}) / `attempts` ≥ 3 | "Will try again in {n} min" / "Couldn't send yet: will try again" |

A request held by "Stop sending" shows "Stopped by you: it waits here until you tap Send now." instead of this line,
and a file too large for one background run adds its own second line (rows above). A deletion that may have reached
the computer adds D-65's line under the reason (deletion rows below).

**Deletion rows** (v1-complete, spec F-20 item 1, render `32`). A deletion from your computer is not a `Request`. It
is a `ComputerDeletion` from `RequestRepository.deletions()`, and its row is `DeletionRow` in `:feature:requests` (WP-34,
architecture 19). The row sits in the same groups and is built from the same `:core:ui` primitives as `RequestRow`.
Its actions are `DeletionAction` values through `RequestRepository.performDeletion`, never `RequestAction`s
(architecture 3.4 and 8.8). The title line is trash + "Delete “{title}” from your computer" (titleLarge 800, with the
quoted title of 9.26); an imported clip's row (`ComputerDeletion.imported`) is titled "Remove “{title}” from your
computer's list" (spec D-58), with the same icon and layout. There is no source line, because the title names the clip.
Under it come a reason line (16 dp icon, bodyMedium ink2), the **mini route** of 9.26 (Phone → Computer, with no
"Ready" node because nothing comes back) and the actions. There is no stepper and no request detail: tapping the card
opens the clip's Clip page (`ClipKey`), and TalkBack's click label says so ("Open the clip page"). Every word of these
rows comes from `DeletionCopy` (`:core:ui` `component/`, WP-34, architecture 19), the one mapping the Clip page block
shares. A row shows only what `ComputerDeletion.actions` holds (architecture 8.8, the one authority), as a request row
shows only `Request.actions`.

| State (icon + words) | Domain state · actions | Group | Reason line | Route | Actions |
|---|---|---|---|---|---|
| Waiting to be deleted (trash title) | `DeletionState.WAITING` · `UNDO` | Waiting on this phone | clock + the first reason that applies of "Which reason a waiting row shows" above (spec D-61): a queue pause or "A different computer answered", "Your computer is using a different library: this waits until it uses this clip's library again." (`DeletionWait.DIFFERENT_LIBRARY`), "Not on Wi-Fi" / "Your computer isn't reachable", "Will try again in {n} min" / "Couldn't send yet: will try again" | dotted, the phone node ink-filled with a trash badge | Undo (secondary, `ic_undo`) |
| Waiting, may have reached the computer (spec D-65) | `DeletionState.WAITING` whose `actions` lack `UNDO` (an attempt with an unknown outcome, `maybe_sent`, architecture 8.8) · none | Waiting on this phone | the reason line as above, then a second line, drawn like the large-file line (bodyMedium ink2, `ic_info` 16 dp): "It may have reached your computer already, so it can't be undone." | as above: the phone still holds it and sends it again | none; no button drawn disabled |
| Deleting (trash title) | `DeletionState.SENDING` · none | Sending | send + "Deleting from your computer…" (imported: "Removing from your computer's list…", D-58) | solid, nib travelling (8) | none |
| Couldn't delete | `DeletionState.REFUSED` (`ComputerDeletion.error`) · `TRY_AGAIN`, `KEEP_CLIP` | Needs your attention, **alert card** (4 dp ink bar) | the title is circle-alert in `critical` + "Couldn't delete “{title}” from your computer." (imported: "Couldn't remove “{title}” from your computer's list.", D-58); the body is the refusal sentence of spec D-62 for the computer's answer (9.26 lists the three), never the computer's raw text | none | Try again (primary, rotate-ccw), Keep the clip (secondary, `ic_undo`) |

A queue-wide pause (Connect again, Update needed, A different computer answered) is the header notice above, as for
every row, and a waiting deletion then shows that pause's words as its reason (order 1 of the list above). An answer
that the deletion was made for a different computer is not a refusal: the row stays in "Waiting on this phone" with
"A different computer answered" and "Undo" (spec D-62), unless an earlier attempt may have reached the computer (D-65).
A deletion that succeeded leaves no row.

Group heads: kicker + italic count + rule, with the group's icon ("Needs your attention" uses circle-alert in
`critical`). Stage changes are announced politely ("Now: Transcribe the words."), never percent ticks.

### 9.20 Sync and storage meters (F-21)

Storage (Settings > Storage): a big "1.4 GB of 2 GB" (displaySmall) over a segmented bar (one ink2 bar per category
told apart by **pattern** — Clips solid, Videos 30° hatch, Waiting to send dotted outline, Cache outline — with
direct labels and sizes, plus a table twin); budget segmented (1 GB / 2 GB / 5 GB / No limit except free space);
"Download videos automatically" switch; per-clip rows sorted by size with "Remove the video", "Remove from this
phone", "Keep on this phone"; "Remove all videos" (danger, confirm). Per-clip download progress: "Clip data ✓ · Audio
60 % · Video waiting" with a thin bar. Errors and limits as caution notices with their copy ("Your phone is almost
full. Downloads are paused.", "Storage budget reached. Remove clips or raise the budget.").

Where each meter and line comes from (names exact): the categories are `StorageUsage.clipsBytes`, `videosBytes`,
`waitingCopiesBytes`, `cacheBytes`; the budget segmented is `StorageBudget.Limit(1/2/5 GB)` / `FreeSpaceOnly`; the
two limit notices are `StorageUsage.belowFloor` and `budgetReached` (also `SyncState.Paused(FREE_SPACE_FLOOR /
BUDGET_REACHED)`); `SyncState.Paused(NOT_CONNECTED)` shows "Downloads continue the next time this phone reaches your
computer."; the per-clip line is `ClipDetail.artifacts` (`ArtifactProgress`: `CLIP_DATA` "Clip data", `AUDIO_MIX`
"Audio", `VIDEO` "Video"; `READY` ✓, `DOWNLOADING` percent, `QUEUED`/`ABSENT` "waiting", `PAUSED` "paused"); an artifact
with two failed verifications shows the caution notice "Download failed: the file was damaged on the way." + "Try
again" (`ClipRepository.downloadToPhone`). Home's sync row is `SyncState.Active` (9.17).

### 9.21 Empty, loading and error states

**Empty-state scaffold** (`EmptyState(illustration, title, body, actions)`): sheet card (or the bare page inside a
tab root), the illustration full width (380 × 214 aspect), title (displaySmall, Newsreader), one sentence (bodyLarge),
at least one action (primary first). Every empty state named by the spec has its illustration (7.2): feed empty,
Home first run, Home no clips, Requests empty, no examples, Practice nothing yet, no computer found, Saved empty.
Search with no result uses no illustration: "No clip matches “{text}”." + "Clear the search".

**Loading** (`LoadingGate`): nothing for 300 ms, then a placeholder (galley blocks at the content's size, static),
then after 1 s a text label ("Loading your clips…"); never an unlabelled spinner.

**Errors**: a caution notice in place of the content (what happened + what to do + a button), never a dialog, never
red text; the rest of the screen keeps working.

### 9.22 Share confirmation sheet (spec 8.4; renders `19`, `20`)

Shown over the sharing app (scrim over it), 28 dp top corners. Content in order: the launcher tile (40 dp) + "Analyze
with PhonoTrainer" (headlineLarge); **what was shared** (galley well: site icon in a sheet well + "YouTube link" +
"youtube.com/watch?v=Ab3x… · Show it all", or file name, kind, size, duration, or "3 videos · 412 MB"); the **bridge**:
a phone node (ink fill), the trace, a computer node; the **connection line** (icon + bold sentence + ink2 sentence);
the "Audio only" switch row; the guidance tip line; the legal line (bodySmall ink2); the buttons "Cancel" and the
primary.

| State | Domain state (`ComputerStatus` after the ≤ 2 s probe; `AddResult`, `IntakeProgress`) | Bridge | Connection line | Primary |
|---|---|---|---|---|
| Checking (≤ 2 s) | `Checking`, `Looking`, or the probe still running | dotted, nib pulsing at the phone (static with Reduce motion) | radar · "Checking your computer…" | Analyze (enabled) |
| Connected | `Connected` (`inLine`, `analyzing` for "in line after 2 other clips") | **solid ink trace, the nib travelling toward the computer**, computer node with a check | monitor-check · "Your computer is connected: the analysis starts now." + "It goes in line after 2 other clips." | Analyze |
| Not reachable / offline / not on Wi-Fi | `NotReachable`, `Offline`, `NotOnWifi` | **dotted pencil line**, the request **parked on the phone** (hourglass badge on the phone node), computer node muted with monitor-off | clock · "Your computer isn't reachable right now." + "This waits on your phone and is sent when you're back on your home Wi-Fi." | Add to queue |
| Not paired | `NotConnected` | dotted line, computer node outlined with a question mark | unplug · "This phone isn't connected to your computer yet. It waits on your phone until you connect." | Add to queue |
| Needs attention | `ConnectAgain`, `ComputerTooOld`, `AppTooOld`, `DifferentComputer` (the queue-wide pause of architecture 8.4) | dotted line, computer node with key-round / circle-arrow-up / monitor-x | circle-alert · "Your computer needs attention ({reason}). This waits on your phone." | Add to queue |
| Preparing the file (files) | `IntakeProgress` (copy, or audio extraction for "Audio only") | the bridge kept; a progress bar on the phone node: "Preparing the file… 40 %"; Back asks "Stop adding this video?" (dialog, 9.23) | | — |
| After the tap | `AddResult.Added` / `AddFilesResult` | the sheet keeps the bridge and shows "Added to your queue." / "Sending to your computer…" → "Sent to your computer." with "Open PhonoTrainer" and "See requests"; closes after 2 s (not with TalkBack, and not while the notification question is open: the in-app question "Get a notification when your clip is ready?" or the system dialog; the 2 s count starts again once it is answered, spec 8.4 item 8, D-51) | | |
| Empty / errors / partial / duplicate | `AddResult.NoLink`, `OutOfSpace`, `DuplicateFound`; `FileRefusal` reasons | the bridge is hidden; a caution notice with the spec copy ("There's no link in what you shared.", "2 of 3 added…", "You already added this link." + "Open it" / "Analyze again anyway") | | |
| No space during the copy (video) | `COPY_FAILED` `copy_no_space` while the sheet is open, with `TRY_AUDIO_ONLY` in `Request.actions` (the sheet holds its sources through `RequestRepository.holdSources`, D-49, SH-AC15) | the bridge kept, the phone node with circle-alert | caution notice "Not enough space on this phone to keep this video until your computer is reachable. Free {N} MB or choose Audio only." | Try audio only (+ Remove): the same request goes back to "Preparing the file… 40 %" for the audio track; no request is left half-made |

### 9.23 Dialogs, snackbars, banners, coach marks

- **Dialog**: sheet, 28 dp radius, float shadow / ring, title headlineLarge (Newsreader 24), body bodyLarge, buttons
  right-aligned (quiet / primary); destructive ones are not dismissible by an outside tap and focus "Cancel" first
  ("Stop analyzing “{title}” on your computer?", "Forget this computer?" (spec D-60: F-22's body, buttons "Cancel"
  (quiet; initial focus) and "Forget this computer" (primary, as render `31` specimen 4 draws it: it does not reach
  every device, CR-01 rule (1) below),
  "Forget your practice history?", "Remove all videos", the mass-deletion question "Your computer no longer lists 9 of 10 clips.
  Remove them from this phone?" (`ClipRepository.pendingMassDeletion`, spec 7.9) and, in the share sheet during a
  copy, "Stop adding this video?"; "Remove “{title}” from this phone?" with the copy of spec F-05 item 5, buttons
  "Cancel" (quiet) and "Remove from this phone" (primary), used by the Clip page, the Library menu and Storage). The
  notification question (spec F-23) is a non-destructive dialog: "Get a notification when your clip is ready?",
  buttons "Not now" (quiet) and "Notify me" (primary, then the system prompt); it is dismissible and dismissing it
  counts as "Not now".
- **CR-01 dialogs** (v1-complete; renders `30`, `31`; anatomy in 9.26): "Delete “{title}” from your computer?", its
  imported variant "Remove “{title}” from your computer's list?" (opened by "Remove from your computer's list…", spec
  D-58), and "Forget your practice history everywhere?" are
  destructive dialogs as above, with three additions. (1) **Danger confirm**: an action that reaches every device is
  the danger button (sheet, 1.5 dp `critical` ring, ink label, trash), never the ink-filled primary, so the most
  inviting button on screen is never the one that cannot be undone everywhere; local or recoverable confirmations keep
  the primary. (2) **Stacked buttons**: when the two buttons do not fit on one line (the computer-wide ones never do
  at 412 dp), they stack full width, the confirming one on top and "Cancel" under it, nearest the thumb; "Cancel" still
  takes the initial focus. (3) **Consequence lines**: each conditional sentence the spec adds ("Your {N} saved items
  from this clip go too.", "Your computer isn't reachable now: …", and the two new sentences of "Forget this
  computer…") is its own line under the body, a hairline above, a 18 dp ink icon and bodyMedium ink2, shown only when
  it applies, read after the body. (4) **Quoted titles**: a clip title inside a dialog title is the quoted title of
  9.26 (cleaned, one line, at most 60 characters, isolated), and no place word, consequence or button label is ever
  built from the title. In v1-complete, "Remove “{title}” from this phone?" gains its two-node reach strip
  (9.26) at the same time as the computer one appears, and the phone-only "Forget your practice history?" gains a
  smartphone place mark, so each pair reads apart at a glance. These pieces (the strip, the mark, the danger confirm,
  the stacked buttons and the consequence line) live in `:core:ui` `ui/place/`, owned by WP-34 (architecture 19).
- **Snackbar**: inverse surface (ink / bone), 12 dp radius, 56 dp, text + one underlined action ("Undo"), above the
  bottom bar; polite announcement; no timer shorter than 10 s; with TalkBack it waits.
- **Banner**: a full-width caution card under the top bar for persistent conditions (credential revoked, the clip was
  deleted on your computer while open). The credential-revoked banner and the snackbar host are drawn once by the app
  shell (work-plan WP-02), not by each screen.
- **One-shot messages** (`UserMessages`, architecture 3.1; one snackbar each, spec copy): `CLIP_GONE` "That clip is
  no longer on this phone."; `REQUEST_REMOVED` "This request was removed."; `CLIPS_REMOVED` "1 clip deleted on your
  computer was removed from this phone." (in the feed, 9.8's wording); `LIBRARY_CHANGED` "Your computer is using a
  different library. Clips from the other one stay on this phone until you remove them."; `TOO_LATE_TO_CANCEL` "Too
  late to cancel: “{title}” is ready. Remove it from this phone if you don't want it."; `CANCEL_UNDONE` "Cancel
  undone: “{title}” won't be cancelled." (D-47; the title is the message's argument). v1-complete adds three, with no
  action (nothing can be undone once the computer deleted it): `CLIP_DELETED_FROM_COMPUTER` "“{title}” was deleted
  from your computer.", `CLIP_REMOVED_FROM_COMPUTER_LIST` "“{title}” was removed from your computer's list."
  (imported) and `DELETION_UNDONE` "Deletion undone: “{title}” stays on your computer." (architecture 3.4; WP-26 adds
  them to the shell's host in CR-01-A2, work-plan 4.7; render `34`). Their argument is the quoted title of 9.26, so the
  place words that end each message are never pushed out or reordered by the title.
- **Coach mark**: an inverse slip (ink fill, onInk text, 12 dp radius, a 14 dp pointer toward its anchor), icon +
  bold tip + one sentence + "Got it" (underlined, 48 dp); one at a time, never pausing, never on a timer; TalkBack
  announces it as a dialog with "Got it".

### 9.24 Top bar and bottom bar

- **Top bar** (secondary destinations): 64 dp, back arrow (48 dp), the title in headlineLarge (Newsreader 28) that
  may wrap to two lines at large text, up to two actions; the lede follows in the content (bodyMedium ink2), so every
  destination opens with a title and a one-sentence lede (F-25 list item 1). Scrolled: sheet fill + hairline.
- **Tab roots** (Learn, Practice): displayMedium title + double rule + lede; Home uses the masthead; Watch uses the
  feed header.
- **Bottom bar**: sheet, 80 dp + gesture area, the double rule above it; four items (Home, Watch, Learn, Practice),
  each a 64 × 32 dp pill + word; selected = **ink pill + onInk icon + bold word** (two cues, no hue). Persistent on
  every destination; hidden only under an overlay sheet expanded to full height (the lesson over the feed, phrase
  details). A lesson opened with `presentation = FULL` (from the Clip page, Learn, Practice, Saved) is a normal
  destination in architecture 16.2 and keeps the bar.

### 9.25 Learn, IPA chart, Insights, Settings components

- **Change card** (Learn, render `12`): family icon + name in its line style; description with IPA; practice badge +
  register; "34 shorts in your clips" (square-play; the short count, D-48, `ChangeCount.shorts`, equal to the Home chip) or "Not in your clips
  yet" (muted).
- **Phenomenon page** (render `13`): chips row, description, "How to hear it", the "For Spanish speakers" tip as a
  tip notice, "Can you copy it?", the mouth demo card (still + segmented "What is said | Dictionary" + "Slow |
  Normal" + "Play the movement"), "Watch examples" (primary lg) + "Practice this", then the **occurrence line** "12 times in your clips" (titleMedium,
  tabular; occurrences, not shorts, D-48: `ChangeCount.occurrences`; + "Counted in the clips on this phone." in bodySmall ink2 when some clips are
  not on the phone) directly above "Examples from your clips" rows (44 dp play circle, the phrase with the word
  marked, clip · time).
- **IPA symbol button**: ≥ 48 dp square, Charis 24, the sound's name under it at large text; weak vowels marked by a
  dotted underline and the word "weak"; selected = ink fill. The chart reflows by group, never scrolls sideways.
- **Insights meter**: label, big value (titleLarge tabular), a 14 dp bar (ink2 fill) over a hatched reference band
  (30° hatch, hairline outline) with "Published: > 60 % · Johnson 2004", "Show as a table".
- **Settings rows** (renders `22`, `22-settings-full`): groups with a kicker (icon + name + rule); rows 56 dp (title +
  description + control); Colors as radio cards with a live preview of the four families (icon + "abc" underlined in
  each style) for each palette; "Show patterns as well as colors" as a three-way segmented "Follow Colors | On |
  Off" (spec F-22: follows Colors / on / off); the sub-page rows (Your computer, Storage, Notifications, Help, About
  and licences) as 56 dp rows with a 40 dp icon well, an optional status line and a chevron.

### 9.26 The two removals: "On this phone" and "On your computer" (spec F-05 items 5-6, F-20, 7.9; renders `28`-`32`, `34`)

The spec asks that removing a clip from this phone and deleting it from your computer are never confused (F-05 user
story, D-54). The design answers with **places**: each removal sits in a block headed by its place, says its reach in
words, and its confirmation draws where the clip goes. Everything here is v1-complete except the "On this phone"
block, which is v1-core and only made explicit (existing components, no new behaviour).

**Place blocks.** At the end of the Clip page's Summary tab (10.2 P-02), two sheet cards 20 dp apart, "On this phone"
first. Each opens with a place head (`PlaceHead` in `ui/place/`): the group head of 9.1 (labelSmall caps, 1 dp `lineStrong` rule) led by the
place's 18 dp icon, smartphone or monitor; the head is a TalkBack heading.

- **On this phone** (v1-core): size rows (label left, size right, bold, tabular: "Clip data and audio · 2.1 MB",
  "Video · 17 MB"); "Keep on this phone" as a switch row (the Library menu's "Keep on this phone" / "Stop keeping" pair as one switch); then full-width
  secondary buttons, left-aligned: "Download the video" (download) or "Remove the video" (film), and "Remove from this
  phone…" (trash). Secondary, not danger: the clip can be downloaded again.
- **On your computer** (v1-complete; absent in v1-core, if the feature is cut, and when the spec offers nothing).
  It reads `RequestRepository.deletionOffer(id)` (architecture 3.4 and 8.8). `DeletionOffer.Available(imported,
  savedItems, connected)` gives the available state and chooses the dialog's variant and consequence lines.
  `InProgress(deletion)` gives the sending, waiting and refused states by `deletion.state`. `NotOffered(reason)` gives
  the not-offered sentence by `NotOfferedReason` (`FORGOTTEN_COMPUTER`, `OTHER_LIBRARY`, `COMPUTER_CANNOT`). Its words
  come from `DeletionCopy`, and its actions are `deleteFromComputer(id)` and `performDeletion(id, DeletionAction.…)`.
  An imported clip (`Available.imported`, `ComputerDeletion.imported`) uses the words of spec D-58 in every state; the
  layout, icons and rings do not change, because the place and the reach are the same (only the list changes).

| State (spec F-05 item 6) | Rendering |
|---|---|
| Available | the sentence "Deleting it here deletes it on your computer and on every device that uses it, including the browser." (bodyMedium ink2), then **"Delete from your computer…"**: the danger button (9.1: 1.5 dp `critical` ring, ink label, trash), full width, alone in its block. Imported (spec D-58): the sentence "Removing it here takes it off your computer's list and off every device that uses it, including the browser. The imported folder stays on your computer." and the button **"Remove from your computer's list…"**, same danger anatomy (it still reaches every device) |
| Sending ("Deleting from your computer…"; imported "Removing from your computer's list…") | status line: trash + the words (titleLarge 800, as a request card's state line); the mini route, solid, the nib travelling (8); no action |
| Waiting (not connected, or not sent yet) | status line: trash + "Waiting to be deleted from your computer" (imported: "Waiting to be removed from your computer's list"); reason line: clock + the first reason that applies of 9.19's "Which reason a waiting row shows" (spec D-61), including "A different computer answered" when the computer said the deletion was made for another one (D-62, not a refusal); the mini route, dotted, the deletion parked on the phone; **Undo** (secondary, `ic_undo`). The rest of the page keeps working (Watch, tabs) |
| Waiting, another library | as above, reason "Your computer is using a different library: this waits until it uses this clip's library again." |
| Waiting, may have reached the computer (spec D-65) | as Waiting, with no "Undo" (`ComputerDeletion.actions` lacks `UNDO`, architecture 8.8) and no button drawn disabled; under the reason line a second line, drawn like 9.19's large-file line (bodyMedium ink2, `ic_info` 16 dp): "It may have reached your computer already, so it can't be undone." The mini route stays as in Waiting: the phone still holds the deletion and sends it again with the same key |
| Refused | the card's alert variant (4 dp ink bar at the start); status line: circle-alert in `critical` + "Couldn't delete “{title}” from your computer." (imported: "Couldn't remove “{title}” from your computer's list."); the reason sentence (bodyMedium ink2) of spec D-62, chosen by the computer's answer, never its raw text: `not_finished` "Your computer says this analysis isn't finished, so it can't be deleted from here."; a computer that no longer offers the deletion (the route is unknown to it) "To delete this clip everywhere, delete it on your computer, or update PhonoTrainer there to do it from this phone."; any other refusal code "Your computer answered that it can't delete this clip right now."; **Try again** (primary, rotate-ccw) and **Keep the clip** (secondary, `ic_undo`). The header shows no pill for it (D-59) |
| Done | the page closes back to where the learner came from; the one-shot message of 9.23 |
| Not offered | one sentence of spec F-05 item 6 (forgotten computer, another library, a computer that cannot delete for phones) with a 18 dp info icon, ink2; no action, no button drawn disabled |

**Mini route** (`DeletionRoute` in `ui/place/`, a visual with plain inputs; decorative, cleared from semantics). Two 44 dp nodes,
"This phone" and "Your computer" (14 sp labels, the node where the deletion is in bold), joined by a trace in 9.7's
grammar: a dotted pencil hairline while it waits on this phone, a solid ribbon with the nib once sent. While it waits,
the phone node is ink-filled and carries a 20 dp trash badge (ink disc, onInk glyph; inverted on the ink node); once
sent, the badge moves to the computer node. It is the request route of 9.19 cut to two places, because a deletion
brings nothing back. The route shows where the phone holds the deletion, not what the computer did: after an attempt
with an unknown outcome (D-65) it is drawn as waiting, because the phone still holds it and will send it again, and
the D-65 line says in words that it may have arrived. Drawing a half-travelled route instead would claim something
nobody knows.

**Reach strip** (`ReachStrip` in `ui/place/`; dialogs only; decorative, cleared from semantics; the body says the same in words). It
sits above the title, in the slot Material gives a dialog icon, left-aligned with the title.
- *Delete from your computer*: four evenly spaced 44 dp nodes with 14 sp labels, "Your computer" (bold, 2 dp `critical`
  ring: where the deletion happens), "This phone" (ink-filled: here), "Other phones", "Browser" (`ic_browser`), each
  with a trash badge, joined by a 1.5 dp ink2 wire through their centres. The imported variant labels the first node
  "Your computer's list".
- *Remove from this phone*: two nodes, "This phone" (ink-filled, trash badge) and "Your computer" (outline ring, check
  badge: it stays), joined by a **dotted** wire: nothing travels to the computer.
- At a font scale above 1.3 only the first node is kept, as a place mark: four labelled columns no longer fit, and the
  body carries the meaning.
- **Place mark**, for a dialog without a strip: one 44 dp node; "Forget your practice history everywhere?" uses
  monitor-smartphone (`ic_pairing`) with the 2 dp `critical` ring, the phone-only "Forget your practice history?"
  smartphone with the `outline` ring.

**The confirmations** (9.23 rules; copy exactly spec F-05 items 5-6 and F-17 item 6):

| Dialog | Strip or mark | Consequence lines | Buttons |
|---|---|---|---|
| "Delete “{title}” from your computer?" | four nodes | bookmark + "Your {N} saved items from this clip go too." (N > 0); clock + "Your computer isn't reachable now: the clip is deleted there the next time this phone connects, and you can undo it until then." (not connected) | stacked: danger "Delete from your computer", then "Cancel" (secondary; initial focus) |
| "Remove “{title}” from your computer's list?" (imported) | four nodes, "Your computer's list" | as above | stacked: danger "Remove from your computer's list", then "Cancel" |
| "Remove “{title}” from this phone?" | two nodes, dotted (v1-complete; v1-core shows none) | — | one line: "Cancel" (quiet; initial focus), "Remove from this phone" (primary) |
| "Forget your practice history everywhere?" | mark: monitor-smartphone, critical ring | — | one line: "Cancel" (quiet), "Forget it" (danger) |
| "Forget your practice history?" (nothing shared) | mark: smartphone (v1-complete) | — | one line: "Cancel" (quiet), "Forget it" (primary) |

What tells the two removals apart, so no single cue carries it (A-01): the block and its place head; the sentence; the
button (secondary with the phone's words, danger ring with the computer's); in the dialog, the strip (two places and a
dotted wire against four places and a solid wire from a critical-ringed computer), the title's place words, the verb
("Remove" / "Delete"), the confirm button (ink fill against danger ring) and the layout (one line against stacked).
An imported clip's removal shares the verb "Remove" with the phone's (spec D-58), so for it the verb is no cue; every
other cue above still differs ("from your computer's list" against "from this phone", four places against two, danger
against primary, stacked against one line), which is why no single cue is ever relied on. Render `31` sets them side
by side.

**Quoted titles** (v1-complete; finding 14 of the CR-01 security review). A clip title is untrusted text. The person who uploaded a video
chooses a link's title, and the computer's `clean_title` removes only the media extension and a trailing id
(server-contract 6.3). A crafted title could otherwise reorder the place words of "Delete “{title}” from your
computer?" or push them away, for example with a right-to-left override, so that the destructive question reads like a
phone-only removal. So every `{title}` in the CR-01 copy is the **quoted title**, made in three steps in this order:
1. **Cleaned.** The same Unicode categories as device names (server-contract 2.3) are taken out. Format characters
   (`Cf`: bidirectional overrides, embeddings and isolates, zero-width characters) are removed. Control characters and
   line and paragraph separators (`Cc`, `Zl`, `Zp`) become a space, so two words never run together. Runs of white
   space become one space, and the result is trimmed.
2. **One line, at most 60 characters.** After step 1 no line break is left. The title is counted in user-perceived
   characters (grapheme clusters, `android.icu.text.BreakIterator.getCharacterInstance()`), so an emoji or an accented
   letter is never split. A longer title keeps its first 59, trimmed, and ends with "…" (U+2026). Why 60: this was
   measured with the bundled Newsreader at headlineLarge in the dialog's 320 dp content width. An ordinary 60-character
   title makes "Delete “{title}” from your computer?" three lines at font scale 1.0 (four for the imported title) and
   eight or nine at 2.0. Even 60 of the widest letters take ten lines at 2.0, about 580 dp. So the place words always
   end on the dialog's first screen, above the body and the fixed buttons. The full title stays where it stands alone:
   the Clip page header and the Library row.
3. **Isolated.** The title is wrapped with `BidiFormatter.unicodeWrap` (`androidx.core.text`, left-to-right context,
   because the app is English only). A right-to-left title keeps its own order inside the quotes and never reorders
   the quotes or the words around them. This step runs last, so step 1 never removes the marks it adds and step 2
   never counts them.

It applies to the dialog titles "Delete “{title}” from your computer?" and "Remove “{title}” from your computer's
list?", and in v1-complete to "Remove “{title}” from this phone?". It also applies to "Couldn't delete “{title}” from
your computer." and its imported form "Couldn't remove “{title}” from your computer's list." (the Clip page block and
the Requests title), to the Requests title lines "Delete “{title}” from your computer" and "Remove “{title}” from your
computer's list", and to the three one-shot messages (9.23). TalkBack reads the same text, the dialog's pane title included;
the bidi marks are not spoken. Nothing else changes. The strip, the body, the consequence lines and the buttons
already say the place without the title, no place word or consequence is ever built from it, and no copy is added.
One function makes the quoted title. `DeletionCopy` (WP-34) and the shell's message host (WP-26) both call it; the
architecture names it and decides its module (architecture 19). Tests:
- A unit test of the three steps:
  - `"Coffee shop small talk \u202Eylno enohp siht morf with the barista at the morning rush"` (an override that is
    never closed, and too long) gives `Coffee shop small talk ylno enohp siht morf with the barist…`;
  - `"Coffee shop\u2028small talk\nwith the barista"` gives `Coffee shop small talk with the barista`;
  - `"\u05D0\u05D1\u05D2 123"` (right-to-left letters, then digits) keeps its text inside the wrapper that `unicodeWrap`
    adds for a right-to-left string.
- A screenshot test of the "Delete from your computer" dialog with the first title, light and dark, at font scale
  1.0 and 2.0. Its reference is render `31`, specimen 5: no U+202E is left, and "from your computer?" follows the
  closing quote in reading order.

**TalkBack**: place heads are headings; "Delete from your computer…" reads its words ("…" is not spoken; the dialog
that follows is announced by its pane title); the status line is a polite live region that announces only transitions
("Waiting to be deleted from your computer", "Deleting from your computer", "Couldn't delete …"; for an imported clip
D-58's words), never the route; D-65's line is read after the reason line and is part of the announcement when a
deletion returns to waiting without "Undo"; if "Undo" had the accessibility focus when it goes, the focus moves to the
status line, never to the top of the page; "Undo" has the accessible name "Undo the deletion from your computer"
(imported: "Undo the removal from your computer's list"; it starts with the visible word); the dialogs are alert
dialogs whose title is the pane title, the strip and the mark are not read, the body and then each consequence
line are, and the initial focus is "Cancel". Library rows and shelf cards read the deletion line inside their one
sentence ("Coffee shop small talk, 4 min 21 s, on this phone, waiting to be deleted from your computer").

**Contrast** (section 3 values): `critical` ring on sheet 5.42 / 6.16; dashed `outline` ring on galley 3.70 / 3.93
(the words carry the state; the ring is the second cue); ink2 wire on sheet 9.72 / 10.73; badge glyph onInk on ink
17.11 / 16.56, badge disc ink on sheet 17.11 / 15.11; labels ink2 on sheet 9.72 / 10.73. Nothing new enters
`palette.json`, so the gates are unchanged (499 checks pass).

**Large text** (A-06): the status lines, reasons, labels and the header pill wrap and are never cut; the mini route
keeps its nodes and lets its labels wrap; dialog content scrolls under fixed buttons; the strip collapses as above.

### 9.27 The shared-progress line (spec F-17 item 6, F-09 item 5, 7.13; renders `33`, `34`)

`SyncLine` (the `StatusLine` of 9.17 at bodySmall: a 16 dp ink icon and 14 sp ink2 words, wrapping) sits under the
heading "Your accuracy so far" in Practice and under the lede of Saved (P-11). Exactly one variant shows. It reads
`SharingState` from `PracticeRepository.sharing()` in Practice and `SavedRepository.sharing()` in Saved (architecture
3.4 and 7.8). `SyncLine` lives in `ui/place/`, and the Practice and Saved screens are WP-34's (`:feature:practice`):

| Variant | Domain state | Icon | Words (spec) |
|---|---|---|---|
| Nothing shared (not paired, forgotten, or a computer without the capability) | `SharingState.NotShared` | smartphone | "Kept only on this phone." |
| Shared | `SharingState.Shared(lastSyncedAt)` | monitor-smartphone (`ic_pairing`: this phone and your computer) | "Shared through your computer · last synced {time}" ({time} relative, the same formatter as the Library's "Last synced") |
| A reset waiting on this phone | `SharingState.ResetWaiting` | clock | "Reset on this phone. It reaches your computer the next time this phone connects." |

In v1-core the line stays the short "Kept only on this phone" beside the heading (render `14`). In v1-complete it moves
under the heading, because the shared variant does not fit beside it, and the heading's end holds "Reset" (quiet
button), which opens the matching reset dialog of 9.26. While a reset waits, the accuracy rows look as they do after a
phone-only reset. Every place that shows accuracy (the progress rows, "Worth reviewing", the Home practice card) shows
the shared totals with no extra mark: the line under the heading is the one place that says where the numbers come
from. Nothing about shared progress appears on Home's status card, in Requests or in a notification (spec 7.13).
TalkBack reads the line as text after the heading ("min" is spoken as "minutes"); it is not a live region.

### 9.28 Version 2 notes (spec 14.6; not v1, nothing drawn or exported now)

Recorded so version 2 starts from the system, not from scratch; none of it is v1 work and no mockup is made until it is
scheduled.
- **Connection details** (P-07) gains one fact row, icon + words: Lucide `lock` + "Encrypted connection" (a future
  `ic_conn_encrypted`), or, with a version-1 computer, Lucide `lock-open` + "Not encrypted: update PhonoTrainer on your
  computer to encrypt this connection." (a future `ic_conn_not_encrypted`). Ink only, never red, no badge on Home: the
  spec asks for "visible, but quiet" (item 7).
- **Identity changed** (item 4): the status card uses the "A different computer answered" look (monitor-x,
  `ic_conn_different`), because the spec treats it as that state; Connection details shows the spec's sentence in a
  caution notice with "Connect again" (primary). No new colour, no new illustration.
- Pairing screens (P-06) are unchanged: the spec allows no new step.

---------------------------------------------------------------------------------------------------------------

## 10. Screens: layout notes per destination

Common frame: status bar (edge to edge, icons in ink), content on `page` with grain, 16 dp gutters, the bottom bar
on every destination. Each destination opens with a title and a one-sentence lede (F-25 item 1). Scroll containers
are vertical only; nothing scrolls sideways except the Home clip shelf (which also has "All clips").

### 10.1 Tabs

| Id | Destination | Layout (top to bottom) | Mockups |
|---|---|---|---|
| T1 | **Home** | Masthead + double rule + dateline + lede; the ten blocks of 9.16 in spec order, 20 dp apart; pull to refresh | `01-home`, `02-home-full`, `03-home-first-run` |
| T2 | **Watch** | Vertical pager of short cards (9.8), one card per screen height minus the bottom bar; question cards and end cards are pages of the same pager. Landscape (rotation): the media takes the left 56 %, slip/captions/rail the right column; state is kept | `04`, `05`, `06`, `07`, `08`, `27` |
| T3 | **Learn** | Title "Learn" + double rule + lede (web); "How to study a change" card (three numbered steps); three entry tiles (IPA chart, Words we use, Insights); one section per family (icon + name in its line style + "dashed line · scissors: the stop is clipped") with change cards; "Whole-word changes" last | `12-learn` |
| T4 | **Practice** | Title "Practice" + the ear spot + double rule + lede (web); the start card (stacked): the three kinds with icons and one-line explanations, the focus chip "Only: flapping ✕" + "Practice everything instead", the "Focus on my weak spots" switch, "Start practicing"; "Your accuracy so far" with "Kept only on this phone" and per-change rows (icon + name, "4 of 9 · 44 %", a 10 dp ink2 meter), weakest first, "Reset"; tiles "Saved 12" and "Changes you have met 11 of 16". v1-complete: the shared-progress line under the heading and "Reset" at its end (9.27) | `14-practice`; v1-complete `33-practice-shared` |

Practice session (inside T4): the quiz card of 9.14 full screen with "Question 3 of 10" and a 10-bead row; results:
"7 of 10 right" (displayLarge), "Worth reviewing" change chips, "Practice again", "Practice my weak spots". Empty:
`ill_practice_empty` + "Nothing to practice yet" + the spec sentence + "Add a clip".

### 10.2 Secondary destinations

| Id | Destination | Layout | Mockup |
|---|---|---|---|
| P-01 | **Library** | Top bar "All clips" + Add; lede; "IN PROGRESS 3" group (request mini-rows with inline primary action) + "2 more started on your computer"; search field ("Search your clips…"); filter chips "On this phone" / "Not on this phone"; a row "8 of 10 clips" (live count, announced) + sort menu "Newest first ▾"; clip rows (9.18). Offline banner: "Showing what this phone knows. Last synced {time}." | `15-library` |
| P-02 | **Clip page** | Hero poster (200 dp, back button on an overlay plate) or the drum for audio clips; title (headlineLarge), meta, availability badges, "Watch · short 4" (primary) + "From the start"; tabs Shorts / Transcript / Summary (selected = bold + 3 dp ink bar); Shorts: numbered rows (italic numeral, spotlight chip + word in its mark, time range · marked words, "✓ Seen" / "Next"); Transcript: phrases with play buttons and marked words, filter chips, mini player; Summary: "Changes in this clip" rows, "How reduced is this speech?" meters, "About this clip"; "On this phone" block with sizes and actions, then (v1-complete) the "On your computer" block (9.26); a deletion from your computer adds its pill to the header (9.18) | `16-clip-page`; v1-complete `28-clip-computer`, `29-clip-delete-waiting`, `30-delete-dialog` |
| P-03 | **Requests** | Top bar; lede; the connection line; groups in the spec order, each with a kicker head (icon + name + italic count + rule) and request cards (9.19); empty: `ill_requests_empty` + "Nothing in progress. …" + "Add a clip". v1-complete: deletion rows in Needs your attention, Waiting on this phone and Sending (9.19) | `17-requests`; v1-complete `32-requests-deletions` |
| P-04 | **Request detail** | Top bar "Request" + menu; lede; source card with the milestone timeline (dots on a 2 dp ink spine: done = ink disc + check, current = ring + loader, next = outline with the place icon, each with its time); "What your computer is doing" (headlineMedium) + estimate; the six stage rows with status words and teaching lines; "Show the log", "Cancel"; for failures the alert card on top with "Show details" | `18-request-detail` |
| P-05 | **Add a clip** | Top bar; lede (web); "Paste a link" field with "Paste" (clipboard read only on tap) and the recognised site label ("YouTube", "YouTube Shorts", "X", "Other website: downloads may not work."); "Choose a file" (picker; then a list of chosen files with name, duration, size); options (Audio only, Separate the dialogue) as switch rows with their sentences; the legal line; the bridge and the connection line of 9.22; primary "Analyze" / "Add to queue" | (share sheet `19`, `20`) |
| P-06 | **Connect to your computer** | Top bar; lede (spec); the three-step instruction card for the computer side (numbered; the command shown in a galley code well, Atkinson 15 sp, selectable, with "Copy"); "Looking for your computer…" (radar) then found computers as radio rows (name, "PhonoTrainer {version}", status word; exactly one found is preselected); **conflict** (`SearchState.Found.conflict`: two answers share a name, or one server id answers from two addresses; spec D-39, F-02-AC10): every answer is its own row, none is preselected, and a caution notice sits above the list (triangle-alert + "Two computers answered with the same name. If you aren't sure which one is yours, enter the address shown on your computer instead." + "Enter the address instead" as a secondary button inside the notice); "Enter the address instead" (field "Computer address and port", placeholder "As shown on your computer", "Check"); code entry: six 48 dp digit cells (ink ring on focus, numeric keypad), optional phone-name field, "Connect"; success: "Connected to {name}" + the sentence. Empty: `ill_no_computer` + "No computer answered." + the numbered checklist (first item "Is PhonoTrainer running with phone access on? If your computer joined a new network, run phonotrainer phone on there.", D-42; `phonotrainer phone on` set in the code style of the instruction card) + "Search again" + "Enter the address instead". Offline: wifi-off notice + "Open Wi-Fi settings". Errors under the code cells as critical-icon lines (wrong code, expired, too many tries; focus stays in the cells); a typed address without phone access or a too-old computer (`CheckResult.PhoneAccessOff` / `TooOld`) reads as a caution line under the field ("That address answered, but phone access isn't on there for this network. On your computer, run phonotrainer phone on, then try again." / "Needs a newer PhonoTrainer on your computer"), the too-old row is not selectable. Modes (`ConnectKey.mode`): `FIRST_RUN` adds "Not now" (quiet) with "You can connect later from Home."; `FROM_HOME` as described; `CONNECT_AGAIN` offers only the paired computer, any other found computer is a muted row "This phone is connected to {name}. Forget it first in Settings to connect to another computer."; `NEW_ADDRESS` opens on the address field and accepts only the paired computer | `21-connect-none` |
| P-07 | **Connection details** | Top bar; the status card (9.17); the "Why?" checklist (numbered, not checkboxes; the phone-access item as in P-06); last probe result in words, from `ProbeOutcomeCopy` (WP-15, next to `ConnectionStatusCard`) ("No answer after 3 s"; for `ProbeOutcome.PhoneAccessOff`, phone access off or not approved, "Your computer answered, but phone access is off there or not approved for this network.", D-42); facts as label/value rows: the computer's address, "This phone on this Wi-Fi: {address}" (D-39, F-22-AC4; `ConnectionDiagnostics.phoneAddress`, tabular, selectable), short server id, API version, pending counts, background restriction, decoder capability ("AV1 up to …"); "Copy diagnostics" (its text carries no address and no id); `ill_no_computer` at the top when not reachable. Version 2 only: the encryption row and the identity-changed notice (9.28) | — |
| P-08 | **Phenomenon page** | Top bar with the change name in its line style + "What's this?"; chips; description; "How to hear it"; the Spanish-speaker tip (where the content has one); caveat; "Can you copy it?"; "The mouth" demo; "Watch examples" + "Practice this"; the occurrence line "12 times in your clips" (9.25, D-48); "Examples from your clips" (up to 8). Empty examples: `ill_no_examples` + web copy + "Add a clip" | `13-phenomenon` |
| P-09 | **IPA chart + symbol sheet** | Top bar; lede; sections (Consonants by manner, "Sounds that replace t and d", Syllabic consonants, Vowels, Diphthongs, Vowels with r) as wrapping grids of 56 dp symbol buttons; nothing selected: "Choose a symbol — Each symbol is one sound. Start with [ɾ], [t̚] or [ə]"; the symbol sheet: symbol (Charis 40), name, the mouth with "Play the movement", glossary text, "As in …", the change it signals, "In your clips" (up to 6 playable rows) | — |
| P-10 | **Insights** (v1-complete) | Top bar; overview line ("10 clips on this phone · 4,321 words · 42 min of speech"); metric meters (9.25) each with "What's this?" and "Show as a table"; "What this means for your listening" takeaways; "Changes across your clips" rows (icon + name + count + a family-hatched bar on sheet; tap → change mode). Empty: "Your clips are counted here — …" | — |
| P-11 | **Saved** | Top bar; lede; v1-complete: the shared-progress line under the lede (9.27); "Watch saved" (primary); rows: word (in its mark), change chip, clip, "Play", "Last watched {time}", unsave (bookmark-check toggle); an item saved on another phone whose clip is not here reads "Download the clip to watch it." (spec F-09) as for any item whose clip is not on this phone. Empty: `ill_saved_empty` + "Nothing saved yet" + the spec sentence + "Go to Watch" | `24-saved-empty`; the v1-complete line in `34-deletion-states` |
| P-12 | **Settings** and sub-pages | Groups (Display, Playback and captions, Learning, Your computer, Storage, Notifications, Help, About and licences) as rows with icons. Display, Playback and captions and Learning are groups on the Settings page itself; the sub-pages are exactly the navigation keys (architecture 16.1): Your computer (`YourComputerKey`: the status card 9.17, actions, "Forget this computer…" danger, whose dialog gains in v1-complete the two consequence lines of 9.23 when they apply, render `31`), Storage (`StorageKey`, 9.20), Notifications (`NotificationSettingsKey`: "Notifications are off for PhonoTrainer" + "Open settings" when denied), Help (`HelpKey`: "Restart the tour", "What do the marks mean?" opening the same legend sheet as the short's More sheet, "How PhonoTrainer works"), About (`AboutKey`: version, licence line, the source location, the privacy statement) and its "Open-source licences" list (`LicencesKey`: every component, font and the Lucide icons with their licence texts, readable offline). The page (`SettingsKey`): top bar "Settings" + back, the lede "All settings apply at once and are remembered on this phone.", the three groups (kicker with icon and rule: Display palette, Playback and captions captions, Learning graduation-cap), then the five sub-page rows (9.25). Display: Appearance segmented with icons, Colors radio cards with previews, "Show patterns as well as colors" three-way segmented "Follow Colors / On / Off", Motion segmented, the text-size note | `22-settings-display` (the Settings page as it opens: top bar, lede, the Display group), `22-settings-full` (the whole page) |
| F-14 | **Words we use** (`GlossaryIndexKey`, from Learn's entry tile) | Top bar "Words we use"; lede "Plain-English explanations of the terms and measures PhonoTrainer uses. Each one also opens from the “?” next to it."; a search field "Search the words we use…" with the live count "{n} of {total} terms" under it (announced, as the Library's); two kicker groups "Terms" and "Measures", as 56 dp rows (term in titleMedium + the first sentence of its explanation in bodyMedium ink2 + chevron); a row opens the "What's this?" sheet (9.13); no match: "No term matches “{text}”." + "Clear the search" (quiet), no illustration (9.21) | — |
| P-13 | **Welcome** | Wordmark + "Skip" (quiet); a stacked, crop-marked stage with the page's illustration and, on page 1, a sample slip ("Flapping · Safe to produce · “water” /ˈwɔtɚ/ → [ˈwɔɾɚ]"); kicker "1 of 3"; title (displayMedium); one sentence (18 sp ink2); page indicator (current = 28 dp ink capsule) + "Next" / "Get started" (primary lg) | `23-welcome` |

### 10.3 Sheets and overlays

| Sheet | Notes | Mockup |
|---|---|---|
| Word lesson | 9.9-9.11; half height over the paused short, expandable; full height from Clip page, Learn, Practice, Saved | `10`, `11` |
| Phrase details | 9.12; full-height sheet; charts with table twins | — |
| "What's this?" | 9.13; half height | — |
| Feed mode picker | 9.12 | `09-mode-picker` |
| Short "More" sheet | 9.12; includes the legend "What do the marks mean?" | — |
| Marks legend "What do the marks mean?" | 9.12; one `MarksLegend` (`:core:ui`, 12.2) opened from the More sheet and from Settings > Help | — |
| Share confirmation | 9.22, over the sharing app | `19`, `20` |
| Coach marks | 9.23 | `06` |
| Confirmation dialogs | 9.23; the CR-01 removals and resets 9.26 | `30-delete-dialog`, `31-removal-dialogs` |

---------------------------------------------------------------------------------------------------------------

## 11. Accessibility rules (spec section 10, made visual)

| Id | How this design meets it |
|---|---|
| A-01 Never colour alone | Family = line style + icon + name (+ colour); status = icon + word; answers = icon + words + ring weight; selection = ink fill, ring or check; chart series = pattern/dash + direct label; the two removals (v1-complete) = place head + words + button style + reach strip, the critical ring only a third cue (9.26). Grayscale proofs: `04-watch-video-light-grayscale.png`, `05-watch-audio-offline-dark-grayscale.png`, `10-lesson-compare-light-grayscale.png` |
| A-02 Hue reserved | Gate G8: every non-family token ≤ 0.05 chroma; illustrations and brand use ink, paper and kraft; anatomy uses the tract palette |
| A-03 Targets | 48 dp minimum everywhere: caption words (hit areas into the line gap), "?" controls (20 dp glyph in a 48 dp box), IPA symbols (56 dp), sorts (80 × 140 dp), rail (64 × 60), chips in lists (48 dp tall), digit cells; quiz answers ≥ 56 dp |
| A-04 TalkBack | Every control labelled (icon buttons require a label in the API); the short is one node with summary + custom actions; words read "word, changes, advice[, listen for]"; sheets have pane titles; headings marked (titles, section heads, kickers); illustrations, textures, the trace, the halftone and the tether are invisible to accessibility (so are the CR-01 reach strip, place marks and mini route, whose meaning is in the words beside them, 9.26); polite announcements only for stage changes, answers, shadowing phases, recording hints, connection transitions and deletion-state transitions |
| A-05 IPA speakable | `IpaText` exposes symbol names ("[ɾ]: voiced alveolar tap") and a play action |
| A-06 Font scaling to 200 % | All text in sp, no fixed heights on text containers, display sizes scale non-linearly, the rail becomes the action bar (9.6), captions follow the playhead line by line, top-bar titles wrap, tables reflow to stacked rows, the IPA chart wraps. Render `27-watch-large-text` |
| A-07 Reduced motion | Section 8 column 3; content motion (trace, mouth) follows playback only; no infinite loops at any setting |
| A-08 No autoplay with TalkBack | The short waits for "Play"; the share sheet does not auto-close |
| A-09 Captions for all speech | Every played sound has its text; "your turn" is text + optional haptic |
| A-10 Contrast | Section 3: body ≥ 7:1 (13.20-17.11), secondary ≥ 4.5:1 (ink2 ≥ 7.50, inkMuted ≥ 5.22), marks ≥ 3:1 on every surface they may sit on (≥ 3.05), focus and control outlines ≥ 3:1, overlays gated over worst-case frames |
| A-11 No time limits | Questions untimed; coach marks and snackbars wait; the only limit (30 s per take) is stated before recording |
| A-12 Gesture alternatives | Next/Previous short buttons in the header; Replay, Speed, Loop on the rail; long-press always has a "More" equivalent |
| A-13 Keyboard, D-pad, switch | Reading order = visual order (section 9 anatomies list it); one focus ring everywhere: 3 dp `focus`, 2 dp offset, following the shape |
| A-14 Charts | Text summary + "Show as a table" on every chart (pitch, prominence, overlay, meters, storage) |
| A-15 CVD palette + patterns | Settings > Display: palette radio cards with previews; patterns default on with the CVD palette (render `05`) |
| A-16 Plain language | A title and a one-sentence lede on every destination; "What's this?" after every term; errors say what happened and what to do |

Checks WP-03 adds: the palette gate as a JVM test; `A11yAudit.assertClean` (architecture 18) on every component
preview; screenshot tests at font scale 1.0, 1.3 and 2.0, light and dark, Standard and CVD; a grayscale screenshot
review of Home, Watch, Lesson and Requests.

---------------------------------------------------------------------------------------------------------------

## 12. Implementation map (aligned with architecture section 17)

### 12.1 `:core:designsystem` (theme, tokens, brand drawing, base components)

| File (package `com.mapineda48.phonotrainer.core.designsystem`) | Contents |
|---|---|
| `theme/Palette.kt` | Raw values from `palette.json`: `Neutrals.Light/Dark`, `FamilyPalette.Standard/Cvd × Light/Dark` (+ tints), `Tract.Light/Dark`, `Brand.Light/Dark` (kraft, halftones, drum and poster papers), `Launcher` constants. Generated or hand-copied; the JVM gate test reads these values |
| `theme/ColorScheme.kt` | `phonoLightScheme`, `phonoDarkScheme`: the explicit Material 3 mapping of 2.2 |
| `theme/PhonoColors.kt` | `@Immutable data class PhonoColors(page, sheet, galley, deepSheet, ink, ink2, inkMuted, onInk, outline, lineStrong, hairline, focus, good, critical, scrim, letterbox, kraft, kraft2, halftone, halftoneStrong, grainAlpha, drumPapers, posterPapers, family: FamilyColors, tract: TractColors)` with `FamilyColors(reduction, td, assimilation, boundary, tints…)`; `LocalPhonoColors` |
| `theme/Type.kt` | `FontFamily` for Atkinson (variable), Newsreader (variable, roman + italic, opsz per role), Charis (Regular, Bold); `phonoTypography` (4.2); `PhonoType` extras (spotlightWord, lessonWord, captionCurrent, captionDim, accentItalic, ipa*) |
| `theme/Shape.kt` | `phonoShapes` (5.1), `PerforatedTopShape`, `SortShape` |
| `theme/Spacing.kt` | `PhonoSpacing` (5.2) and target sizes |
| `theme/Motion.kt` | durations, easings, spring spec; `PhonoMotion` honouring `LocalReducedMotion` (architecture 18) |
| `theme/Theme.kt` | `PhonoTheme(darkTheme, colors: ColorChoice, patterns, reducedMotion, content)`: provides the scheme, `PhonoTheme.colors/type/motion/patterns`, and a configuration context whose `uiMode` follows the in-app appearance so `drawable-night` resources (illustrations, splash) resolve with the in-app choice |
| `texture/PaperGrain.kt`, `texture/Halftone.kt` | `Modifier.paperGrain()`, the seeded tile cache; `HalftoneCache` (seed → `ImageBitmap`) |
| `trace/StylusTrace.kt` | The trace path builder (ribbon from samples + widths, runs split at gaps) and `StylusTrace(samples, progress, ticks, pin, pinNow, style)` Canvas composable; used by the progress bar, the drum, the route line and the bridge |
| `brand/PhonoMark.kt` | The stylus P as an `ImageVector` (from the master path) for the masthead and the about screen |
| `icons/PhonoIcons.kt` | `object PhonoIcons { val FamilyTd = R.drawable.ic_family_td … }` for every entry of `icons.json` |
| `illustration/PhonoIllustrations.kt` | The `ill_*` drawable ids and their intrinsic aspect |
| `component/*` | Buttons, IconButton, Segmented, Switch, RadioCard, TextField, Chip, Badge, Notice, ProgressBar, Card (stack, crop marks, alert), Kicker, DoubleRule, TopBar, BottomBar, Sheet frame, Dialog, Snackbar style, CoachMark slip, the route-line and stepper visuals, TypeSort, EmptyState scaffold visuals |

### 12.2 `:core:ui` and the feature modules (architecture 17, work-plan 4.4 and 4.5)

| Where | Composables (section of this document) |
|---|---|
| `:core:ui` primitives (WP-03) | `FamilyMark` and `MarkedWordSpan` (line style + icon + name; draws the six underlines and the ‿ arc with `drawBehind` from word boxes in a `FlowRow`, 9.4), `ChangeChip`, `PracticeBadge` (9.1), `StatusLine` (icon + words for every request, connection and availability state, 9.17-9.19), `AvailabilityChip` (9.18), `ExplainButton` + `ExplainSheet` (9.13), `IpaText` (4.3), `LoadingGate`, `EmptyState(illustration, title, body, actions)` (9.21), `CoachMarkHost` (9.23), `PhonoButton`, cards and sheets (9.1; the frozen `:core:ui` names of the 12.1 `component/` visuals); visual-only pieces WP-03 adds additively (plain inputs, no domain logic): `ShortProgress` (9.7: feed and Clip page player, over `StylusTrace`), `PosterArt` (7.4: poster, poster fallback or audio art for Home, Library, Clip page and the feed), `MarksLegend` (9.12: the More sheet and Settings > Help) |
| `:core:ui` `component/` (shared, one owner each, work-plan 4.5) | `ClipCard` / `ClipRow` (9.18, WP-16), `RequestRow` (9.19: the request card and its mini-row, WP-22), `QuestionCard` + `AnswerFeedback` (9.14, WP-21), `ShadowingPanel` (9.11 step 5, WP-18), `OccurrenceRow` (9.25 example rows, WP-20), `ConnectionStatusCard` (9.17: Home, Connection details, Settings > Your computer; WP-15), `ShareBridge` + `ConnectionLine` (9.22: share sheet and Add a clip; WP-23). They are domain-aware (`ComputerStatus`, `SyncState`, request counts), so by architecture 17 each has one feature owner |
| `:core:ui` `extension/` (v1-complete, work-plan 4.4) | `MouthRenderer` draws the sound-card still and step 4 (7.5, 9.10-9.11; absent: the name and the mouth sentence); `RecordSection` (9.11); `PhraseDetailsEntry` (9.12); `ClipTranscriptTab` (10.2 P-02); `InsightsAvailability` (Home block 10, Learn tile); `ClipReductionMeters` (P-02 Summary) |
| `:feature:watch` | Feed header (9.2), `SpotlightSlip` + tether (9.3), `CaptionFlow` (9.4), media framing (9.5), `ActionRail` / `ActionBar` (9.6), short card (9.8), mode picker and More sheet (9.12), end cards (9.15), `AudioOnlyVisual` (7.3, architecture 11.6) |
| `:feature:lesson` | Lesson frame and header (9.9), `ComparePanel` (forms, legend, type sorts, word headers, gap box) and `SoundCard` (9.10), steps 1, 3, 4, 5 (9.11) |
| `:feature:home` | Masthead, Today's set with the stations thread, Keep watching, Add a clip slots, New on this phone, Watch by change, Practice card, Insights teaser (9.16) |
| Other features | Learn (9.25, 10.1), Practice (10.1), Library and Clip page (10.2), Requests and Add a clip (9.19, 10.2), Connect (10.2), Settings and Storage (9.20, 9.25), Welcome (10.2), share (9.22) |
| CR-01, v1-complete (owner WP-34, architecture 19; work-plan WP-34 and 3.2) | Visual pieces with plain inputs in the new `:core:ui` sub-package `ui/place/` (strings in `strings_place.xml`): `PlaceHead` (the 9.1 group head with a place icon), `ReachStrip` (computer, imported, phone; the single-node place mark), `DeletionRoute` (the mini route), `SyncLine` (`StatusLine` at bodySmall), and the danger confirm, the stacked buttons and the consequence line of the dialog of 9.1 / 9.23. `DeletionCopy` in `:core:ui` `component/` (`strings_deletion.xml`): every deletion word for the Requests rows and the Clip page block, with the quoted title of 9.26. Domain-aware: the "On your computer" block (`:feature:library`, on `deletionOffer`), the deletion line in `ClipRow` / `ClipCard` (on `ClipSummary.deletion`), `DeletionRow` in `:feature:requests` (on `deletions()` and `DeletionAction`; not a `RequestRow` kind), and the line and "Reset" in Practice and Saved (`:feature:practice`, on `SharingState`) (9.18, 9.19, 9.26, 9.27). Outside WP-34: the three one-shot messages in the shell's host, the two icons and the contract additions are WP-26's, in CR-01-A2 (work-plan 4.7) |

Feature modules hold no colour, font or text size of their own: they compose these pieces with `PhonoTheme` tokens.

### 12.3 Resources and assets

WP-03 converts the staging export into `:core:designsystem` (work-plan WP-03: every resource lives there; the merged
manifest of `:app` references the launcher icon, the splash and the notification icon from it). Conversion scripts
belong to WP-03 in `android/design-src/convert/`; the design tools of section 13 are what they call.

| Resource | Staging path (`android/design-src/export/`) | Final home |
|---|---|---|
| Fonts | `res/font/*.ttf` | `:core:designsystem` `res/font/` |
| Font and icon licences | `assets/licenses/*.txt` | `:core:designsystem` `assets/licenses/` (merged into the APK; About reads them) |
| Icons `ic_*.xml` | `res/drawable/` | `:core:designsystem` `res/drawable/` |
| CR-01 icons `ic_undo.xml`, `ic_browser.xml` (v1-complete) | `android/design-src/cr01/export/res/drawable/` (a separate staging tree, not part of the 173 files) | `:core:designsystem` `res/drawable/`, converted by the integrator WP-26 in CR-01-A2 with their `PhonoIcons` entries, `bundled.json` and notices 7.3 (6.1; work-plan 4.7, WP-26) |
| Illustrations `ill_*.xml` | `res/drawable/` + `res/drawable-night/` | `:core:designsystem` |
| Launcher layers and monochrome | `res/drawable/ic_launcher_background.xml`, `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`; `res/mipmap-anydpi/ic_launcher.xml`, `ic_launcher_round.xml` | `:core:designsystem`, same paths. The adaptive-icon XMLs are staged in `mipmap-anydpi/` (no `-v26` qualifier: with minSdk 29 lint, run with `warningsAsErrors`, would report `ObsoleteSdkInt`). No legacy raster mipmaps: every supported device (API 29+) draws the adaptive icon, which is what spec F-25's "legacy icons" would otherwise cover (D-50) |
| Splash `splash_mark.xml`, `splash_mark_animated.xml` (+ night) | `res/drawable/`, `res/drawable-night/` | `:core:designsystem` |
| Notification icon `ic_notification.xml` | `res/drawable/` | `:core:designsystem`; any `NotificationCompat.setColor` accent is ink, never the launcher kraft (white on kraft is about 1.7:1) |
| Frozen-name aliases `ic_stat_phono` (→ `ic_notification`), `phono_window_background` (→ `phono_page`) | `res/values/brand_aliases.xml` | `:core:designsystem` (work-plan 4.1 names these for `:app`'s manifest and themes) |
| Window and splash colours | `res/values/colors.xml`, `res/values-night/colors.xml` (`phono_page`, `phono_ink`, `phono_launcher_tile`) | `:core:designsystem` |
| Themes `Theme.Phono`, `Theme.Phono.Starting`, `Theme.Phono.ShareSheet` | written by WP-03 from 12.4 | `:core:designsystem` `res/values/themes.xml` (+ `values-v31/`), referenced by `:app`'s manifest and by `ShareActivity` (work-plan 4.1 freezes the three names) |

Checks the converted resources must pass in `:core:designsystem`:
- **Lint vector checks**, verified against the lint that AGP 9.4.1 runs (lint-checks 32.4.1, read from
  `VectorPathDetector` and `VectorDetector`, then confirmed by running `lintDebug` with `warningsAsErrors` on the
  staged `res/` in a minSdk 29 library: before the fixes 3 `VectorPath`, 22 `VectorRaster` and 1 `ObsoleteSdkInt`
  errors; after them "No issues found", also with lint's whole default check set):
  - `VectorPath`: a `pathData` of **3,000 or more** characters, counted with every run of whitespace as one character,
    in a file whose root is `<vector>` (minSdk ≥ 21). `svg2vd.py` keeps every path under it: merges stop short of the
    limit, and a longer path is split at subpath boundaries when that cannot change a pixel (otherwise it fails and
    the master must be split). The launcher background's halftone is four paths; `ill_no_computer`'s densest dot row
    two. Longest path in the export: 2,976.
  - `VectorRaster`: a `<vector>` whose `android:width` or `android:height` is **over 200 dp**. The ten 380 × 214
    illustrations declare 190 × 107 dp and the splash icon 144 × 144 dp, with unchanged viewports (7.1, 7.2); the
    intrinsic size is only the default bounds.
  - `InvalidVectorPath` (scientific notation) is not checked when minSdk ≥ 23; `svg2vd.py` never writes it anyway.
  `tools/lint_vectors.py` applies both counts to the staging tree (exit 1 on any hit) and must stay green; no
  `tools:ignore` is needed.
- **Licence entries**: `THIRD-PARTY-NOTICES.md` 7.3 "Fonts and icons" (WP-03) lists the five font files from
  `assets/licenses/FONTS.txt` (name, version, source, SHA-256, OFL-1.1; Charis with its Reserved Font Names) and the
  Lucide icons (v1.51.0, ISC, Feather-derived glyphs MIT, the 122 names in `icons.json`, also present as SVG sources
  under `design-src/svg/icons/` and in `design-src/mockups/kit/icons.js`). The illustrations, the launcher icon, the
  splash and the notification icon are original to this project (no third-party art, no logos, no generated images)
  and need no entry. WP-03 also writes `assets/licenses/bundled.json` from `FONTS.txt` and `icons.json` (one entry
  per font family and one for the Lucide set), which the build's licence check and the "Open-source licences" list
  read (architecture 20.6, D-50).

### 12.4 Splash and window

`Theme.Phono.Starting` (core-splashscreen 1.2.0): `windowSplashScreenBackground = @color/phono_page`,
`windowSplashScreenAnimatedIcon = @drawable/splash_mark_animated` (API 31+; the compat library shows the static
`splash_mark` below 31), `windowSplashScreenAnimationDuration = 700`, no icon background, `postSplashScreenTheme =
@style/Theme.Phono`. `Theme.Phono` window background = `phono_window_background` (= `phono_page`), light/dark from
`values-night`. `Theme.Phono.ShareSheet` (the share confirmation, 9.22): translucent window, no title, transparent
window background, scrim and sheet drawn by Compose, `windowIsFloating` off, navigation bar handled by the sheet's
insets (architecture 12). The splash follows the system night mode; on API 31+ the app calls
`UiModeManager.setApplicationNightMode` when the in-app appearance is Light or Dark so the next cold start matches (no
white flash in either theme). That call lives in the app shell (work-plan WP-02, `:app`), through a helper WP-03
provides in `:core:designsystem`; WP-03 itself never edits `:app`.

### 12.5 Rules carried from Artemi (architecture 17)

Tokens only in screens (no hard-coded colours, fonts or text sizes; a hygiene grep fails on `Color(0x` and `.sp`
literals outside `:core:designsystem`), WCAG contrast on every surface a colour can sit on (section 3), 48 dp
targets, decorative art excluded from semantics, `@PreviewLightDark` previews of every component state in Standard
and CVD palettes.

---------------------------------------------------------------------------------------------------------------

## 13. Design tools (`android/design-src/tools/`, Python 3 standard library only)

| Tool | What it does | Verified |
|---|---|---|
| `check_palette.py [palette.json] [--report] [--verbose]` | Runs gates G1-G11 (section 3); exit 1 on any failure, 2 on a malformed file | 499 checks pass; a deliberately broken palette fails with 10 named failures |
| `svg2vd.py in.svg [-o out.xml] [--size 24] [--precision 3] [--palette palette.json --theme dark] [--strict-palette] [--tint ?attr/x] [--color name=#hex] [--lenient] [--no-merge] [--max-path 3000]` | SVG subset → VectorDrawable: path, circle, ellipse, rect (rx/ry), line, polyline, polygon, g, transforms (flattened exactly; elliptical radial gradients via nested groups), fill/stroke/opacity, linear and radial gradients (objectBoundingBox and userSpaceOnUse, gradientTransform, spreadMethod, href), clipPath, `var(--token)` and theme swaps; merges paths that share a paint, never past lint's `VectorPath` limit, and splits a longer path at subpath boundaries when no pixel can change; warns when the intrinsic size is over 200 dp; refuses `stroke-dasharray`, `<pattern>`, text, images and filters with a clear message | Fixtures + brand masters + all 122 icons + the 2 CR-01 icons |
| `vd2svg.py drawable.xml [-o out.svg] [--scale 4] [--background #hex] [--attr name=#hex] [--trim-end 1]` | VectorDrawable (or the drawable inside an AnimatedVectorDrawable) → SVG for review: group transforms in Android's order, clip-paths, trim paths, aapt gradients, tint | Round trip |
| `roundtrip.py [svgs…] --out DIR [--palette palette.json]` | SVG → VD → SVG, renders both with headless Chrome, compares with ImageMagick (normalized RMSE), writes side-by-side PNGs; exit 1 above the threshold (0.02), except the masters listed in `RENDERER_TOLERANCE` with their evidence | Every fixture, brand master, illustration (light and dark), all 122 icons and the 2 CR-01 icons pass. `ic_site_x` (Lucide "at-sign", a 307° arc) is the one listed: against its source it measures 0.0129 / 0.0204 / 0.0117 at 2× / 4× / 8×, against an independently written exact-cubic version of the same arc 0.0013 / 0.0009 / 0.0005, and the source against that cubic version 0.0128 / 0.0203 / 0.0116. The residue is Chrome drawing a native arc differently from cubics (Android draws only cubics), not the converter, and it does not grow with scale; its tolerance is 0.025 for that file only |
| `lint_vectors.py [res…]` | Applies lint's `VectorPath` (≥ 3,000, whitespace runs count once) and `VectorRaster` (> 200 dp) rules to every staged vector, also inside animated vectors; exit 1 on any hit (12.3) | 158 vectors, 1,037 paths, longest 2,976; agrees with `lintDebug` (AGP 9.4.1) on the old and the new export; `cr01/export/res`: 2 vectors, 2 paths, longest 144 |
| `export_vectors.py [--out DIR] [--only icons\|illustrations\|brand]` | Regenerates every staged vector from its master: icons (precision 3), illustrations light and night (precision 2, strict palette, intrinsic size halved above 200 dp), then `brand_export.py` | Its output is the staged tree byte for byte |
| `lucide_icons.py --lucide DIR [--inventory FILE] [--svg-dir DIR] [--js FILE] [--extra NAME…]` | Extracts the `icons.json` inventory (or another, e.g. `cr01/icons.json`) from a Lucide install (lucide-static SVGs or lucide-react ESM), read-only; writes SVG sources and the mockups' icon table | 122 icons; the 2 of `cr01/icons.json`; it regenerates `kit/icons.js` and `svg/icons/` byte for byte |
| `brand_export.py [--out DIR]` | Writes the brand resources into the staging tree: launcher layers, the two adaptive-icon XMLs (`mipmap-anydpi/`), notification icon, static and animated splash (light and night; 144 dp intrinsic), `colors.xml` (light and night) from `palette.json` | Its outputs match `svg2vd.py` run on each brand master |
| `../mockups/render.sh [page…]` | Renders the mockups to `docs/design/` at 2× with Chrome and quantizes them; fonts are the staged TTFs (offline, the exact bytes the app bundles) | 35 renders × 2 themes (28 v1, 7 CR-01) |

Workflow for an illustration: draw in the light illustration tokens (no dashes, no patterns: expand hatching into
clipped line paths and dots into zero-length round-capped strokes or circles) → `export_vectors.py --only
illustrations` (it runs `svg2vd.py ill_x.svg --strict-palette --precision 2 [--size 190x107]` for `drawable/` and the
same with `--palette palette.json --theme dark` for `drawable-night/`) → `lint_vectors.py` → `roundtrip.py` on the
source → look at the comparison PNGs. `export_vectors.py` regenerates the whole staged vector tree byte for byte
from the masters (icons and brand layers at the default precision, 3; illustrations at 2).

---------------------------------------------------------------------------------------------------------------

## 14. Mockup index (`docs/design/`, 412 × 915 dp at 2×; tall renders are full scrolls)

| File (`-light.png`, `-dark.png`) | Shows |
|---|---|
| `01-home` | Home, populated: masthead, status card with analyzing / needs attention / bringing, Today's set hero with stations and a full-width "Start" (no list control, D-45) |
| `02-home-full` | Home full scroll: all ten blocks in spec order, spot illustrations, Watch by change with line-style names |
| `03-home-first-run` | Home first run: not connected, the teaching card with `ill_welcome_clips`, Today's set placeholder |
| `04-watch-video` | A short with landscape video: header with beads and prev/next, slip with the Now pill and the tether, marked captions, intonation badge, hint line, rail with Speed 0.75×, stylus-trace progress with the filled pin |
| `05-watch-audio-offline` | Audio-only short, computer not reachable ("On this phone"), colour-vision palette with patterns: the drum, labels on the strip, pattern icons after marks, low-confidence outline, ‿ arc, Loop: Phrase and Saved |
| `06-watch-portrait` | Portrait video: opaque caption slip, rail capsule, contraction spotlight, the "hold to slow down" coach mark |
| `07-watch-question` | A set question answered wrong: rings, icons and words, explanation well |
| `08-watch-set-end` | "Done for today" summary card |
| `09-mode-picker` | The feed mode picker sheet |
| `10-lesson-compare` | Lesson sheet, step 2: paused-context pill, forms, legend above sorts, selected ≠ sort, sound card with the "≠ changed" pill and the mouth still |
| `11-lesson-boundary` | Full-height lesson for “did” + “you”: per-word headers, dropped sort, gap box, Why it changes, shadowing running (8-phase bar) |
| `12-learn`, `13-phenomenon` | Learn tab (cards with the short count); the Flapping page with the mouth demo, the occurrence line "12 times in your clips" and examples |
| `14-practice` | Practice tab: kinds, focus chip, weak-spots switch, accuracy |
| `15-library`, `16-clip-page` | All clips with In progress; the Clip page Shorts tab |
| `17-requests`, `18-request-detail` | Requests with the alert card, the route line, the named six-stage stepper and its teaching line; the request detail timeline and stages |
| `19-share-connected`, `20-share-queued` | The share confirmation bridge in both states |
| `21-connect-none` | "No computer answered." with `ill_no_computer` and the numbered checklist (first item with `phonotrainer phone on`, D-42) |
| `22-settings-display` | The Settings page as it opens (`SettingsKey`): top bar "Settings", lede, the Display group with palette previews; Display is a group of this page, not a sub-page (10.2 P-12) |
| `22-settings-full` | The whole Settings page: Display, Playback and captions, Learning as groups, then the rows that open Your computer, Storage, Notifications, Help, About and licences |
| `23-welcome` | Welcome page 1 |
| `24-saved-empty` | Saved, empty state |
| `25-illustrations` | The illustration set, light and dark from one vector |
| `26-brand` | Launcher icon (masks, sizes, themed), layers with the safe zone, share sheets light and dark, notifications, splash frames |
| `27-watch-large-text` | Watch at font scale 2.0: compact slip, full-width captions, the two-row action bar |
| `*-grayscale.png` | Grayscale proofs (A-01) |
| `28-clip-computer` (v1-complete) | Clip page, Summary tab to its end: "On this phone" (sizes, keep switch, secondary removals) and "On your computer" (the sentence and the danger "Delete from your computer…"), connected |
| `29-clip-delete-waiting` (v1-complete) | The same page after the deletion was confirmed while the computer is not reachable (on Wi-Fi, so the reason is "Your computer isn't reachable", spec D-61): the dashed header pill, the block waiting with its reason, the dotted mini route and "Undo" |
| `30-delete-dialog` (v1-complete) | "Delete “{title}” from your computer?" over the Clip page, offline with saved items: the four-place reach strip, both consequence lines, stacked danger confirm, "Cancel" focused |
| `31-removal-dialogs` (v1-complete) | Specimen: "Remove from this phone?" (two places, dotted wire, primary), the imported variant, the two practice resets (shared: critical place mark and danger; phone-only: smartphone mark and primary), "Forget this computer?" with its two new lines (title and confirm label as spec D-60 confirms them), and specimen 5, "Delete from your computer?" with a crafted title as the quoted title of 9.26 shows it (the reference of its screenshot test) |
| `32-requests-deletions` (v1-complete) | Requests with deletion rows: refused (alert card, Try again, Keep the clip), waiting on another library (Undo) next to an ordinary waiting file, sending |
| `33-practice-shared` (v1-complete) | Practice, full scroll, with "Shared through your computer · last synced 5 min ago" under the heading and "Reset" |
| `34-deletion-states` (v1-complete) | Specimen: header pills (dashed / solid), Library rows and a shelf card with the deletion line, every other state of the "On your computer" block, the three one-shot messages, the three shared-progress lines and the Saved lede |

Copy shortened in the mockups (meaning kept, as the spec allows): the slip cue is one line; the sound card buttons
read "Play it", "See the mouth", "IPA chart" (for "Play this sound", "See it in the mouth", "Open in the IPA chart");
the word buttons read "‹ Word" / "Word ›" with full accessible names; the hint line reads "Tap any word for its
lesson." Names of clips and computers in the mockups are invented examples. The refusal sentence in renders `32` and
`34` ("Your computer answered that it can't delete this clip right now.") is spec D-62's sentence for any refusal code
other than `not_finished` and a computer that no longer offers the deletion; 9.26 lists all three (`different_computer`
is not a refusal: the deletion waits). Renders `28`-`34` draw the non-imported words; an imported clip's words (D-58,
9.18, 9.19, 9.26) and the waiting state without "Undo" (D-65, 9.19, 9.26) are not drawn, because they reuse drawn
anatomy unchanged: other words in the same slots, and the waiting block or row of renders `29` and `32` without its
button, plus one more line in the style of the reason line drawn there (`ic_info` instead of the clock).
