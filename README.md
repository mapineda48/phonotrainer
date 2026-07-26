# PhonoTrainer

Analizador fonético de habla nativa en inglés. Dado un **video o audio**, produce una
línea de tiempo alineada con: transcripción, fonos realmente pronunciados, pronunciación
canónica alineada en tiempo, diff etiquetado de fenómenos de *connected speech*
(wanna, gotcha, flapping, schwa…) y prosodia (F0, énfasis, contorno). Ver `task.md`
para el plan completo y `references/NOTES.md` para las decisiones de la Fase 0.

## Uso

```bash
source .venv/bin/activate            # venv creado con: uv venv --python 3.12 .venv
phonotrainer analyze episodio.webm -o out/          # video
phonotrainer analyze entrevista.mp3 -o out/          # o solo audio
```

Salidas en `out/`: `audio.wav`, `transcript.json`, `canonical.json`,
`phones_real.json`, `analysis.json` y `report.html` (autocontenido, con modo oscuro).

Opciones: `--whisper-model tiny|base|small|medium` (default `small`),
`--phone-engine wav2vec2|allosaurus` (default `wav2vec2`), `--language en`,
`--no-attraction` (desactiva la atracción fonética, para comparar salidas).

Validación humana muestreada (prioriza palabras atraídas/baja confianza/diff alto):

```bash
phonotrainer review out/analysis.json -n 20 --seed 48   # → out/review.json
```

## Setup desde cero

```bash
sudo dnf install -y ffmpeg espeak-ng      # requisitos de sistema (Fedora)
uv venv --python 3.12 .venv
uv pip install --python .venv/bin/python torch torchaudio --index-url https://download.pytorch.org/whl/cpu
uv pip install --python .venv/bin/python -r requirements.txt -e .
.venv/bin/python scripts/download_models.py   # ~1.8 GB de modelos (una vez)
```

## Arquitectura (resumen)

- **ASR**: faster-whisper `small` int8, `word_timestamps=True`, `vad_filter=True`.
  El texto se conserva sin postprocesar.
- **Fonos reales**: `facebook/wav2vec2-lv-60-espeak-cv-ft` (CTC greedy con spans de
  frames → timestamps por fono). La salida cruda (multilingüe) se sanea al inventario
  inglés (`ipa_maps.normalize_espeak`, crudo en `raw_phone`) y las confusiones
  acústicas sin valor didáctico se atraen al canónico (`phones_real.attract_to_canonical`),
  sin tocar jamás la variación nativa (`diff.NATIVE_SHIFTS`, reducciones, monoptongaciones).
- **Canónico alineado**: `torchaudio.functional.forced_align` sobre las emisiones del
  MISMO modelo, con la secuencia canónica fonemizada por su propio tokenizer
  (espeak-ng). Real y canónico comparten alfabeto y pasada acústica.
- **Diff**: Needleman-Wunsch por ventana de palabra; costos = rasgos panphon +
  tabla de cambios nativos atestiguados (`diff.NATIVE_SHIFTS`).
- **Fenómenos** (`phenomena.py`): reducción vocálica, elisión de t/d, glotalización,
  th-stopping, flapping, palatalización, elisión de sílaba, linking, h muda y
  contracciones léxicas por doble vía (texto de Whisper o evidencia fonética).
- **Prosodia**: parselmouth — F0 cada 10 ms, énfasis por palabra (pico F0×intensidad),
  contorno final rising/falling.

## Tests

```bash
.venv/bin/python -m pytest tests/ -q
```

Fixtures 100 % sintéticos (tonos numpy + media generada con ffmpeg): no hay audio
con copyright en el repo; los episodios se procesan solo localmente.
