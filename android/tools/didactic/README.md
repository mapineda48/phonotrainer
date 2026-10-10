# Didactic content extractor

The glossary ("What's this?"), the Learn content, the IPA chart layout and the articulation
table exist only in the web client's TypeScript. The phone shows the same text, offline and
before pairing, so this tool reads those files **read-only** and writes them as one JSON
asset for the Android build; a drift check fails the build when the asset no longer matches
the web sources (spec S-18 and D-09, architecture 15).

- `extract.mjs`: the extractor. Node only, no npm dependency: it uses Node's built-in
  TypeScript type stripping (Node 22.18 or newer; older 22.x and 23.x are re-run with
  `--experimental-strip-types`).
- `ts-resolve-hook.mjs`: the module hook that resolves the web's extension-less imports to
  `.ts` files and refuses any import outside the declared inputs.
- `schema.mjs`: schema 1 of the asset, checked on every run and by the tests.
- `test/extract.test.mjs`: the tests (Node's own test runner).

## Use

From the repository root (the paths are resolved from the script, so any directory works):

```sh
node android/tools/didactic/extract.mjs            # write the asset
node android/tools/didactic/extract.mjs --check    # exit 1 if the asset is stale
node --test android/tools/didactic/test/extract.test.mjs
```

`./gradlew :core:content:verifyDidacticContent` runs the `--check` form (architecture 15.2).
When it fails, it prints the changed keys (`~ glossary.schwa.body`); run the extractor,
review the asset's diff and commit it with the web change.

Options: `--root <dir>` reads `web/src` from another tree (the tests use a temporary copy of
the inputs), `--out <file>` writes or checks another file. Exit status: 0 written or up to
date, 1 stale asset, 2 refused or failed.

## What it guarantees

- **Nothing under `web/` is written.** An output path that resolves inside `web/` (of this
  repository or of `--root`, symbolic links followed) is refused before anything is read;
  the web files are only imported.
- **Strict reading.** It depends on the shape of the web sources on purpose. A field it does
  not know, a link it cannot map, a demo symbol missing from the articulation table, a new
  import or a missing export fails the run with a message that names the file and the place.
- **Deterministic output.** Sorted keys (JavaScript string order), two-space indent, LF, a
  final newline; numbers as JavaScript writes them. The same inputs give the same bytes.

## Inputs

| Web file | Read |
|---|---|
| `web/src/didactic/glossary/*.ts` (tests excluded) | `GLOSSARY` from `index.ts`, `SYMBOL_PHENOMENON` from `ipa.ts` |
| `web/src/features/learn/content.ts` | `LESSONS` |
| `web/src/features/learn/ipaLayout.ts` | `PLACES`, `CONSONANT_ROWS`, `ALLOPHONE_PLACES`, `ALLOPHONE_ROWS`, `SYLLABICS`, `VOWEL_COLUMNS`, `VOWEL_ROWS`, `REDUCED_VOWELS`, `GLIDING_GROUPS`, `placedSymbols()`, `unplacedSymbols()` |
| `web/src/articulation/phones.ts` | `PHONE_TABLE`, `lookupPhone`, `bareSymbol` (checks only) |
| `web/src/articulation/pose.ts` | `POSE_KEYS` (checks only) |
| `web/src/paths.ts` | imported by the glossary to build its links |

Every one of them is listed with its SHA-256 in the asset's `sources`, so any change to an
input, even a comment, makes the drift check ask for a fresh extraction.

## Output: `android/core/content/src/main/assets/didactic/didactic.json`, schema 1

Every field is always present; an absent value is `null`. `schema.mjs` is the exact
definition; the Kotlin readers of `:core:content` decode it strictly (an unknown or missing
field fails their test).

| Field | Content |
|---|---|
| `schema` | `1` |
| `sources` | `[{path, sha256}]`, the inputs above, sorted by path |
| `glossary` | key → `{title, body, example, learnMore, learnMoreLabel}`. Keys: terms (`weak-form`), symbols (`ipa:ɾ`, and the length-marked aliases such as `ipa:iː`), measures (`metric:deviate`). `learnMore` is a link target (below) or null; `learnMoreLabel` is the web's link text or null |
| `symbolPhenomenon` | IPA symbol → the change it is the trace of (`ɾ` → `flapping`) |
| `lessons` | change label → `{listenFor, demo, spanishTip, note}`; `demo` is `{word, dictionary, said, from, to}` or null, and `to` is null when the sound is dropped |
| `ipaLayout` | `places`, `allophonePlaces`, `vowelColumns`: `[{key, label, detail}]` (`detail` is `""` when the web gives none); `consonantRows`, `allophoneRows`, `vowelRows`: `[{label, detail, cells}]` with `cells` column key → symbols; `syllabics`, `reducedVowels`, `placed`, `unplaced`: symbols; `glidingGroups`: `[{label, detail, symbols}]` |
| `phones` | the articulation table in the web's order: `{symbol, name, example, cue, manner, voiced, gestures, carry}`; `gestures` is `[{at, pose}]` with all eleven articulators (`jaw`, `lipOpen`, `lipRound`, `lipTuck`, `body`, `height`, `tip`, `tipFront`, `root`, `velum`, `voice`); `carry` holds only the articulators `/h/` and `/ʔ/` impose, null for every other phone |

Link targets (the web `href`s the app can follow; any other `href` fails the run):

| Web `href` | Target |
|---|---|
| `/learn` | `{"kind": "learn"}` |
| `/learn/<change>` | `{"kind": "phenomenon", "label": "<change>"}` (a change of `LESSONS`) |
| `/learn/ipa`, `/learn/ipa?symbol=<s>` | `{"kind": "ipa", "symbol": "<s>" or null}` (a symbol of the articulation table) |
| `/practice`, `/practice?focus=<change>` | `{"kind": "practice", "focus": "<change>" or null}` |
| `/insights` | `{"kind": "insights"}` |

Changing the schema: a renamed, removed or retyped field bumps `SCHEMA` in `schema.mjs`, and
the app refuses a schema it does not know. The stepper's stage lines (`web/src/jobs/stages.ts`)
are deliberately not extracted (architecture 25.4 item 7).
