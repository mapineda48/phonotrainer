"""Spoken English for the numerals and symbols Whisper writes as digits.

"9", "9.30", "$5", "50%" have no dictionary form: `clean_word` keeps only
letters, so their canonical came out empty and every phone heard there was an
insertion — they headed the corpus's most-divergent list. This turns them into
the words a speaker says ("nine thirty", "five dollars", "fifty percent"), and
the canonical is built from those words instead (pipeline.py sets the word's
`canonical_text`; dict_pronunciation and the espeak tokenizer read it).

Whisper's word timestamps split a numeral into several "words" ("9" ".30.",
"5" ",000.", "9" "-1" "-1"), so the expansion works on the segment's tokens and
gives each fragment the words spoken over its own stretch of audio: "9" → nine,
".30." → thirty; "5" → five, ",000." → thousand.

American usage, no "and" inside numbers (one hundred twenty-five). In-house on
purpose: the number-to-words packages are LGPL or of unclear license.
"""

from __future__ import annotations

import re

_ONES = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
         "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
         "sixteen", "seventeen", "eighteen", "nineteen"]
_TENS = ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty",
         "ninety"]
_SCALES = [(10 ** 9, "billion"), (10 ** 6, "million"), (10 ** 3, "thousand")]
# the scale word a comma group carries, counted from the right: 5,000 → thousand
_GROUP_SCALE = ["", "thousand", "million", "billion"]
_ORDINAL_IRREGULAR = {"one": "first", "two": "second", "three": "third", "five": "fifth",
                      "eight": "eighth", "nine": "ninth", "twelve": "twelfth"}
_SYMBOLS = {"%": "percent", "&": "and", "+": "plus", "@": "at"}

# punctuation that sits around a token without being part of the numeral
_LEAD = "\"'“‘([{"
_TRAIL = "\"'”’)]}.,!?;:…"
_CONTINUATION = re.compile(r"[.,:\-]\d")


def cardinal(n: int) -> str:
    """12 → twelve, 125 → one hundred twenty-five, -3 → minus three."""
    if n < 0:
        return "minus " + cardinal(-n)
    if n < 20:
        return _ONES[n]
    if n < 100:
        tens, ones = divmod(n, 10)
        return _TENS[tens] + (f"-{_ONES[ones]}" if ones else "")
    if n < 1000:
        hundreds, rest = divmod(n, 100)
        return f"{_ONES[hundreds]} hundred" + (f" {cardinal(rest)}" if rest else "")
    for value, name in _SCALES:
        if n >= value:
            high, rest = divmod(n, value)
            return f"{cardinal(high)} {name}" + (f" {cardinal(rest)}" if rest else "")
    raise AssertionError(n)  # unreachable


def ordinal(n: int) -> str:
    """1 → first, 21 → twenty-first, 30 → thirtieth."""
    words = cardinal(n)
    head, sep, last = words.rpartition(" ")
    pre, hyphen, unit = last.rpartition("-")
    if unit in _ORDINAL_IRREGULAR:
        unit = _ORDINAL_IRREGULAR[unit]
    elif unit.endswith("y"):
        unit = unit[:-1] + "ieth"
    else:
        unit += "th"
    return head + sep + pre + hyphen + unit


def year(n: int) -> str:
    """Read in pairs: 1999 → nineteen ninety-nine, 1905 → nineteen oh five,
    1900 → nineteen hundred; 2000–2009 → two thousand five."""
    if 2000 <= n <= 2009:
        return cardinal(n)
    high, low = divmod(n, 100)
    if low == 0:
        return f"{cardinal(high)} hundred"
    if low < 10:
        return f"{cardinal(high)} oh {cardinal(low)}"
    return f"{cardinal(high)} {cardinal(low)}"


def clock(hours: int, minutes: int) -> str:
    """9:30 → nine thirty, 9:05 → nine oh five, 9:00 → nine o'clock."""
    if minutes == 0:
        return f"{cardinal(hours)} o'clock"
    if minutes < 10:
        return f"{cardinal(hours)} oh {cardinal(minutes)}"
    return f"{cardinal(hours)} {cardinal(minutes)}"


def digits(s: str) -> str:
    """Digit by digit: 911 → nine one one, 007 → zero zero seven."""
    return " ".join(_ONES[int(d)] for d in s if d.isdigit())


def _plural(words: str) -> str:
    """The decade reading: eighty → eighties, nineteen ninety → nineteen nineties."""
    if words.endswith("y"):
        return words[:-1] + "ies"
    if words.endswith(("s", "x")):
        return words + "es"
    return words + "s"


def _core(token: str) -> str:
    return token.lstrip(_LEAD).rstrip(_TRAIL)


def _number(s: str) -> str | None:
    """A bare digit string (commas allowed) read the way it is usually said."""
    plain = s.replace(",", "")
    if not plain.isdigit():
        return None
    n = int(plain)
    if "," in s:
        return cardinal(n)
    if len(plain) > 1 and plain.startswith("0"):
        return digits(plain)           # 007, 0800
    if plain == "911":
        return digits(plain)           # the emergency number, never "nine hundred eleven"
    if len(plain) == 4 and (1100 <= n <= 1999 or 2000 <= n <= 2099):
        return year(n)
    if len(plain) > 9:
        return digits(plain)
    return cardinal(n)


