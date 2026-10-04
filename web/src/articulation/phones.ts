/** What each English phone asks the articulators to do.
 *
 *  One entry per symbol of the closed inventory the backend can emit
 *  (`ipa_maps.ENGLISH_INVENTORY`), because a phone with no entry is a phone the
 *  engine cannot draw. The numbers are not free: `body` places the tongue hump
 *  on the front↔back axis of the vowel chart, `height` says how close it gets
 *  to the roof (1 = contact), and `tip`/`tipFront` place the tip on the same
 *  three targets a phonetics textbook uses — postalveolar, alveolar, between
 *  the teeth.
 *
 *  The table lives in the interface and not in the backend on purpose: it is
 *  not linguistic analysis of the recording, it is how to *draw* a phone. The
 *  backend publishes the taxonomy of phenomena; this describes a mouth.
 */

import {
  makePose,
  vowelJaw,
  vowelLipOpen,
  type PartialPose,
  type Pose,
} from "./pose";

export type Manner =
  | "vowel"
  | "diphthong"
  | "stop"
  /** A stop whose closure is never released (a narrow recognizer writes t̚):
   *  kept apart from "stop" because the view marks a burst when a stop opens,
   *  and the whole point of this phone is that no burst is heard. */
  | "unreleased"
  | "nasal"
  | "fricative"
  | "affricate"
  | "approximant"
  | "lateral"
  | "tap"
  | "glottal";

export interface Gesture {
  /** Where inside the phone's span this target is reached, 0..1. */
  at: number;
  pose: Pose;
}

export interface PhoneArticulation {
  symbol: string;
  /** The phonetic name: "voiceless alveolar stop". */
  name: string;
  /** An English word where the learner can hear it. */
  example: string;
  /** What the mouth does, in one sentence. This is the didactic payload. */
  cue: string;
  manner: Manner;
  voiced: boolean;
  gestures: Gesture[];
  /** /h/ and /ʔ/ impose nothing on the mouth: they keep the shape of whatever
   *  surrounds them (the /h/ of "he" is already an [i]). Only these overrides
   *  are applied on top of that inherited shape. */
  carry?: PartialPose;
}

/** Vowel pose. Jaw and lip aperture follow from the height unless stated: an
 *  open vowel is open *because* the jaw drops, and tying them keeps the table
 *  from drifting into impossible mouths. */
function vowelPose(body: number, height: number, options: PartialPose = {}): Pose {
  const jaw = options.jaw ?? vowelJaw(height);
  const round = options.lipRound ?? 0;
  return makePose({
    root: 0.3,
    tip: 0.1,
    velum: 0,
    jaw,
    lipRound: round,
    lipOpen: vowelLipOpen(jaw, round),
    ...options,
    body,
    height,
    voice: 1,
  });
}

/** Speech happens with the nasal port shut unless the phone says otherwise:
 *  the velum only relaxes open in the silence between phrases. */
const consonantPose = (partial: PartialPose): Pose => makePose({ velum: 0, ...partial });

/** A held target: the articulators arrive, stay, and only then leave. Without
 *  the plateau a stop would touch and bounce, which is not what a stop is. */
const steady = (pose: Pose): Gesture[] => [
  { at: 0.25, pose },
  { at: 0.75, pose },
];

/** A closure reached and never let go: the target lasts to the very end of the
 *  phone's stretch, so the next phone starts from a sealed tract. */
const held = (pose: Pose): Gesture[] => [
  { at: 0.25, pose },
  { at: 1, pose },
];

/** A single instant: the tap, which is over before it can hold anything. */
const quick = (pose: Pose): Gesture[] => [{ at: 0.5, pose }];

/** Two targets in a row: that movement IS the diphthong. */
const glide = (from: Pose, to: Pose): Gesture[] => [
  { at: 0.18, pose: from },
  { at: 0.85, pose: to },
];

const triple = (a: Pose, b: Pose, c: Pose): Gesture[] => [
  { at: 0.12, pose: a },
  { at: 0.5, pose: b },
  { at: 0.9, pose: c },
];

/** A stop that opens into a fricative instead of straight into the next vowel. */
const affricate = (closure: Pose, release: Pose): Gesture[] => [
  { at: 0.1, pose: closure },
  { at: 0.45, pose: closure },
  { at: 0.75, pose: release },
  { at: 0.95, pose: release },
];

