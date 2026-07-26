# PLAN.md — "PhonoTrainer": Analizador fonético de habla nativa

> Documento de implementación para Claude Code. Entorno: **Fedora 44**, CPU (GPU opcional).
> Objetivo: dado un video/audio en inglés, producir una línea de tiempo alineada con:
> (1) transcripción, (2) fonos REALMENTE pronunciados, (3) pronunciación canónica alineada en tiempo,
> (4) diff etiquetado de fenómenos de connected speech (wanna, gotcha, flapping, schwa, th-stopping…),
> (5) prosodia (F0, énfasis, contorno).
>
> **Estrategia central: apoyarse en lo ya hecho.** No reinventar alineadores ni reconocedores de
> fonos. Reutilizamos componentes de proyectos MDD/CAPT existentes (que hacen este mismo diff pero
> para "corregir alumnos") y solo escribimos la capa nueva: etiquetado de fenómenos nativos + reporte.

---

## 0. Principio de diseño (leer antes de codear)

- Texto (ASR) y fonética real NO salen del mismo modelo. El ASR normaliza, el diccionario normaliza.
  Las reducciones reales solo aparecen con un reconocedor acústico de fonos.
- 3 fuentes de verdad alineadas por tiempo:
  1. Palabras + timestamps → `faster-whisper`.
  2. Fonos canónicos ALINEADOS EN TIEMPO → forced alignment (torchaudio / MFA), no solo diccionario.
  3. Fonos reales → wav2vec2 fine-tuneado a fonemas (estándar de facto en los repos MDD).
- El producto = diff (3) vs (2) por ventana temporal + prosodia. Interpretamos la desviación como
  *fenómeno nativo a enseñar*, no como error (inverso a las herramientas MDD).

## 1. Trabajo previo a reutilizar (Fase 0 — estudiar antes de codear)

Clonar en `references/` (solo lectura, para extraer patrones y código adaptable con licencia compatible):

| Repo | Qué reutilizamos | Qué NO |
|---|---|---|
| `Halleck45/OpenPronounce` | Pipeline wav2vec2→fonemas, alineación DTW fonema-a-fonema, extracción de prosodia, estructura CLI+web | Su capa de scoring "error del alumno" |
| `openai/whisper` ecosistema: `SYSTRAN/faster-whisper`, `m-bain/whisperX` | ASR con word timestamps; whisperX además trae forced alignment con wav2vec2 ya resuelto | Su diarización (no la necesitamos) |
| Repos MDD wav2vec2 (`vocaliodmiku/wav2vec2mdd`, `rhss10/joint-apa-mdd-mtl`) | Recetas de decodificación CTC de fonemas, evaluación fonema-nivel | Fine-tuning (usamos checkpoints públicos) |
| `Montreal Forced Aligner` (docs) | Referencia de calidad de alineación canónica | Instalación conda si torchaudio basta |

Modelos preentrenados (HuggingFace, descargar una vez):
- **ASR**: `faster-whisper small` (int8, CPU).
- **Fonos reales**: `facebook/wav2vec2-lv-60-espeak-cv-ft` (CTC → fonemas estilo eSpeak/IPA).
  Fallback ligero: `allosaurus eng2102`.
- **Forced alignment canónico**: `torchaudio.pipelines.MMS_FA` o el aligner de whisperX
  (ambos pip-instalables; evitamos conda/MFA salvo que la calidad lo exija).
- **Distancia fonética para el diff**: `panphon` (vectores de rasgos articulatorios → costos de
  sustitución fundados lingüísticamente, en vez de matriz ad-hoc).

Entregable de Fase 0: `references/NOTES.md` con: función exacta de OpenPronounce que hace DTW,
formato de salida del modelo espeak-phoneme, y decisión torchaudio-FA vs MFA (probar ambos en un clip).

## 2. Setup del entorno (Fedora 44)

```bash
sudo dnf install -y ffmpeg python3.11 python3.11-devel gcc gcc-c++ make git espeak-ng
# RPM Fusion si falta ffmpeg:
# sudo dnf install -y https://mirrors.rpmfusion.org/free/fedora/rpmfusion-free-release-$(rpm -E %fedora).noarch.rpm

mkdir -p ~/phonotrainer && cd ~/phonotrainer
python3.11 -m venv .venv && source .venv/bin/activate
pip install --upgrade pip wheel
```

