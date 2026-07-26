import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def mk_phones(spaced: str, t0: float = 0.0, dur: float = 0.06) -> list[dict]:
    """'d ə z' → lista de dicts de fonos con tiempos sintéticos consecutivos."""
    phones = []
    t = t0
    for p in spaced.split():
        phones.append({"phone": p, "start": round(t, 3), "end": round(t + dur, 3), "score": 1.0})
        t += dur
    return phones


def mk_word(text: str, canonical: str, real: str, t0: float = 0.0, dur: float = 0.06,
            real_dur: float | None = None, dict_arpabet: list[str] | None = None) -> dict:
    """Construye la entrada de palabra que consume phenomena.detect()."""
    can = mk_phones(canonical, t0, dur)
    rea = mk_phones(real, t0, real_dur if real_dur is not None else dur)
    end = max([p["end"] for p in can + rea], default=t0)
    word = {
        "word": text,
        "start": t0,
        "end": round(end, 3),
        "canonical": can,
        "real": rea,
    }
    if dict_arpabet is not None:
        word["dict_arpabet"] = dict_arpabet
    return word
