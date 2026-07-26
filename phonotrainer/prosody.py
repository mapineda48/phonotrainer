"""Prosodia con parselmouth: F0 cada 10 ms, énfasis por palabra y contorno final.

Adapta el enfoque de OpenPronounce (F0 acotada a rango de habla + interpolación de
huecos sordos) y añade lo que le falta: stats por segmento, palabra enfatizada
(pico F0 × intensidad) y contorno final rising/falling.
"""

from __future__ import annotations

import numpy as np

F0_FLOOR = 65.0   # Hz
F0_CEIL = 400.0
TIME_STEP = 0.01  # 10 ms


class ProsodyExtractor:
    def __init__(self, wav_path: str):
        import parselmouth

        self.snd = parselmouth.Sound(str(wav_path))
        pitch = self.snd.to_pitch(time_step=TIME_STEP, pitch_floor=F0_FLOOR,
                                  pitch_ceiling=F0_CEIL)
        self.f0_times = pitch.xs()
        f0 = pitch.selected_array["frequency"].astype(float)
        f0[f0 == 0] = np.nan  # frames sordos
        self.f0 = f0
        intensity = self.snd.to_intensity(time_step=TIME_STEP, minimum_pitch=F0_FLOOR)
        self.int_times = intensity.xs()
        self.intensity = intensity.values[0].astype(float)

    # --- helpers -------------------------------------------------------------
    def _slice(self, arr: np.ndarray, times: np.ndarray, t0: float, t1: float):
        mask = (times >= t0) & (times <= t1)
        return arr[mask], times[mask]

    def f0_track(self, t0: float, t1: float) -> list[list[float]]:
        """[(t, f0), …] cada 10 ms con huecos sordos interpolados (para el SVG)."""
        f0, times = self._slice(self.f0, self.f0_times, t0, t1)
        if len(f0) == 0 or np.all(np.isnan(f0)):
            return []
        voiced = ~np.isnan(f0)
        interp = np.interp(times, times[voiced], f0[voiced])
        return [[round(float(t), 3), round(float(v), 1)] for t, v in zip(times, interp)]

    # --- API por segmento ----------------------------------------------------
    def segment_stats(self, t0: float, t1: float) -> dict:
        f0, _ = self._slice(self.f0, self.f0_times, t0, t1)
        voiced = f0[~np.isnan(f0)]
        if len(voiced) < 3:
            return {"mean": None, "range": None, "final_contour": "flat"}
        return {
            "mean": round(float(np.nanmean(voiced)), 1),
            "range": round(float(np.nanpercentile(voiced, 95)
                                 - np.nanpercentile(voiced, 5)), 1),
            "final_contour": self._final_contour(t0, t1),
        }

    def _final_contour(self, t0: float, t1: float, tail: float = 0.35) -> str:
        """Pendiente de F0 en el último tramo sonoro del segmento."""
        f0, times = self._slice(self.f0, self.f0_times, t0, t1)
        voiced = ~np.isnan(f0)
        if voiced.sum() < 5:
            return "flat"
        vt, vf = times[voiced], f0[voiced]
        cut = max(vt[-1] - tail, vt[0])
        tail_mask = vt >= cut
        if tail_mask.sum() < 3:
            return "flat"
        slope = np.polyfit(vt[tail_mask], vf[tail_mask], 1)[0]  # Hz/s
        if slope > 30:
            return "rising"
        if slope < -30:
            return "falling"
        return "flat"

    def emphasis_word_idx(self, words: list[dict]) -> int | None:
        """Índice de la palabra con mayor prominencia (pico F0 norm. × intensidad)."""
        scores = []
        for w in words:
            f0, _ = self._slice(self.f0, self.f0_times, w["start"], w["end"])
            inten, _ = self._slice(self.intensity, self.int_times, w["start"], w["end"])
            f0_peak = float(np.nanmax(f0)) if len(f0) and not np.all(np.isnan(f0)) else 0.0
            int_peak = float(np.nanmax(inten)) if len(inten) else 0.0
            scores.append(f0_peak * int_peak)
        if not scores or max(scores) <= 0:
            return None
        return int(np.argmax(scores))