// --- vowels -----------------------------------------------------------------
// `body`: 1 = hump under the hard palate, 0 = hump in the upper pharynx.
// `height`: how close that hump gets to the roof.

const I = vowelPose(0.96, 0.9, { root: 0.15 });
const SMALL_I = vowelPose(0.88, 0.74, { root: 0.2 });
const E_OPEN = vowelPose(0.8, 0.55, { root: 0.25 });
const ASH = vowelPose(0.71, 0.3, { root: 0.32 });
const A_BACK = vowelPose(0.04, 0.14, { root: 0.75, lipRound: 0.05 });
const O_BACK = vowelPose(0.06, 0.2, { root: 0.7, lipRound: 0.3 });
const WEDGE = vowelPose(0.39, 0.4, { root: 0.45 });
const OPEN_O = vowelPose(0.14, 0.34, { root: 0.6, lipRound: 0.45 });
const SMALL_U = vowelPose(0.45, 0.7, { root: 0.4, lipRound: 0.5 });
const U = vowelPose(0.52, 0.88, { root: 0.35, lipRound: 0.85 });
const SCHWA = vowelPose(0.5, 0.45, { root: 0.3, lipRound: 0.05 });
/** The reduced /ɪ/ of unstressed syllables: as high as /ɪ/, pulled halfway back
 *  towards schwa. */
const BARRED_I = vowelPose(0.68, 0.66, { root: 0.25 });
const TURNED_A = vowelPose(0.46, 0.32, { root: 0.4, lipRound: 0.05 });
/** The American r-coloured vowel, drawn bunched (the majority variant). */
const SCHWAR = vowelPose(0.55, 0.58, {
  root: 0.65,
  lipRound: 0.3,
  tip: 0.42,
  tipFront: 0.15,
});
const NURSE = vowelPose(0.5, 0.5, { root: 0.4, lipRound: 0.1 });
/** Starting point of /aɪ/ and /aʊ/: low and central, not as back as /ɑ/. */
const A_LOW = vowelPose(0.42, 0.12, { root: 0.45 });
/** The /o/ that opens /oʊ/: mid back, already rounded. */
const O_MID = vowelPose(0.28, 0.45, { root: 0.55, lipRound: 0.6 });

// --- consonants -------------------------------------------------------------

const BILABIAL: PartialPose = { jaw: 0.1, lipOpen: 0, lipRound: 0.08, body: 0.5, height: 0.45 };
const LABIODENTAL: PartialPose = { jaw: 0.12, lipOpen: 0.2, lipTuck: 1, body: 0.5, height: 0.45 };
const DENTAL: PartialPose = { jaw: 0.22, lipOpen: 0.42, tip: 1, tipFront: 1, body: 0.6, height: 0.45 };
const ALVEOLAR: PartialPose = { jaw: 0.14, lipOpen: 0.34, tip: 1, tipFront: 0.5, body: 0.6, height: 0.5 };
const SIBILANT: PartialPose = { jaw: 0.1, lipOpen: 0.26, tip: 0.9, tipFront: 0.46, body: 0.65, height: 0.55 };
const POSTALVEOLAR: PartialPose = {
  jaw: 0.16, lipOpen: 0.34, lipRound: 0.45, tip: 0.84, tipFront: 0.12, body: 0.68, height: 0.62,
};
const POSTALVEOLAR_STOP: PartialPose = { ...POSTALVEOLAR, tip: 1, tipFront: 0.16 };
const VELAR: PartialPose = { jaw: 0.18, lipOpen: 0.36, body: 0.45, height: 1, tip: 0.08 };
const TAP: PartialPose = { jaw: 0.18, lipOpen: 0.36, tip: 0.97, tipFront: 0.52, body: 0.5, height: 0.42 };
const LATERAL: PartialPose = {
  jaw: 0.16, lipOpen: 0.36, tip: 1, tipFront: 0.5, body: 0.3, height: 0.42, root: 0.5,
};

const voiceless = (base: PartialPose, extra: PartialPose = {}): Pose =>
  consonantPose({ ...base, ...extra, voice: 0 });
const voiced = (base: PartialPose, extra: PartialPose = {}): Pose =>
  consonantPose({ ...base, ...extra, voice: 1 });

/** Every phone of the closed English inventory, keyed by its bare symbol
 *  (stress and length marks are stripped before lookup). */