`requirements.txt`:

```
faster-whisper>=1.0
transformers>=4.40
torch --index-url https://download.pytorch.org/whl/cpu
torchaudio --index-url https://download.pytorch.org/whl/cpu
phonemizer>=3.2        # backend espeak-ng, requerido por el modelo wav2vec2-espeak
g2p-en>=2.1
panphon>=0.20
praat-parselmouth>=0.4
numpy
soundfile
click
rich
pytest
```

Notas:
- `phonemizer` necesita `espeak-ng` del sistema (ya en dnf arriba).
- Script `scripts/download_models.py` que baje y cachee: whisper-small, wav2vec2-espeak, cmudict/NLTK.
- Si CPU resulta lenta con wav2vec2-lv-60: probar el checkpoint base o Allosaurus como fallback (flag `--phone-engine`).

## 3. Estructura del proyecto

```
phonotrainer/
├── PLAN.md
├── requirements.txt
├── references/            # repos clonados de Fase 0 + NOTES.md (gitignored)
├── scripts/download_models.py
├── phonotrainer/
│   ├── cli.py             # phonotrainer analyze <media> -o out/ [--phone-engine wav2vec2|allosaurus]
│   ├── audio.py           # ffmpeg → WAV 16kHz mono
│   ├── asr.py             # faster-whisper: palabras + timestamps
│   ├── phones_real.py     # wav2vec2-espeak CTC → fonos reales + timestamps (offsets CTC)
│   ├── align_canonical.py # forced alignment: fonemas canónicos con tiempos (torchaudio MMS_FA)
│   ├── canonical.py       # g2p_en/CMUdict como respaldo y para OOV; ARPAbet→IPA
│   ├── diff.py            # alineamiento real-vs-canónico con costos panphon + etiquetado
│   ├── phenomena.py       # reglas y tabla léxica (wanna, gonna, gotcha…)
│   ├── prosody.py         # parselmouth: F0, énfasis, contorno (adaptar enfoque OpenPronounce)
│   ├── report.py          # analysis.json + report.html estático
│   └── ipa_maps.py        # espeak↔IPA↔ARPAbet
├── tests/                 # fixtures propios (NO audio con copyright en el repo)
└── out/
```

## 4. Fases

### Fase 0 — Reconocimiento del terreno 
Clonar los repos de la tabla, correr el notebook/CLI de OpenPronounce sobre un clip propio,
documentar en `references/NOTES.md` qué funciones se adaptan. Decidir torchaudio-FA vs MFA.

### Fase 1 — Esqueleto + audio 
`audio.py` (ffmpeg `-ac 1 -ar 16000`), `cli.py` con click. Éxito: media → `out/audio.wav`.

### Fase 2 — ASR 
faster-whisper, `word_timestamps=True`, `vad_filter=True`. Guardar `out/transcript.json` SIN
postprocesar (si Whisper escribe "gonna", se conserva). 

### Fase 3 — Canónico alineado en tiempo 
`align_canonical.py` con `torchaudio.pipelines.MMS_FA` (o wav2vec2-aligner de whisperX): por cada
palabra del ASR, fonemas canónicos con [start, end] reales. `canonical.py` (g2p_en) solo para OOV
y para mostrar la forma de diccionario. Éxito: tabla palabra → fonemas canónicos con tiempos.

### Fase 4 — Fonos reales 
`phones_real.py`: wav2vec2-espeak vía transformers con `output_char_offsets=True` para timestamps
por fono; mapear alfabeto espeak→IPA en `ipa_maps.py`. Flag para Allosaurus como motor alternativo.
Éxito: para un "does that" relajado, ver algo como `d ə d ə` / `d ə z ð ə`.

### Fase 5 — Diff + etiquetado 
- Alineamiento Needleman-Wunsch entre secuencia real y canónica **dentro de cada ventana de palabra**
  (ya tenemos ambas con tiempos, el problema se vuelve local y robusto).
