package com.mapineda48.phonotrainer.feature.practice

/**
 * The window of the Practice and Saved screen tests: the design's 412 dp width (render `14`, `24`), and a height that
 * holds a whole page at font scale 2.0, so the shared accessibility audit (which measures what is on screen) sees
 * every control at its real size instead of one clipped by the scroll. The layout depends on the width only.
 */
internal const val TALL_WINDOW = "w412dp-h5000dp-xxhdpi"
