# PhonoTrainer for Android: brand assets

The brand is "the stylus P" of the Phonautograph design system (`android/docs/design.md`, sections 1 and 7). Every
brand asset is a vector generated from one master geometry; nothing is a raster, nothing comes from an image model,
and no third-party logo is reproduced anywhere (sites are shown with generic glyphs and their names).

## The mark

One continuous stroke rises as the stem of a P, closes the bowl and leaves the stem as a decaying sound wave that
ends in an ink blot: the stylus of Scott de Martinville's phonautograph (1857), the first machine that wrote a voice
down so people could look at it.

Master geometry (`android/design-src/svg/brand/mark-stylus-p.svg`, a 100 × 100 box):
- stroke `M38 52H62C76 52 82 44 82 35C82 25 75 18 62 18H38V78C38 84 41 87 45 85C50 82 51 72 56 72C61 72 61 84 66 84C70 84 71 77 74 76`,
  width 10, round caps and joins;
- blot: circle at (80, 75.4), radius 7.2.

## Inventory

| Asset | Source (`design-src/svg/brand/`) | Resource | Colours |
|---|---|---|---|
| Adaptive icon background | `launcher-background.svg` | `ic_launcher_background` | kraft drum paper `#D2C3A3`, drum rules and a halftone corner in ink, a soft sheen |
| Adaptive icon foreground | `launcher-foreground.svg` | `ic_launcher_foreground` | ink `#1B1A18`, inside the 66 dp safe zone |
| Monochrome (themed icons) | `launcher-monochrome.svg` | `ic_launcher_monochrome` | single colour |
| Adaptive icon | — | `mipmap-anydpi-v26/ic_launcher`, `ic_launcher_round` | background + foreground + monochrome |
| Splash icon | `splash-mark.svg` | `splash_mark` (+ `drawable-night`) | ink on page `#F8F6EF`; bone `#F3F0E8` on soot `#121110` |
| Animated splash | `splash-mark.svg` (named paths `stylus_stroke`, `stylus_blot`) | `splash_mark_animated` (+ night) | the stroke drawn by `trimPathEnd` in 600 ms, the blot appears by 700 ms; once |
| Notification icon | `notification.svg` | `ic_notification` | white silhouette, 24 dp |
| Wordmark | Compose text | — | "Phono*Trainer*" in Newsreader 700, "Trainer" italic 500, next to the mark |

Why a kraft tile: the icon must be recognizable in the system share sheet next to saturated app circles, in light
and dark. The kraft tile is the one light, warm, unsaturated tile in the row; on white sheets the ink P carries the
figure (10:1 on the tile), on dark sheets the tile does (9.85:1 against `#1C1B1F`). Both are checked by
`check_palette.py` (gate G10). Renders: `android/docs/design/26-brand-light.png` and `-dark.png`.

## Regenerating

From `android/design-src/`:

```sh
python3 tools/svg2vd.py svg/brand/launcher-background.svg -o export/res/drawable/ic_launcher_background.xml
python3 tools/svg2vd.py svg/brand/launcher-foreground.svg -o export/res/drawable/ic_launcher_foreground.xml
python3 tools/svg2vd.py svg/brand/launcher-monochrome.svg -o export/res/drawable/ic_launcher_monochrome.xml
python3 tools/svg2vd.py svg/brand/notification.svg       -o export/res/drawable/ic_notification.xml
python3 tools/svg2vd.py svg/brand/splash-mark.svg        -o export/res/drawable/splash_mark.xml
python3 tools/svg2vd.py svg/brand/splash-mark.svg --palette palette.json --theme dark -o export/res/drawable-night/splash_mark.xml
python3 tools/roundtrip.py svg/brand/*.svg --out <a scratch directory>   # renders and compares every round trip
python3 tools/check_palette.py                                           # gates G10 cover the icon and the splash
```

The animated splash, the adaptive-icon XML and the window colours are specified in design.md 7.1 and 12.4.
