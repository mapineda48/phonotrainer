/** What Learn adds to each phenomenon beyond the backend's one-line description and the
 *  report's practice advice (both come from /api/reference and are never copied here):
 *  how to HEAR it, a short demonstration for the mouth, and — only where the reductions
 *  report says something specific — a tip for Spanish speakers or a usage note.
 *
 *  Every symbol in a demo must exist in the articulation table (a test checks it). */

export interface MouthDemo {
  /** The words being shown ("water", "got you"). */
  word: string;
  /** Dictionary form, one symbol per phone. */
  dictionary: readonly string[];
  /** What a native speaker typically says. */
  said: readonly string[];
  /** The phone that changes, in the dictionary form… */
  from: string;
  /** …and what it becomes (null: it is dropped). */
  to: string | null;
}

export interface LessonContent {
  /** How to recognize it by ear, in plain English with familiar spellings. */
  listenFor: string;
  demo?: MouthDemo;
  /** Only where the report gives one. */
  spanishTip?: string;
  /** A usage limit or caveat from the report. */
  note?: string;
}

export const LESSONS: Readonly<Record<string, LessonContent>> = {
  vowel_reduction: {
    listenFor:
      "Unstressed syllables get short and quiet, and their vowel becomes a neutral “uh” [ə]. " +
      "Listen for the small words — to, of, a, can, for — that almost vanish between the stressed ones.",
    demo: { word: "does", dictionary: ["d", "ʌ", "z"], said: ["d", "ə", "z"], from: "ʌ", to: "ə" },
    spanishTip:
      "Spanish gives every vowel its full value. Saying ba-NA-na with three clear a's instead of " +
      "[bəˈnænə] is the most audible accent marker — and it also makes the reduced word hard to recognize.",
    note: "“can” reduces to [kən], but “can't” keeps its full [æ]: the vowel, not the t, tells them apart.",
  },
  monophthongization: {
    listenFor:
      "A two-part vowel stays on its first part: “my” sounds like “ma”, “time” like “tahm”.",
    demo: { word: "my", dictionary: ["m", "aɪ"], said: ["m", "æ"], from: "aɪ", to: "æ" },
  },
  elision_syllable: {
    listenFor:
      "A whole unstressed syllable is skipped, so the word has one beat less: " +
      "“probably” → “prob-ly”, “camera” → “cam-ra”, “family” → “fam-ly”.",
    demo: {
      word: "camera",
      dictionary: ["k", "æ", "m", "ɚ", "ə"],
      said: ["k", "æ", "m", "ɹ", "ə"],
      from: "ɚ",
      to: "ɹ",
    },
  },
  word_elision: {
    listenFor:
      "The word is so short and quiet that no sound was found for it — usually a small " +
      "function word in fast speech, or a word buried under laughter or music.",
    note: "Read this label with caution: it can also be a gap in the recognition.",
  },
  flapping: {
    listenFor:
      "A t or d between vowels becomes one very quick tap of the tongue: " +
      "“water” sounds like “wadder”, “get it” like “geddit”.",
    demo: { word: "water", dictionary: ["w", "ɔ", "t", "ɚ"], said: ["w", "ɔ", "ɾ", "ɚ"], from: "t", to: "ɾ" },
    spanishTip:
      "The flap is the single r of Spanish “pero”. You already have this sound: use it for the t " +
      "of “water”, “better” or “a lot of”.",
    note: "Not before a stressed syllable: “attack” and “return” keep a full t.",
  },
  t_deletion: {
    listenFor:
      "When a word ends in a group of consonants, the t or d is skipped before the next " +
      "consonant: “next day” → “nex' day”, “old man” → “ol' man”.",
    demo: {
      word: "next day",
      dictionary: ["n", "ɛ", "k", "s", "t", "d", "eɪ"],
      said: ["n", "ɛ", "k", "s", "d", "eɪ"],
      from: "t",
      to: null,
    },
  },
  t_unreleased: {
    listenFor:
      "At the end of a word the tongue closes for t, but no puff of air follows: " +
      "“that one” sounds like “tha' one”, with a tiny silence where the t should be.",
    demo: { word: "that", dictionary: ["ð", "æ", "t"], said: ["ð", "æ", "t̚"], from: "t", to: "t̚" },
  },
  glottalization: {
    listenFor:
      "The t is replaced by a catch in the throat, as in “uh-oh”: " +
      "“button” → “bu'n”, “important” → “impor'nt”.",
    demo: {
      word: "button",
      dictionary: ["b", "ʌ", "t", "ə", "n"],
      said: ["b", "ʌ", "ʔ", "n̩"],
      from: "t",
      to: "ʔ",
    },
  },
  th_stopping: {
    listenFor: "th is said as a plain d or t: “that” → “dat”, “thing” → “ting”.",
    demo: { word: "that", dictionary: ["ð", "æ", "t"], said: ["d", "æ", "t"], from: "ð", to: "d" },
    spanishTip:
      "Many Spanish speakers already mix [ð] with [d]. Here it is a regional native feature to " +
      "recognize — keep practising [θ] and [ð] in your own speech.",
  },
  palatalization: {
    listenFor:
      "A final t, d, s or z melts into a following “you”: “did you” → “didja”, " +
      "“got you” → “gotcha”, “miss you” → “mishu”.",
    demo: {
      word: "got you",
      dictionary: ["ɡ", "ɑ", "t", "j", "u"],
      said: ["ɡ", "ɑ", "tʃ", "ə"],
      from: "t",
      to: "tʃ",
    },
  },
  linking: {
    listenFor:
      "There is no pause between the words: a final consonant moves onto the next vowel " +
      "(“pick it up” → “pi-ki-dup”), an r joins the next word (“far away”), or a w or y glide " +
      "appears between two vowels (“go on” → “go-won”).",
    demo: {
      word: "does it",
      dictionary: ["d", "ʌ", "z", "ɪ", "t"],
      said: ["d", "ʌ", "z", "ɪ", "t"],
      from: "z",
      to: "z",
    },
    note: "American English never adds an r that is not written (“idea-r-is” is British).",
  },
  h_dropping: {
    listenFor:
      "In unstressed he, him, his, her and have the h disappears: " +
      "“tell him” → “tell-im”, “give her” → “giver”.",
    demo: {
      word: "tell him",
      dictionary: ["t", "ɛ", "l", "h", "ɪ", "m"],
      said: ["t", "ɛ", "l", "ɪ", "m"],
      from: "h",
      to: null,
    },
    note: "Never at the start of a sentence: “He is here” keeps its h.",
  },
  contraction_lex: {
    listenFor:
      "Two words fuse into one fixed form: “going to” → “gonna”, “want to” → “wanna”, " +
      "“got to” → “gotta”, “let me” → “lemme”.",
    demo: {
      word: "want to",
      dictionary: ["w", "ɑ", "n", "t", "t", "u"],
      said: ["w", "ɑ", "n", "ə"],
      from: "t",
      to: null,
    },
    note:
      "“gonna” is only for the future before a verb (I'm gonna leave), never for movement " +
      "(not “I'm gonna the store”). “wanna” only when want and to are side by side: " +
      "“Who do you wanna win?” is wrong.",
  },
  place_assimilation: {
    listenFor:
      "A final n, t or d takes the place of the next sound: " +
      "“ten bucks” → “tem bucks”, “in case” → “ing case”, “good boy” → “goob boy”.",
    demo: {
      word: "ten bucks",
      dictionary: ["t", "ɛ", "n", "b", "ʌ", "k", "s"],
      said: ["t", "ɛ", "m", "b", "ʌ", "k", "s"],
      from: "n",
      to: "m",
    },
    note: "It happens by itself in fast speech; forcing it sounds odd. Learn to hear it.",
  },
  nt_reduction: {
    listenFor:
      "The t after n disappears or becomes a nasal tap: " +
      "“twenty” → “twenny”, “winter” → almost “winner”, “internet” → “innernet”.",
    demo: {
      word: "winter",
      dictionary: ["w", "ɪ", "n", "t", "ɚ"],
      said: ["w", "ɪ", "ɾ̃", "ɚ"],
      from: "t",
      to: "ɾ̃",
    },
    note: "Very American but variable: “winter” and “winner” often stay slightly different.",
  },
  function_elision: {
    listenFor:
      "A small function word keeps almost nothing: “of” → [ə] (“cup of tea” → “cuppa tea”), " +
      "“them” → [əm] (“tell 'em”).",
    demo: { word: "cup of", dictionary: ["k", "ʌ", "p", "ʌ", "v"], said: ["k", "ʌ", "p", "ə"], from: "v", to: null },
  },
};

export function lessonFor(name: string): LessonContent | null {
  return LESSONS[name] ?? null;
}
