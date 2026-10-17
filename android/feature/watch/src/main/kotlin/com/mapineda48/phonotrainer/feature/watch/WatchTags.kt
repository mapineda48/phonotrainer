package com.mapineda48.phonotrainer.feature.watch

/** Test tags of :feature:watch ("watch.<screen>.<element>", work-plan 2.6). Tags are part of the test contract. */
object WatchTags {
  /** The feed's root (the Watch destination); the stub's tag, kept (the shell's smoke tests read it). */
  const val START = "watch.feed.start"

  /** The feed screen: the same node as [START]. */
  const val SCREEN = START

  /**
   * The stub's "Open a word lesson" button. The real feed opens the lesson from a word or the spotlight
   * ([word], [SLIP]); the constant stays until the shell's smoke test stops reading it (change request CR-WP17-1).
   */
  const val OPEN_LESSON = "watch.feed.openLesson"
  const val PAGER = "watch.feed.pager"
  const val LOADING = "watch.feed.loading"

  // Header (design 9.2).
  const val MODE_PICKER = "watch.feed.modePicker"
  const val POSITION = "watch.feed.position"
  const val BEADS = "watch.feed.beads"
  const val PREVIOUS = "watch.feed.previous"
  const val NEXT = "watch.feed.next"
  const val ON_THIS_PHONE = "watch.feed.onThisPhone"
  const val SHORT_SET = "watch.feed.shortSet"

  // The short card (design 9.3 to 9.8).
  const val MEDIA = "watch.card.media"
  const val AUDIO_ONLY = "watch.card.audioOnly"
  const val MEDIA_CHIP = "watch.card.mediaChip"
  const val HOLD_CHIP = "watch.card.holdChip"
  const val GETTING_READY = "watch.card.gettingReady"
  const val SLIP = "watch.card.slip"
  const val SLIP_WORD = "watch.card.slipWord"
  const val SLIP_IPA = "watch.card.slipIpa"
  const val SLIP_CUE = "watch.card.slipCue"
  const val NOW = "watch.card.now"
  const val PLAY_WORD = "watch.card.playWord"
  const val SLOWLY = "watch.card.slowly"
  const val NO_SPOTLIGHT = "watch.card.noSpotlight"
  const val CONTRACTION = "watch.card.contraction"
  const val SAVED_TAG = "watch.card.savedTag"
  const val CAPTIONS = "watch.card.captions"
  const val CAPTION_CURRENT = "watch.card.captionCurrent"
  const val CAPTION_PREVIOUS = "watch.card.captionPrevious"
  const val CAPTION_NEXT = "watch.card.captionNext"
  const val LISTEN_FIRST = "watch.card.listenFirst"
  const val SHOW_WORDS = "watch.card.showWords"
  const val CAPTIONS_OFF = "watch.card.captionsOff"
  const val CAPTIONS_SHOW = "watch.card.captionsShow"
  const val INTONATION = "watch.card.intonation"
  const val COACH = "watch.card.coach"
  const val REPLAY = "watch.card.replay"
  const val SPEED = "watch.card.speed"
  const val LOOP = "watch.card.loop"
  const val SHADOW = "watch.card.shadow"
  const val SAVE = "watch.card.save"
  const val MORE = "watch.card.more"
  const val ACTION_BAR = "watch.card.actionBar"
  const val RAIL = "watch.card.rail"
  const val PLAY_PAUSE = "watch.card.playPause"
  const val PROGRESS = "watch.card.progress"
  const val CLIP_LINE = "watch.card.clipLine"
  const val CLIP_TITLE = "watch.card.clipTitle"
  const val TRACK_LINE = "watch.card.trackLine"
  const val ERROR = "watch.card.error"
  const val VOLUME = "watch.card.volume"

  // Sheets (design 9.12).
  const val PICKER_SHEET = "watch.picker.sheet"
  const val PICKER_MIX = "watch.picker.mix"
  const val PICKER_SAVED = "watch.picker.saved"
  const val MORE_SHEET = "watch.more.sheet"
  const val MORE_TRACK = "watch.more.track"
  const val MORE_CAPTIONS = "watch.more.captions"
  const val MORE_LISTEN_FIRST = "watch.more.listenFirst"
  const val MORE_PHRASE_DETAILS = "watch.more.phraseDetails"
  const val MORE_RECORD = "watch.more.record"
  const val MORE_CLIP_PAGE = "watch.more.clipPage"
  const val MORE_DOWNLOAD_VIDEO = "watch.more.downloadVideo"
  const val MORE_LEAVE_OUT = "watch.more.leaveOut"
  const val MORE_MARKS = "watch.more.marks"
  const val LEGEND_SHEET = "watch.legend.sheet"
  const val SHADOW_SHEET = "watch.shadow.sheet"
  const val RECORD_SHEET = "watch.record.sheet"

  // Questions and end cards (design 9.14, 9.15).
  const val QUESTION = "watch.question.card"
  const val NEXT_QUESTION = "watch.question.next"
  const val END = "watch.end.card"
  const val END_TITLE = "watch.end.title"
  const val END_TALLY = "watch.end.tally"
  const val END_ON_THE_WAY = "watch.end.onTheWay"
  const val END_NO_QUESTIONS = "watch.end.noQuestions"
  const val END_HOME = "watch.end.home"
  const val END_ANOTHER = "watch.end.another"
  const val END_PRACTICE = "watch.end.practice"
  const val END_AGAIN = "watch.end.again"
  const val END_NEXT_CLIP = "watch.end.nextClip"
  const val END_LEARN = "watch.end.learn"
  const val END_REPLAY_MIX = "watch.end.replayMix"
  const val END_ADD_CLIP = "watch.end.addClip"

  // Empty states (design 9.21).
  const val EMPTY = "watch.empty.state"
  const val EMPTY_LEARN = "watch.empty.learn"

  /** The page [index] of the pager (a short card, a question or an end card). */
  fun page(index: Int): String = "watch.feed.page.$index"

  /** The caption word [word] of phrase [segment] on the current card. */
  fun word(segment: Int, word: Int): String = "watch.card.word.$segment.$word"

  /** A change row of the picker. */
  fun pickerChange(label: String): String = "watch.picker.change.$label"

  /** A clip row of the picker. */
  fun pickerClip(jobId: String): String = "watch.picker.clip.$jobId"

  /** A "What you met" chip of the set summary. */
  fun met(label: String): String = "watch.end.met.$label"
}
