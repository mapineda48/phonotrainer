"""The interface's articulator has to be able to draw every phone we emit.

`web/src/articulation/phones.ts` holds a mouth — tongue, jaw, lips, velum — for
each symbol of `ipa_maps.ENGLISH_INVENTORY`. A phone the analyzer produces and
the engine has never heard of leaves a hole in the middle of a word: the tongue
simply holds the previous shape and the learner is shown a movement that did
not happen.

Nothing links the two halves at runtime, so this test is the link: add a phone
to the inventory and it fails until the drawing exists.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from phonotrainer import ipa_maps

WEB = Path(__file__).resolve().parent.parent / "web" / "src" / "articulation"
TABLE = WEB / "phones.ts"

#: Stress and length are not articulations: /ˈuː/ and /u/ are the same mouth,
#: and the interface strips them before looking a phone up (`bareSymbol`).
BARE = str.maketrans("", "", "ˈˌː")


def _symbols_in(source: str) -> set[str]:
    return set(re.findall(r'^\s*symbol: "([^"]+)"', source, flags=re.MULTILINE))


@pytest.mark.skipif(not TABLE.exists(), reason="web/ is not checked out")
def test_every_phone_we_emit_can_be_drawn():
    drawn = _symbols_in(TABLE.read_text(encoding="utf-8"))
    needed = {token.translate(BARE) for token in ipa_maps.ENGLISH_INVENTORY}
    assert needed - drawn == set()


@pytest.mark.skipif(not TABLE.exists(), reason="web/ is not checked out")
def test_the_dictionary_row_can_be_drawn_too():
    # The dictionary form comes from CMUdict through ARPAbet, not from espeak:
    # a different road into the same drawing.
    drawn = _symbols_in(TABLE.read_text(encoding="utf-8"))
    needed = {ipa.translate(BARE) for ipa in ipa_maps.ARPABET_TO_IPA.values()}
    assert needed - drawn == set()


@pytest.mark.skipif(not TABLE.exists(), reason="web/ is not checked out")
def test_the_inventory_copied_into_the_web_test_has_not_drifted():
    # The vitest suite checks the same coverage against a copy of the inventory
    # (it cannot import Python). If that copy goes stale, its check is empty.
    spec = (WEB / "phones.test.ts").read_text(encoding="utf-8")
    listed = re.search(r"const INVENTORY = `([^`]+)`", spec)
    assert listed is not None, "phones.test.ts no longer carries the inventory"
    assert set(listed.group(1).split()) == set(ipa_maps.ENGLISH_INVENTORY)