- Costos de sustitución = distancia de rasgos de `panphon` (vocal↔ə barato, ð↔d barato, p↔s caro).
- `phenomena.py` etiqueta sobre las operaciones:

| Etiqueta | Regla | Ejemplo |
|---|---|---|
| vowel_reduction | vocal plena→ə/ɪ átona | does→dəz |
| t_deletion / glottalization | t final elidida / t→ʔ | that→ðæ, button→bʌʔn̩ |
| th_stopping | ð→d, θ→t | that→dat |
| flapping | t,d intervocálica→ɾ | water→wɔɾɚ |
| palatalization | t+j→tʃ, d+j→dʒ, s+j→ʃ, z+j→ʒ en frontera | got you→gotcha |
| elision_syllable | sílaba omitida | probably→prɒbli |
| linking | consonante re-siliabificada | does‿it |
| h_dropping | h átona omitida | tell 'im |
| contraction_lex | tabla léxica directa | wanna, gonna, gotta, hafta, whaddya, didja, lemme, gimme, kinda, sorta, outta, shoulda… |

- Doble vía para `contraction_lex`: (a) el texto de Whisper ya trae la forma reducida, o
  (b) el texto dice "want to" pero los fonos reales no tienen /t/ ni /u/ → es wanna igual.
- TDD: tests sintéticos por regla ANTES de implementar.

### Fase 6 — Prosodia
`prosody.py` con parselmouth (adaptar el enfoque de prosodia de OpenPronounce): F0 cada 10 ms,
media/rango por segmento, palabra enfatizada (pico F0+intensidad), contorno final rising/falling.

### Fase 7 — Reporte
`out/analysis.json` (esquema abajo) + `out/report.html` autocontenido: transcripción coloreada por
fenómeno, tooltip canónico vs real, mini-SVG de F0, leyenda.

```json
{
  "segments": [{
    "start": 12.3, "end": 15.1, "text": "does that work for you",
    "f0_stats": {"mean": 118, "range": 62, "final_contour": "rising"},
    "emphasis_word_idx": 2,
    "words": [{
      "word": "does", "start": 12.30, "end": 12.44,
      "canonical_ipa": "dʌz", "canonical_aligned": [["d",12.30,12.34],["ʌ",12.34,12.40],["z",12.40,12.44]],
      "realized_ipa": "dəz",
      "phenomena": ["vowel_reduction"], "boundary_link_next": true
    }]
  }],
  "summary": {"phenomena_counts": {"vowel_reduction": 41, "flapping": 12}}
}
```

## 5. Testing y validación
- pytest; fixture = clip corto grabado por el usuario o TTS (NO commitear audio con copyright;
  los episodios se procesan solo localmente).
- Tests: mapeos espeak↔IPA↔ARPAbet completos, cada regla de phenomena con secuencias sintéticas,
  alineación con fonos en fronteras de palabra.
- Validación manual: 2–3 min de un episodio, revisar 20 palabras al azar contra el oído.

## 6. Riesgos y mitigaciones
- **Música/efectos de fondo** degradan el reconocedor de fonos → VAD de Whisper primero; fase
  opcional 8 con `demucs` para separar voz (pesado en CPU, solo si hace falta).
- **wav2vec2-lv-60 lento en CPU** → checkpoint base o Allosaurus (`--phone-engine`), o procesar
  por lotes nocturnos.
- **El modelo espeak-phoneme emite alfabeto espeak, no IPA puro** → tabla de mapeo en ipa_maps.py
  con test de cobertura total del vocabulario del modelo.
- **Whisper normaliza reducciones** → doble vía léxica de Fase 5.
- **OOV (nombres propios)** → g2p_en predice; marcar `oov: true`.

## 7. Orden de trabajo para Claude Code
1. Fase 0 completa (clonar, correr OpenPronounce en un clip, NOTES.md con decisiones).
2. Fases 1+2 en una sesión; verificar con WAV propio.
3. Fase 3 y 4 en paralelo conceptual; punto de control: tabla palabra | canónico(t) | real(t).
4. Fase 5 con TDD estricto.
5. Fases 6+7. Opcional: demucs y modo batch de carpetas.