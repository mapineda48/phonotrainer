"""Real-vs-canonical diff: Needleman-Wunsch per word window, panphon costs.

Raw panphon costs invert the ordering we want on key pairs (t↔ɾ comes out pricier
than p↔s; t↔ʔ is wildly expensive), so the attested native connected-speech shifts
get a fixed low cost in NATIVE_SHIFTS and panphon covers everything else.
"""

from __future__ import annotations

from functools import lru_cache

from .ipa_maps import is_vowel, normalize_for_panphon

GAP_COST = 0.7
_SCALE = 3.0
_MAX_COST = 1.5

# Attested allophonic/native shifts (symmetric): cost << GAP_COST·2
NATIVE_SHIFTS = {
    frozenset(p): c for p, c in [
        (("t", "ɾ"), 0.25), (("d", "ɾ"), 0.25), (("ɾ", "ɾ̃"), 0.15),
        (("t", "ʔ"), 0.30), (("d", "ʔ"), 0.35), (("h", "ʔ"), 0.45),
        (("ð", "d"), 0.30), (("θ", "t"), 0.30),
        (("ð", "d̪"), 0.20), (("θ", "t̪"), 0.20), (("d", "d̪"), 0.15), (("t", "t̪"), 0.15),
        (("t", "tʃ"), 0.40), (("d", "dʒ"), 0.40), (("s", "ʃ"), 0.30), (("z", "ʒ"), 0.30),
        (("n", "ŋ"), 0.35), (("n", "m"), 0.40), (("n", "ɾ̃"), 0.25),
        (("l", "ɫ"), 0.10), (("ɹ", "ɚ"), 0.45),
        # native "your"/"sure" variation seen during external validation
        # (yor/yer): never pull these back toward the canonical ʊɹ
        (("ʊɹ", "ɔːɹ"), 0.25), (("ʊɹ", "oːɹ"), 0.25), (("ʊɹ", "ɔɹ"), 0.25),
        (("ʊɹ", "ɚ"), 0.35), (("ʊɹ", "ə"), 0.40),
    ]
}


def is_native_shift(a: str, b: str) -> bool:
    """Is (a, b) an attested native shift (either raw or normalized)?"""
    if frozenset((a, b)) in NATIVE_SHIFTS:
        return True
    na, nb = normalize_for_panphon(a), normalize_for_panphon(b)
    return frozenset((na, nb)) in NATIVE_SHIFTS


@lru_cache(maxsize=1)
def _panphon_distance():
    import panphon.distance

    return panphon.distance.Distance()


@lru_cache(maxsize=65536)
def phone_cost(a: str, b: str) -> float:
    """Cost of substituting the real phone `b` for the canonical phone `a`."""
    if a == b:
        return 0.0
    # raw lookup BEFORE normalizing: pairs such as (ɹ, ɚ) or (ʊɹ, ɚ) would be lost
    # after normalize_for_panphon (ɚ→ə)
    key = frozenset((a, b))
    if key in NATIVE_SHIFTS:
        return NATIVE_SHIFTS[key]
    na, nb = normalize_for_panphon(a), normalize_for_panphon(b)
    if na == nb:
        return 0.05
    key = frozenset((na, nb))
    if key in NATIVE_SHIFTS:
        return NATIVE_SHIFTS[key]

    va, vb = is_vowel(na), is_vowel(nb)
    if va != vb:
        return _MAX_COST

    try:
        raw = _panphon_distance().weighted_feature_edit_distance(na, nb)
    except Exception:
        raw = None
    if raw is None or raw <= 0:
        return 1.0
    cost = raw / _SCALE
    if va and vb:
        # vowels drift a lot in fast speech: never make this prohibitive
        return min(cost, 0.9)
    return min(max(cost, 0.15), _MAX_COST)


def align_word(real: list[dict], canonical: list[dict],
               gap_cost: float = GAP_COST, cost_fn=None) -> list[dict]:
    """NW alignment between the real and canonical phones of ONE word.

    Returns ops [{'op': match|sub|del|ins, 'canonical': dict|None,
    'real': dict|None, 'cost': float}] in canonical/temporal order.
    `cost_fn(canonical, real)` allows an alternative cost (attraction uses it).
    """
    if cost_fn is None:
        cost_fn = phone_cost
    n, m = len(real), len(canonical)
    if n == 0 and m == 0:
        return []

    # dp[i][j]: cost of aligning real[:i] with canonical[:j]
    dp = [[0.0] * (m + 1) for _ in range(n + 1)]
    back = [[""] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        dp[i][0] = i * gap_cost
        back[i][0] = "ins"
    for j in range(1, m + 1):
        dp[0][j] = j * gap_cost
        back[0][j] = "del"
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            sub = dp[i - 1][j - 1] + cost_fn(canonical[j - 1]["phone"], real[i - 1]["phone"])
            dele = dp[i][j - 1] + gap_cost
            ins = dp[i - 1][j] + gap_cost
            best = min(sub, dele, ins)
            dp[i][j] = best
            back[i][j] = "sub" if best == sub else ("del" if best == dele else "ins")

    ops: list[dict] = []
    i, j = n, m
    while i > 0 or j > 0:
        move = back[i][j]
        if move == "sub":
            c, r = canonical[j - 1], real[i - 1]
            cost = cost_fn(c["phone"], r["phone"])
            ops.append({
                "op": "match" if c["phone"] == r["phone"] else "sub",
                "canonical": c, "real": r, "cost": round(cost, 3),
            })
            i, j = i - 1, j - 1
        elif move == "del":
            ops.append({"op": "del", "canonical": canonical[j - 1], "real": None,
                        "cost": gap_cost})
            j -= 1
        else:
            ops.append({"op": "ins", "canonical": None, "real": real[i - 1],
                        "cost": gap_cost})
            i -= 1
    ops.reverse()
    return ops


def assign_real_to_words(real_phones: list[dict], word_spans: list[dict],
                         max_orphan_gap: float = 0.12) -> list[list[dict]]:
    """Distribute the real phones (in temporal order) across the word windows.

    A phone falls into the word whose [start, end] contains its midpoint; if it lands
    in a gap between words it is assigned to the closest one (provided that one is
    less than `max_orphan_gap` s away), and otherwise it is dropped as inter-word
    noise.
    """
    buckets: list[list[dict]] = [[] for _ in word_spans]
    if not word_spans:
        return buckets
    for ph in real_phones:
        mid = (ph["start"] + ph["end"]) / 2
        chosen = None
        for k, w in enumerate(word_spans):
            if w["start"] <= mid <= w["end"]:
                chosen = k
                break
        if chosen is None:
            dists = [
                (max(w["start"] - mid, mid - w["end"], 0.0), k)
                for k, w in enumerate(word_spans)
            ]
            dist, k = min(dists)
            if dist <= max_orphan_gap:
                chosen = k
        if chosen is not None:
            buckets[chosen].append(ph)
    return buckets