const TABLE: PhoneArticulation[] = [
  // --- stops
  {
    symbol: "p", name: "voiceless bilabial stop", example: "pie", manner: "stop", voiced: false,
    cue: "Both lips seal, the pressure builds up behind them, and the release is a puff of air.",
    gestures: steady(voiceless(BILABIAL)),
  },
  {
    symbol: "b", name: "voiced bilabial stop", example: "buy", manner: "stop", voiced: true,
    cue: "The same seal as /p/, but the vocal folds are already vibrating when it opens.",
    gestures: steady(voiced(BILABIAL)),
  },
  {
    symbol: "t", name: "voiceless alveolar stop", example: "tea", manner: "stop", voiced: false,
    cue: "The tip of the tongue seals against the ridge behind the upper teeth.",
    gestures: steady(voiceless(ALVEOLAR)),
  },
  {
    symbol: "d", name: "voiced alveolar stop", example: "do", manner: "stop", voiced: true,
    cue: "The same seal as /t/, with the voice running through it.",
    gestures: steady(voiced(ALVEOLAR)),
  },
  {
    symbol: "k", name: "voiceless velar stop", example: "key", manner: "stop", voiced: false,
    cue: "The back of the tongue rises and seals against the soft palate.",
    gestures: steady(voiceless(VELAR)),
  },
  {
    symbol: "ɡ", name: "voiced velar stop", example: "go", manner: "stop", voiced: true,
    cue: "The same back seal as /k/, voiced.",
    gestures: steady(voiced(VELAR)),
  },
  {
    symbol: "ʔ", name: "glottal stop", example: "the catch in “uh-oh”", manner: "glottal", voiced: false,
    cue: "The vocal folds close and cut the sound off. The mouth does nothing — that is why a glottalised /t/ looks like no /t/ at all.",
    gestures: [], carry: { voice: 0 },
  },
  // --- unreleased stops: the same seal as their released twins, held to the
  // end of the phone and never opened. What tells them apart on screen is the
  // missing burst (manner "unreleased") and, for b̚/d̚/ɡ̚, only the voicing.
  {
    symbol: "p̚", name: "unreleased voiceless bilabial stop", example: "the -p of “stop” said curtly", manner: "unreleased", voiced: false,
    cue: "The lips seal as for /p/ and stay sealed: no puff of air, the next sound starts from behind closed lips.",
    gestures: held(voiceless(BILABIAL)),
  },
  {
    symbol: "b̚", name: "unreleased voiced bilabial stop", example: "the -b of “club” before a pause", manner: "unreleased", voiced: true,
    cue: "The /b/ seal held shut and never opened; the voice fades away inside the closure.",
    gestures: held(voiced(BILABIAL)),
  },
  {
    symbol: "t̚", name: "unreleased voiceless alveolar stop", example: "the -t of “that” in “that one”", manner: "unreleased", voiced: false,
    cue: "The tip seals on the ridge and simply stays there: no burst. American English does this to most final /t/s — the /t/ is in the mouth, not in the ear.",
    gestures: held(voiceless(ALVEOLAR)),
  },
  {
    symbol: "d̚", name: "unreleased voiced alveolar stop", example: "the -d of “good” in “good times”", manner: "unreleased", voiced: true,
    cue: "The /d/ seal held with no release; the voicing dies away inside the closure.",
    gestures: held(voiced(ALVEOLAR)),
  },
  {
    symbol: "k̚", name: "unreleased voiceless velar stop", example: "the -k of “look” in “look back”", manner: "unreleased", voiced: false,
    cue: "The back of the tongue seals against the velum and stays there; the release never comes.",
    gestures: held(voiceless(VELAR)),
  },
  {
    symbol: "ɡ̚", name: "unreleased voiced velar stop", example: "the -g of “big” in “big deal”", manner: "unreleased", voiced: true,
    cue: "The /ɡ/ seal at the velum, voiced as it closes and never opened.",
    gestures: held(voiced(VELAR)),
  },
  // --- nasals
  {
    symbol: "m", name: "bilabial nasal", example: "my", manner: "nasal", voiced: true,
    cue: "Lips closed and the velum drops: the whole airstream leaves through the nose.",
    gestures: steady(voiced(BILABIAL, { velum: 1 })),
  },
  {
    symbol: "n", name: "alveolar nasal", example: "no", manner: "nasal", voiced: true,
    cue: "Tongue tip on the ridge and velum down: the mouth is blocked, the nose is not.",
    gestures: steady(voiced(ALVEOLAR, { velum: 1 })),
  },
  {
    symbol: "ŋ", name: "velar nasal", example: "sing", manner: "nasal", voiced: true,
    cue: "The back of the tongue blocks the mouth against the lowered velum. No /ɡ/ follows it in English.",
    gestures: steady(voiced(VELAR, { velum: 1 })),
  },
  {
    symbol: "n̩", name: "syllabic alveolar nasal", example: "the -on of “button”", manner: "nasal", voiced: true,
    cue: "An /n/ that carries the syllable by itself: the tongue never leaves the ridge, and there is no vowel before it.",
    gestures: steady(voiced(ALVEOLAR, { velum: 1, jaw: 0.22 })),
  },
  {
    symbol: "m̩", name: "syllabic bilabial nasal", example: "the -m of “rhythm”", manner: "nasal", voiced: true,
    cue: "An /m/ that is the whole syllable: the lips stay closed and the voice hums through the nose with no vowel before it.",
    gestures: steady(voiced(BILABIAL, { velum: 1, jaw: 0.14 })),
  },
  {
    symbol: "ŋ̍", name: "syllabic velar nasal", example: "“bacon” said fast", manner: "nasal", voiced: true,
    cue: "The back of the tongue stays on the velum after the /k/ and hums the last syllable through the nose: the /n/ of “bacon” has taken the /k/'s place.",
    gestures: steady(voiced(VELAR, { velum: 1, jaw: 0.22 })),
  },
  // --- fricatives
  {
    symbol: "f", name: "voiceless labiodental fricative", example: "fee", manner: "fricative", voiced: false,
    cue: "The lower lip touches the upper teeth and the air hisses between them. The lips never meet each other.",
    gestures: steady(voiceless(LABIODENTAL)),
  },
  {
    symbol: "v", name: "voiced labiodental fricative", example: "view", manner: "fricative", voiced: true,
    cue: "Like /f/, with voice. Watch the lip against the teeth: a /b/ closes both lips instead.",
    gestures: steady(voiced(LABIODENTAL)),
  },
  {
    symbol: "θ", name: "voiceless dental fricative", example: "think", manner: "fricative", voiced: false,
    cue: "The tip of the tongue comes out between the teeth and the air passes over it.",
    gestures: steady(voiceless(DENTAL)),
  },
  {
    symbol: "ð", name: "voiced dental fricative", example: "this", manner: "fricative", voiced: true,
    cue: "The same tongue between the teeth as /θ/, with the voice on.",
    gestures: steady(voiced(DENTAL)),
  },
  {
    symbol: "s", name: "voiceless alveolar fricative", example: "see", manner: "fricative", voiced: false,
    cue: "A narrow groove between the tip of the tongue and the ridge; the air whistles through it.",
    gestures: steady(voiceless(SIBILANT)),
  },
  {
    symbol: "z", name: "voiced alveolar fricative", example: "zoo", manner: "fricative", voiced: true,
    cue: "The same groove as /s/, with the vocal folds vibrating.",
    gestures: steady(voiced(SIBILANT)),
  },
  {
    symbol: "ʃ", name: "voiceless postalveolar fricative", example: "ship", manner: "fricative", voiced: false,
    cue: "The tongue pulls back a little from /s/ and the lips round: a deeper, darker hiss.",
    gestures: steady(voiceless(POSTALVEOLAR)),
  },
  {
    symbol: "ʒ", name: "voiced postalveolar fricative", example: "vision", manner: "fricative", voiced: true,
    cue: "The voiced twin of /ʃ/. It is what /z/ turns into before a /j/ (“as you” → aʒu).",
    gestures: steady(voiced(POSTALVEOLAR)),
  },
  {
    symbol: "h", name: "voiceless glottal fricative", example: "hat", manner: "glottal", voiced: false,
    cue: "Nothing blocks the mouth: the noise is made at the vocal folds while the tongue is already shaped for the vowel that follows.",
    gestures: [], carry: { voice: 0 },
  },
  // --- affricates
  {
    symbol: "tʃ", name: "voiceless postalveolar affricate", example: "church", manner: "affricate", voiced: false,
    cue: "A stop that does not open straight up: it releases into the hiss of /ʃ/.",
    gestures: affricate(voiceless(POSTALVEOLAR_STOP), voiceless(POSTALVEOLAR)),
  },
  {
    symbol: "dʒ", name: "voiced postalveolar affricate", example: "judge", manner: "affricate", voiced: true,
    cue: "The voiced version: closure and then /ʒ/. It is what /d/ becomes before a /j/ (“did you” → dɪdʒu).",
    gestures: affricate(voiced(POSTALVEOLAR_STOP), voiced(POSTALVEOLAR)),
  },
  // --- tap, approximants and lateral
  {
    symbol: "ɾ", name: "alveolar tap", example: "the -tt- of “butter”", manner: "tap", voiced: true,
    cue: "The tip bounces off the ridge once, too briefly to build up any pressure. This is what American /t/ and /d/ become between vowels.",
    gestures: quick(voiced(TAP)),
  },
  {
    symbol: "ɾ̃", name: "alveolar nasal tap", example: "the -nt- of “winter”", manner: "tap", voiced: true,
    cue: "The same single tap as /ɾ/, but the velum stays down from the /n/ before it, so the tap sounds through the nose. It is why “winter” can sound like “winner”.",
    gestures: quick(voiced(TAP, { velum: 1 })),
  },
  {
    symbol: "l", name: "alveolar lateral approximant", example: "let", manner: "lateral", voiced: true,
    cue: "The tip touches the ridge but the sides of the tongue stay clear: the air leaves around them. At the end of a syllable the back rises too — that is the “dark” l.",
    gestures: steady(voiced(LATERAL)),
  },
  {
    symbol: "l̩", name: "syllabic alveolar lateral", example: "the -le of “bottle”", manner: "lateral", voiced: true,
    cue: "A dark /l/ that is the whole syllable: no vowel comes before it, the tongue goes straight to the ridge.",
    gestures: steady(voiced(LATERAL, { jaw: 0.22 })),
  },
  {
    symbol: "ɹ", name: "alveolar approximant", example: "red", manner: "approximant", voiced: true,
    cue: "Nothing touches anything: the body of the tongue bunches up towards the palate, the root pulls back and the lips round a little. Some speakers curl the tip up instead.",
    gestures: steady(voiced({
      jaw: 0.2, lipOpen: 0.34, lipRound: 0.4, tip: 0.45, tipFront: 0.1,
      body: 0.55, height: 0.6, root: 0.62,
    })),
  },
  {
    symbol: "j", name: "palatal approximant", example: "yes", manner: "approximant", voiced: true,
    cue: "The tongue starts as high and as front as an /i/ and immediately slides off it.",
    gestures: steady(voiced({ jaw: 0.12, lipOpen: 0.26, body: 0.95, height: 0.88 })),
  },
  {
    symbol: "w", name: "labial-velar approximant", example: "we", manner: "approximant", voiced: true,
    cue: "Two narrowings at once: the lips round hard and the back of the tongue rises towards the velum.",
    gestures: steady(voiced({ jaw: 0.14, lipOpen: 0.14, lipRound: 0.9, body: 0.5, height: 0.86, root: 0.35 })),
  },

  // --- vowels
  {
    symbol: "i", name: "close front unrounded vowel", example: "see", manner: "vowel", voiced: true,
    cue: "The tongue is as high and as far forward as it goes without hissing; the lips are spread.",
    gestures: steady(I),
  },
  {
    symbol: "ɪ", name: "near-close near-front unrounded vowel", example: "sit", manner: "vowel", voiced: true,
    cue: "Like /i/ but loose: the tongue drops a little and pulls back. Keeping it tense turns “sit” into “seat”.",
    gestures: steady(SMALL_I),
  },
  {
    symbol: "ɛ", name: "open-mid front unrounded vowel", example: "dress", manner: "vowel", voiced: true,
    cue: "Front of the tongue at mid height, jaw clearly more open than for /ɪ/.",
    gestures: steady(E_OPEN),
  },
  {
    symbol: "æ", name: "near-open front unrounded vowel", example: "cat", manner: "vowel", voiced: true,
    cue: "The front of the tongue stays forward while the jaw drops wide open. It is the widest front vowel English has.",
    gestures: steady(ASH),
  },
  {
    symbol: "ɑ", name: "open back unrounded vowel", example: "father", manner: "vowel", voiced: true,
    cue: "Tongue low and pulled back, so the pharynx narrows behind it; the lips stay unrounded.",
    gestures: steady(A_BACK),
  },
  {
    symbol: "ɒ", name: "open back rounded vowel", example: "British “lot”", manner: "vowel", voiced: true,
    cue: "Low and back with a trace of rounding. American English usually merges it into /ɑ/.",
    gestures: steady(O_BACK),
  },
  {
    symbol: "ʌ", name: "open-mid back unrounded vowel", example: "cup", manner: "vowel", voiced: true,
    cue: "Tongue central and relaxed, jaw half open, lips doing nothing at all.",
    gestures: steady(WEDGE),
  },
  {
    symbol: "ɔ", name: "open-mid back rounded vowel", example: "thought", manner: "vowel", voiced: true,
    cue: "Low at the back with the lips slightly rounded. Many Americans no longer separate it from /ɑ/.",
    gestures: steady(OPEN_O),
  },
  {
    symbol: "ʊ", name: "near-close near-back rounded vowel", example: "book", manner: "vowel", voiced: true,
    cue: "High and back but loose, with only a hint of rounding.",
    gestures: steady(SMALL_U),
  },
  {
    symbol: "u", name: "close back rounded vowel", example: "boot", manner: "vowel", voiced: true,
    cue: "The back of the tongue rises towards the velum while the lips round and push forward.",
    gestures: steady(U),
  },
  {
    symbol: "ə", name: "mid central vowel (schwa)", example: "the a- of “about”", manner: "vowel", voiced: true,
    cue: "The resting mouth, with voice: tongue in the middle, no effort anywhere. Every unstressed vowel in English drifts here.",
    gestures: steady(SCHWA),
  },
  {
    symbol: "ɐ", name: "near-open central vowel", example: "a reduced “but”", manner: "vowel", voiced: true,
    cue: "A schwa with the jaw a little lower. espeak uses it for heavily reduced vowels.",
    gestures: steady(TURNED_A),
  },
  {
    symbol: "ᵻ", name: "near-close central unrounded vowel", example: "the -e- of “roses”", manner: "vowel", voiced: true,
    cue: "The unstressed /ɪ/: as high as /ɪ/ but pulled back towards the centre, halfway to schwa. It is what tells “roses” from “Rosa's”.",
    gestures: steady(BARRED_I),
  },
  {
    symbol: "ɚ", name: "r-coloured mid central vowel", example: "letter", manner: "vowel", voiced: true,
    cue: "Schwa plus /ɹ/ in one movement: the body of the tongue bunches towards the palate and the root pulls back.",
    gestures: steady(SCHWAR),
  },
  {
    symbol: "ɝ", name: "stressed r-coloured vowel", example: "bird", manner: "vowel", voiced: true,
    cue: "The stressed /ɚ/: the same bunched tongue, held longer and louder.",
    gestures: steady(SCHWAR),
  },
  {
    symbol: "ɜ", name: "open-mid central vowel", example: "nurse without the r", manner: "vowel", voiced: true,
    cue: "Central and mid, with no r-colouring. In American English it almost always turns up as /ɝ/.",
    gestures: steady(NURSE),
  },
  // --- diphthongs: the movement is the phone
  {
    symbol: "eɪ", name: "closing diphthong", example: "say", manner: "diphthong", voiced: true,
    cue: "Starts near /ɛ/ and closes upwards to /ɪ/. Stopping at the start is the commonest way to make it sound foreign.",
    gestures: glide(vowelPose(0.82, 0.56, { root: 0.25 }), SMALL_I),
  },
  {
    symbol: "oʊ", name: "closing diphthong", example: "go", manner: "diphthong", voiced: true,
    cue: "Starts mid-back with the lips already rounded and closes towards /ʊ/.",
    gestures: glide(O_MID, SMALL_U),
  },
  {
    symbol: "aɪ", name: "closing diphthong", example: "my", manner: "diphthong", voiced: true,
    cue: "The jaw starts wide open with the tongue low, then everything closes up to /ɪ/.",
    gestures: glide(A_LOW, SMALL_I),
  },
  {
    symbol: "aʊ", name: "closing diphthong", example: "now", manner: "diphthong", voiced: true,
    cue: "Starts low, then the tongue pulls back and the lips round towards /ʊ/.",
    gestures: glide(A_LOW, SMALL_U),
  },
  {
    symbol: "ɔɪ", name: "closing diphthong", example: "boy", manner: "diphthong", voiced: true,
    cue: "Starts back and rounded, then slides forward and up to /ɪ/: the longest trip in the vowel space.",
    gestures: glide(OPEN_O, SMALL_I),
  },
  // --- r-coloured sequences espeak emits as one token
  {
    symbol: "ɑɹ", name: "r-coloured vowel", example: "start", manner: "diphthong", voiced: true,
    cue: "Open back vowel that turns into an /ɹ/: the tongue rises and bunches without ever touching.",
    gestures: glide(A_BACK, SCHWAR),
  },
  {
    symbol: "ɔɹ", name: "r-coloured vowel", example: "north", manner: "diphthong", voiced: true,
    cue: "Rounded back vowel closing into /ɹ/.",
    gestures: glide(OPEN_O, SCHWAR),
  },
  {
    symbol: "oɹ", name: "r-coloured vowel", example: "force", manner: "diphthong", voiced: true,
    cue: "Like “north” but starting higher; most Americans no longer keep the two apart.",
    gestures: glide(O_MID, SCHWAR),
  },
  {
    symbol: "ɪɹ", name: "r-coloured vowel", example: "near", manner: "diphthong", voiced: true,
    cue: "/ɪ/ falling back into the bunched /ɹ/.",
    gestures: glide(SMALL_I, SCHWAR),
  },
  {
    symbol: "ɛɹ", name: "r-coloured vowel", example: "square", manner: "diphthong", voiced: true,
    cue: "/ɛ/ falling back into the bunched /ɹ/.",
    gestures: glide(E_OPEN, SCHWAR),
  },
  {
    symbol: "ʊɹ", name: "r-coloured vowel", example: "cure", manner: "diphthong", voiced: true,
    cue: "/ʊ/ unrounding as it turns into /ɹ/.",
    gestures: glide(SMALL_U, SCHWAR),
  },
  {
    symbol: "aɪɚ", name: "triphthong", example: "fire", manner: "diphthong", voiced: true,
    cue: "Three targets without a pause: open, then /ɪ/, then r-coloured.",
    gestures: triple(A_LOW, SMALL_I, SCHWAR),
  },
  {
    symbol: "aɪə", name: "triphthong", example: "fire (non-rhotic)", manner: "diphthong", voiced: true,
    cue: "Like “fire” but ending in a plain schwa, with no r-colouring.",
    gestures: triple(A_LOW, SMALL_I, SCHWA),
  },
  {
    symbol: "iə", name: "centring diphthong", example: "idea", manner: "diphthong", voiced: true,
    cue: "/i/ relaxing back into schwa.",
    gestures: glide(I, SCHWA),
  },
  {
    symbol: "eə", name: "centring diphthong", example: "square (non-rhotic)", manner: "diphthong", voiced: true,
    cue: "Mid front vowel relaxing back into schwa.",
    gestures: glide(E_OPEN, SCHWA),
  },
  {
    symbol: "əl", name: "syllabic l", example: "the -le of “little”", manner: "lateral", voiced: true,
    cue: "Schwa straight into a dark /l/: the tongue goes up to the ridge while the back stays low.",
    gestures: glide(SCHWA, voiced(LATERAL)),
  },
  {
    symbol: "ju", name: "rising diphthong", example: "few", manner: "diphthong", voiced: true,
    cue: "/j/ then /u/: the tongue starts against the palate and slides back while the lips round.",
    gestures: glide(voiced({ jaw: 0.12, lipOpen: 0.26, body: 0.95, height: 0.88 }), U),
  },
];

const BY_SYMBOL = new Map(TABLE.map((phone) => [phone.symbol, phone]));

/** Stress and length marks are not articulations: /ˈuː/ and /u/ are one mouth. */
export const bareSymbol = (symbol: string): string => symbol.replace(/[ˈˌː]/g, "");

/** The articulation of a phone, or null when the symbol is outside the
 *  inventory (the engine then holds the previous shape rather than inventing
 *  one). */
export function lookupPhone(symbol: string): PhoneArticulation | null {
  return BY_SYMBOL.get(bareSymbol(symbol)) ?? null;
}

/** Every phone the engine can draw, in table order. Used by the lab page and
 *  by the test that checks the inventory is covered. */
export const PHONE_TABLE: readonly PhoneArticulation[] = TABLE;
