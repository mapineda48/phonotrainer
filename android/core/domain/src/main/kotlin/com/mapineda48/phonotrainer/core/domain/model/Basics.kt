package com.mapineda48.phonotrainer.core.domain.model

// Closed vocabularies of architecture 3.4, transcribed as is.

/** The family a change belongs to (spec 4); OTHER has no hue and no mark. */
enum class Family { REDUCTION, TD, ASSIMILATION, BOUNDARY, LEXICAL, OTHER }

/** The practice badge: "Safe to produce" / "Recognize only". */
enum class Advice { PRODUCE, UNDERSTAND }

/** Where a link points (architecture 8.2). */
enum class Site { YOUTUBE, YOUTUBE_SHORTS, X, OTHER }

/** Which audio track plays: the original mix or the separated dialogue. */
enum class Track { MIX, DIALOGUE }