def spoken(token: str) -> str | None:
    """One standalone token as it is said, or None if it is not a numeral/symbol."""
    core = _core(token)
    if core in _SYMBOLS:
        return _SYMBOLS[core]
    if not any(ch.isdigit() for ch in core):
        return None

    m = re.fullmatch(r"\$(\d[\d,]*)(?:\.(\d{2}))?", core)
    if m:
        n = int(m.group(1).replace(",", ""))
        words = f"{cardinal(n)} {'dollar' if n == 1 else 'dollars'}"
        if m.group(2) and int(m.group(2)):
            cents = int(m.group(2))
            words += f" and {cardinal(cents)} {'cent' if cents == 1 else 'cents'}"
        return words
    m = re.fullmatch(r"(\d[\d,]*(?:\.\d+)?)%", core)
    if m:
        return f"{spoken(m.group(1))} percent"
    m = re.fullmatch(r"(\d{1,2})[:.](\d{2})", core)
    if m and int(m.group(1)) <= 23 and int(m.group(2)) <= 59:
        return clock(int(m.group(1)), int(m.group(2)))
    m = re.fullmatch(r"(\d+)\.(\d+)", core)
    if m:
        return f"{_number(m.group(1))} point {digits(m.group(2))}"
    m = re.fullmatch(r"(\d+)(st|nd|rd|th)", core, flags=re.IGNORECASE)
    if m:
        return ordinal(int(m.group(1)))
    m = re.fullmatch(r"'?(\d+)'?s", core)
    if m:
        number = _number(m.group(1))
        return _plural(number) if number else None
    m = re.fullmatch(r"-(\d+)", core)
    if m:
        return f"minus {_number(m.group(1))}"
    number = _number(core)
    if number is not None:
        return number
    # letters and digits in one token (COVID-19, F-16, mp3): digits spoken, letters kept
    parts = [p for p in re.split(r"[-/]", core) if p]
    if len(parts) > 1 or re.search(r"[a-z]", core, flags=re.IGNORECASE):
        out = []
        for part in re.findall(r"[A-Za-z']+|\d+", core):
            out.append(_number(part) if part.isdigit() else part.lower())
        return " ".join(p for p in out if p) or None
    return None


def _group(parts: list[str]) -> list[str]:
    """The words of each fragment of ONE numeral that Whisper split in several."""
    first, rest = parts[0], parts[1:]
    seps = {p[0] for p in rest}
    money = first.startswith("$")
    head = first.lstrip("$")

    if seps == {","} and head.isdigit() and all(p[1:].isdigit() for p in rest):
        # 5,000 → five | thousand: each comma group says its value, and the scale
        # word of the group before it, which is spoken over this fragment's audio
        values = [int(head)] + [int(p[1:]) for p in rest]
        scales = [_GROUP_SCALE[len(values) - 1 - i] for i in range(len(values))]
        out = []
        for i, value in enumerate(values):
            words = []
            if i and values[i - 1]:
                words.append(scales[i - 1])
            if value:
                words.append(cardinal(value))
            out.append(" ".join(words))
        if money:
            out[-1] = (out[-1] + " dollars").strip()
        return out

    if len(rest) == 1 and rest[0][0] in ".:" and head.isdigit():
        frac = rest[0][1:]
        if len(frac) == 2 and int(head) <= 23 and int(frac) <= 59:
            minutes = int(frac)        # 9 | .30 → nine | thirty
            tail = ("o'clock" if minutes == 0
                    else f"oh {cardinal(minutes)}" if minutes < 10 else cardinal(minutes))
            return [cardinal(int(head)), tail]
        if frac.isdigit():
            return [_number(head) or "", f"point {digits(frac)}"]

    if seps == {"-"} and head.isdigit():
        # 9-1-1, 555-1234: read digit by digit, as numbers like these are said
        return [digits(head)] + [digits(p[1:]) for p in rest]

    # anything else: each fragment on its own
    return [spoken(p) or "" for p in parts]


def expand_tokens(tokens: list[str]) -> list[str | None]:
    """Per token: the words it stands for, or None when it is an ordinary word.

    An empty string means a numeric fragment with no words of its own (the
    trailing ",000" of 5,000,000): its canonical stays empty.
    """
    out: list[str | None] = [None] * len(tokens)
    cores = [_core(t) for t in tokens]
    i = 0
    while i < len(tokens):
        core = cores[i]
        if not any(ch.isdigit() for ch in core) and core not in _SYMBOLS:
            i += 1
            continue
        j = i + 1
        while (j < len(tokens) and _CONTINUATION.match(cores[j])
               and cores[j - 1][-1:].isdigit()):
            j += 1
        if j - i == 1:
            if i > 0 and cores[i - 1][-1:].isalpha() and re.fullmatch(r"-\d+", core):
                out[i] = _number(core[1:])      # COVID -19: a joined name, not "minus"
            else:
                out[i] = spoken(tokens[i])
        else:
            out[i:j] = _group(cores[i:j])
        i = j
    return out
