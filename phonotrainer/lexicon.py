"""Closed-class (function) words: the one list prosody, phenomena and metrics share.

Function words are the half of connected speech that English leaves unstressed
and reduced by default (Johnson 2004 counts ~56 % of the tokens of conversation
as function words). Prominence uses the list to tell whether the peak of a
phrase fell on a content word; the metrics count the share of function words
against that figure; the rules use it to recognize weak forms.

What is in: the report's weak-form table (§2), the remaining determiners and
quantifiers, pronouns (reflexives included), prepositions, auxiliaries and
modals, conjunctions, the negation particle "not", the pronoun/auxiliary and
modal-perfect contractions, and the reduced semi-auxiliaries (gonna, tryna…),
whose following verb carries the stress.

What is out, on purpose:
  * negative contractions (can't, don't, won't): they keep their full vowel and
    their stress — "can't" [kænt] is how a listener tells it from "can" [kən];
  * wh-words: "why" is a stressed content word (report §8, the why→schwa myth);
  * adverbs and particles (just, up, out, off, like): open class, and stressed
    in phrasal verbs ("give UP").
"""

from __future__ import annotations

from .canonical import clean_word

FUNCTION_WORDS = frozenset({
    # report §2, the weak-form table
    "a", "an", "the", "and", "but", "or", "of", "to", "for", "from", "at", "as",
    "than", "that", "can", "could", "would", "should", "must", "have", "has",
    "had", "was", "were", "do", "does", "am", "are", "he", "him", "his", "her",
    "them", "us", "you", "your", "some", "there",
    # determiners and quantifiers
    "this", "these", "those", "my", "our", "their", "its", "any", "each", "every",
    "no", "all", "both", "such",
    # pronouns
    "it", "i", "me", "we", "she", "they", "ya", "'em", "em", "y'all",
    "myself", "yourself", "himself", "herself", "itself", "ourselves",
    "yourselves", "themselves",
    # prepositions
    "in", "on", "with", "by", "into", "onto", "upon", "about", "over", "under",
    "through", "till", "until", "since", "per", "via", "after", "before",
    "against", "between", "without", "within", "toward", "towards", "across",
    "among",
    # auxiliaries, modals and the negation particle
    "be", "is", "been", "being", "will", "shall", "may", "might", "did", "not",
    # conjunctions
    "if", "so", "nor", "because", "'cause", "cause", "cuz", "'cuz", "though",
    "while",
    # pronoun + auxiliary contractions
    "i'm", "i've", "i'll", "i'd", "you're", "you've", "you'll", "you'd", "he's",
    "he'll", "he'd", "she's", "she'll", "she'd", "it's", "it'll", "it'd", "we're",
    "we've", "we'll", "we'd", "they're", "they've", "they'll", "they'd",
    "that's", "there's", "let's",
    # modal + perfect "have" (coulda, shoulda)
    "could've", "should've", "would've", "must've", "might've",
    # reduced semi-auxiliaries: the verb after them carries the stress
    "gonna", "wanna", "gotta", "hafta", "hasta", "oughta", "kinda", "sorta",
    "outta", "lotta", "lotsa", "coulda", "shoulda", "woulda", "musta", "mighta",
    "imma", "tryna", "finna", "useta", "usta", "supposta", "sposta",
})


def is_function_word(word: str) -> bool:
    return clean_word(word) in FUNCTION_WORDS


def word_class(word: str) -> str:
    """'function' for closed-class words, 'content' otherwise."""
    return "function" if is_function_word(word) else "content"
