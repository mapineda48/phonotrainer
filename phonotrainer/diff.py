"""Diff real-vs-canónico: Needleman-Wunsch por ventana de palabra, costos panphon.

Los costos crudos de panphon invierten el orden deseado en pares clave (t↔ɾ sale
más caro que p↔s; t↔ʔ carísimo), así que los cambios nativos atestiguados de
connected speech llevan costo bajo fijo en NATIVE_SHIFTS y panphon cubre el resto.
"""

from __future__ import annotations

from functools import lru_cache

from .ipa_maps import is_vowel, normalize_for_panphon

GAP_COST = 0.7
_SCALE = 3.0
_MAX_COST = 1.5

# Cambios alofónicos/nativos atestiguados (simétricos): costo << GAP_COST·2
NATIVE_SHIFTS = {
    frozenset(p): c for p, c in [
        (("t", "ɾ"), 0.25), (("d", "ɾ"), 0.25), (("ɾ", "ɾ̃"), 0.15),
        (("t", "ʔ"), 0.30), (("d", "ʔ"), 0.35), (("h", "ʔ"), 0.45),
        (("ð", "d"), 0.30), (("θ", "t"), 0.30),
        (("ð", "d̪"), 0.20), (("θ", "t̪"), 0.20), (("d", "d̪"), 0.15), (("t", "t̪"), 0.15),
        (("t", "tʃ"), 0.40), (("d", "dʒ"), 0.40), (("s", "ʃ"), 0.30), (("z", "ʒ"), 0.30),
        (("n", "ŋ"), 0.35), (("n", "m"), 0.40), (("n", "ɾ̃"), 0.25),
        (("l", "ɫ"), 0.10), (("ɹ", "ɚ"), 0.45),
        # variación nativa de "your"/"sure" observada en validación externa
        # (yor/yer): jamás atraer hacia el canónico ʊɹ
        (("ʊɹ", "ɔːɹ"), 0.25), (("ʊɹ", "oːɹ"), 0.25), (("ʊɹ", "ɔɹ"), 0.25),
        (("ʊɹ", "ɚ"), 0.35), (("ʊɹ", "ə"), 0.40),
    ]
}


def is_native_shift(a: str, b: str) -> bool:
    """¿Es (a, b) un cambio nativo atestiguado (en crudo o normalizado)?"""
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
    """Costo de sustituir el fono canónico `a` por el real `b`."""
    if a == b:
        return 0.0
    # lookup crudo ANTES de normalizar: pares como (ɹ, ɚ) o (ʊɹ, ɚ) se perderían
    # tras normalize_for_panphon (ɚ→ə)
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
        # las vocales derivan mucho en habla rápida: nunca prohibitivo
        return min(cost, 0.9)
    return min(max(cost, 0.15), _MAX_COST)


def align_word(real: list[dict], canonical: list[dict],
               gap_cost: float = GAP_COST, cost_fn=None) -> list[dict]:
    """NW entre fonos reales y canónicos de UNA palabra.

    Devuelve ops [{'op': match|sub|del|ins, 'canonical': dict|None,
    'real': dict|None, 'cost': float}] en orden canónico/temporal.
    `cost_fn(canónico, real)` permite un costo alternativo (lo usa la atracción).
    """
    if cost_fn is None:
        cost_fn = phone_cost
    n, m = len(real), len(canonical)
    if n == 0 and m == 0:
        return []

    # dp[i][j]: costo de alinear real[:i] con canonical[:j]
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
    """Reparte los fonos reales (orden temporal) entre ventanas de palabra.

    Un fono cae en la palabra cuyo [start, end] contiene su punto medio; si queda
    en un hueco entre palabras se asigna a la más cercana (si está a menos de
    `max_orphan_gap` s), y si no, se descarta como ruido inter-palabra.
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
