# Conversion of the design export (WP-03)

`convert.py` turns the design track's staging export (`../export/`, 173 files) into `:core:designsystem`, the only
place the build reads them from (architecture 17, design.md 12.3). It needs Python 3 and its standard library only.

```bash
python3 android/design-src/convert/convert.py           # gate the inputs, copy the export, generate the sources
python3 android/design-src/convert/convert.py --check   # change nothing; exit 1 on any drift
python3 android/design-src/convert/convert.py --notices # the rows of THIRD-PARTY-NOTICES.md 7.3
```

What it does:

1. Runs the design tools as gates: `tools/check_palette.py` (G1 to G11) and `tools/lint_vectors.py` (lint's
   `VectorPath` and `VectorRaster` rules).
2. Copies every staged file byte for byte to `android/core/designsystem/src/main/` under the same path (fonts,
   licence texts, icons, illustrations and their `drawable-night` twins, launcher layers, `mipmap-anydpi/`, the
   notification icon, the splash drawables, `colors.xml` light and night, the frozen-name `brand_aliases.xml`), and
   removes a converted file the export no longer has.
3. Generates `theme/Palette.kt` (every colour of `palette.json`, with an index the drift test compares),
   `icons/PhonoIcons.kt` (one property per `icons.json` entry) and `illustration/PhonoIllustrations.kt`.
4. Generates `assets/licenses/bundled.json` from `FONTS.txt` and `icons.json` (architecture 20.6).

The module's JVM tests repeat the checks on every build: the 173-file inventory, the palette gates on the theme's
values, the palette drift, `bundled.json` against the fonts and the notices, and the vector limits. Never edit the
converted files by hand: change the design sources (the design track's) and run the conversion again.
