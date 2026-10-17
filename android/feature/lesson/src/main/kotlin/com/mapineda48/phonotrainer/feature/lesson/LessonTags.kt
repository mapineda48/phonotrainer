package com.mapineda48.phonotrainer.feature.lesson

/** Test tags of :feature:lesson ("lesson.<screen>.<element>", work-plan 2.6); part of the test contract. */
object LessonTags {
  const val ROOT = "lesson.sheet.root"
  const val CLOSE = "lesson.sheet.close"
  const val LOADING = "lesson.sheet.loading"
  const val UNAVAILABLE = "lesson.sheet.unavailable"
  const val PAUSED = "lesson.sheet.paused"

  const val SAVE = "lesson.header.save"
  const val WORD = "lesson.header.word"
  const val META = "lesson.header.meta"
  const val CHANGES = "lesson.header.changes"
  const val RELIABILITY = "lesson.header.reliability"
  const val PREVIOUS = "lesson.header.previous"
  const val NEXT = "lesson.header.next"
  const val NEXT_CHANGE = "lesson.header.next_change"

  /** A step's header row (the disclosure). */
  fun step(step: LessonStep): String = "lesson.step.${step.name.lowercase()}"

  /** A step's content, present only while it is open. */
  fun stepBody(step: LessonStep): String = "lesson.step.${step.name.lowercase()}.body"

  const val PLAY_WORD = "lesson.listen.play_word"
  const val PLAY_WITH_NEXT = "lesson.listen.play_with_next"
  const val PLAY_PHRASE = "lesson.listen.play_phrase"
  const val CROSSING = "lesson.listen.crossing"
  const val NO_AUDIO = "lesson.listen.no_audio"
  const val SPEED = "lesson.listen.speed"
  const val LOOP = "lesson.listen.loop"
  const val TRACK = "lesson.listen.track"

  const val FORM_DICTIONARY = "lesson.compare.dictionary"
  const val FORM_SAID = "lesson.compare.said"
  const val FORM_ALIGNER = "lesson.compare.aligner"
  const val SPOKEN = "lesson.compare.spoken"
  const val NO_DICTIONARY = "lesson.compare.no_dictionary"
  const val LEGEND = "lesson.compare.legend"
  const val SORTS = "lesson.compare.sorts"
  const val GAP = "lesson.compare.gap"
  const val COMPARE_NOTE = "lesson.compare.note"
  const val SOUND_CARD = "lesson.compare.sound"
  const val SOUND_PLAY = "lesson.compare.sound.play"
  const val SOUND_MOUTH = "lesson.compare.sound.mouth"
  const val SOUND_CHART = "lesson.compare.sound.chart"

  /** One sort of Compare, by its position. */
  fun sort(index: Int): String = "lesson.compare.sort.$index"

  /** The header above the sorts of the word, or of the next word ([next]). */
  fun sortGroup(next: Boolean): String = if (next) "lesson.compare.group.next" else "lesson.compare.group.word"

  const val NOTICE_NOTHING_HEARD = "lesson.why.nothing_heard"
  const val NOTICE_LOW_CONFIDENCE = "lesson.why.low_confidence"
  const val NO_CHANGE = "lesson.why.no_change"
  const val FORM_SCORING = "lesson.why.form_scoring"
  const val FACTS = "lesson.why.facts"

  /** A change's card in step 3, and its actions. */
  fun card(label: String): String = "lesson.why.card.$label"

  fun watchMore(label: String): String = "lesson.why.watch_more.$label"

  fun learnAbout(label: String): String = "lesson.why.learn_about.$label"

  const val MOUTH_STREAM = "lesson.see.stream"
  const val MOUTH_DRAWING = "lesson.see.drawing"
  const val MOUTH_FOLLOW = "lesson.see.follow"

  /** One sound button of step 4. */
  fun mouthSound(index: Int): String = "lesson.see.sound.$index"

  const val PRACTICE_PANEL = "lesson.practice.panel"
  const val RECORD = "lesson.practice.record"

  const val CHANGE_SHEET = "lesson.explain.change"
  const val CHANGE_SHEET_WATCH = "lesson.explain.change.watch_more"
  const val CHANGE_SHEET_LEARN = "lesson.explain.change.learn_more"
}
