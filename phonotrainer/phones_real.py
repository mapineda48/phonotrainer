"""Fonos REALMENTE pronunciados: wav2vec2-espeak CTC → tokens fonéticos con tiempos.

El mismo motor expone las emisiones (log-probs) para que align_canonical.py haga
forced alignment de la secuencia canónica con el MISMO modelo: real y canónico
quedan en el mismo alfabeto (espeak/IPA) y comparten una sola pasada acústica.
"""

from __future__ import annotations

import numpy as np

from .ipa_maps import (DIPHTHONGS, FLAP, GLOTTAL, is_full_vowel, is_schwa_like,
                       is_vowel, normalize_espeak)

MODEL_ID = "facebook/wav2vec2-lv-60-espeak-cv-ft"
SAMPLE_RATE = 16000

# MEJORA 1: atracción fonética hacia el canónico.
# Umbral calibrado: las confusiones acústicas (b→v 0.42, n→l 0.67, l→d 1.17,
# h→f 1.25) quedan debajo; V↔C (1.5) queda fuera. j→t da 1.5 (tope C↔C) pero es
# confusión típica del modelo ("you"→[tuː]): va en tabla explícita.
DEFAULT_ATTRACTION_MAX_COST = 1.3
EXTRA_ATTRACT = {frozenset(("j", "t")), frozenset(("j", "d"))}


def attract_to_canonical(realized: list[dict], canonical: list[dict],
                         max_cost: float = DEFAULT_ATTRACTION_MAX_COST) -> list[dict]:
    """Sustituye fonos reales por su contraparte canónica cuando la confusión es
    acústica y sin valor didáctico. Nunca inserta ni borra; nunca toca variación
    nativa (NATIVE_SHIFTS, reducciones, monoptongaciones, flaps/glotales).
    Marca "attracted": True y conserva raw_phone.

    La contraparte sale del alineamiento NW, no del solape máximo: un fono
    canónico ELIDIDO queda como `del` y no puede absorber al fono real vecino
    (bug detectado en validación: "don't"→[doʊn] se volvía [doʊt]). El solape
    temporal se mantiene como condición adicional.
    """
    from .diff import align_word, phone_cost

    if not realized or not canonical:
        return [dict(p) for p in realized]

    attract: dict[int, str] = {}  # id(fono real) → fono canónico
    for op in align_word(realized, canonical, cost_fn=_attraction_cost):
        if op["op"] != "sub":
            continue
        c, r = op["canonical"], op["real"]
        cp, rp = c["phone"], r["phone"]
        if _protected(cp, rp):
            continue
        pair = frozenset((cp, rp))
        if pair not in EXTRA_ATTRACT and phone_cost(cp, rp) > max_cost:
            continue
        overlap = min(r["end"], c["end"]) - max(r["start"], c["start"])
        mid_dist = abs((r["start"] + r["end"]) - (c["start"] + c["end"])) / 2
        if overlap <= 0 and mid_dist > 0.15:
            continue  # emparejados por secuencia pero temporalmente ajenos
        attract[id(r)] = cp

    out = []
    for p in realized:
        q = dict(p)
        if id(p) in attract:
            q.setdefault("raw_phone", q["phone"])
            q["phone"] = attract[id(p)]
            q["attracted"] = True
        out.append(q)
    return out


def _attraction_cost(canon: str, real: str) -> float:
    """Costo para el NW de atracción: las confusiones de EXTRA_ATTRACT deben
    emparejarse como sustitución (su costo real 1.5 haría preferir del+ins)."""
    from .diff import phone_cost

    if frozenset((canon, real)) in EXTRA_ATTRACT:
        return 0.4
    return phone_cost(canon, real)


def _protected(canon: str, real: str) -> bool:
    """Cambios que son fenómeno (o candidatos a serlo): la atracción no los toca."""
    from .diff import is_native_shift

    if is_native_shift(canon, real):
        return True
    if real in FLAP or real in GLOTTAL:
        return True
    # reducción vocálica (mismo criterio que phenomena._word_rules)
    if is_full_vowel(canon) and (is_schwa_like(real) or real == "ɪ"):
        return True
    # candidato a monophthongization: diptongo canónico + vocal simple real
    if canon in DIPHTHONGS and is_vowel(real) and real not in DIPHTHONGS:
        return True
    return False


class Wav2Vec2PhoneEngine:
    def __init__(self, model_id: str = MODEL_ID, device: str = "cpu"):
        import torch
        from transformers import AutoModelForCTC, AutoProcessor

        self.torch = torch
        self.processor = AutoProcessor.from_pretrained(model_id)
        self.model = AutoModelForCTC.from_pretrained(model_id).to(device).eval()
        self.tokenizer = self.processor.tokenizer
        self.blank_id = self.tokenizer.pad_token_id
        self.special_ids = set(self.tokenizer.all_special_ids)
        self.device = device

    def log_probs(self, audio: np.ndarray):
        """Emisiones CTC log-softmax [T, C] para un fragmento mono 16 kHz."""
        torch = self.torch
        with torch.inference_mode():
            inputs = self.processor(audio, sampling_rate=SAMPLE_RATE, return_tensors="pt")
            logits = self.model(inputs.input_values.to(self.device)).logits[0]
            return torch.log_softmax(logits, dim=-1).cpu()

    @staticmethod
    def frame_duration(n_samples: int, n_frames: int) -> float:
        return n_samples / n_frames / SAMPLE_RATE

    def greedy_phones(self, audio: np.ndarray, t_offset: float = 0.0,
                      log_probs=None) -> list[dict]:
        """Decodificación CTC greedy con colapso de repeticiones y spans de frames.

        Devuelve [{'phone', 'start', 'end', 'score'}, …] en segundos absolutos.
        """
        lp = self.log_probs(audio) if log_probs is None else log_probs
        ids = lp.argmax(dim=-1).tolist()
        frame_dur = self.frame_duration(len(audio), len(ids))

        spans: list[list] = []  # [token_id, frame_ini, frame_fin, score_acum, n]
        prev_id = None
        for i, tid in enumerate(ids):
            if tid == self.blank_id:
                prev_id = None
                continue
            score = float(lp[i, tid])
            if tid == prev_id:
                spans[-1][2] = i + 1
                spans[-1][3] += score
                spans[-1][4] += 1
            else:
                spans.append([tid, i, i + 1, score, 1])
                prev_id = tid

        phones = []
        for tid, f0, f1, score, n in spans:
            if tid in self.special_ids:
                continue
            raw = self.tokenizer.convert_ids_to_tokens(tid)
            norm = normalize_espeak(raw)
            if norm is None:
                continue  # token sin equivalente inglés (ya se logueó el warning)
            phones.append({
                "phone": norm,
                "raw_phone": raw,
                "start": round(t_offset + f0 * frame_dur, 3),
                "end": round(t_offset + f1 * frame_dur, 3),
                "score": round(float(np.exp(score / n)), 3),
            })
        return phones


def build_engine(name: str = "wav2vec2", device: str = "cpu"):
    """Fábrica de motores de fonos (flag --phone-engine)."""
    if name == "wav2vec2":
        return Wav2Vec2PhoneEngine(device=device)
    if name == "allosaurus":
        raise NotImplementedError(
            "Motor allosaurus no instalado. `pip install allosaurus` y ver task.md; "
            "el motor por defecto (wav2vec2) no lo requiere."
        )
    raise ValueError(f"Motor de fonos desconocido: {name}")
