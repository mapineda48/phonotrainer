# NOTES.md — Fase 0: hallazgos de los repos de referencia

> Objetivo: extraer lo reutilizable para PhonoTrainer (diff fonos reales vs canónicos alineados
> en tiempo + fenómenos de connected speech). Rutas relativas a `references/`.
> Convención de citas: `repo/archivo.py::funcion` (número de línea aproximado al commit clonado).

---

## 1. OpenPronounce (`references/OpenPronounce/`)

Repo plano, sin `src/`: toda la lógica vive en `speech.py` (raíz). Entradas: `cli.py`,
`server.py` (FastAPI), `streamlit_app.py`. Licencia MIT.

### 1.1 Pipeline real (¡ojo: NO es wav2vec2→fonemas!)

- Checkpoint: `facebook/wav2vec2-large-960h` (`OpenPronounce/speech.py` línea 18, `MODEL_NAME`),
  cargado dos veces: `Wav2Vec2Model` (embeddings crudos) y `Wav2Vec2ForCTC` (transcripción a TEXTO).
- `OpenPronounce/speech.py::transcribe` (l. 417): CTC greedy — `torch.argmax(logits, -1)` +
  `processor.batch_decode`. Salida = texto ortográfico en mayúsculas.
- Los "fonemas dichos" NO salen del audio: salen de fonemizar la transcripción con
  `phonemizer.phonemize(word, language="en-us", backend="espeak", strip=True)` (fallback
  `backend="festival"`), palabra por palabra, en
  `OpenPronounce/speech.py::get_phonemes_with_word_mapping` (l. 50). Devuelve
  `(phonemes, phoneme_to_word)` donde `phoneme_to_word[idx_fonema] = palabra`.
- Consecuencia: OpenPronounce hereda la normalización del ASR (si el hablante dice "gonna",
  wav2vec2-960h transcribe lo que oye como texto y el phonemizer lo canoniza). Para PhonoTrainer
  esto valida nuestra decisión de usar un modelo CTC de FONOS (`wav2vec2-lv-60-espeak-cv-ft`)
  para la vía "real": es la única forma de ver reducciones.

### 1.2 Alineación fonema-a-fonema (la función que buscábamos)

- **Función central**: `OpenPronounce/speech.py::compare_transcriptions` (l. 78–306).
  - Alineación por **edit-distance, no DTW**: `Levenshtein.opcodes(expected_phonemes,
    transcribed_phonemes)` (l. 108) → opcodes `equal/replace/delete/insert`.
  - Construye `alignment_map: list[set[int]]` (idx fonema esperado → idxs transcritos). En
    `replace` reparte el rango **proporcionalmente** (l. 114–130) para manejar mapeos 1-a-N
    ("I'm" 3 fonemas ↔ "I M" 4 fonemas).
  - Agrupa por palabra re-fonemizando cada palabra para conocer su nº de fonemas (l. 177–194)
    → ventanas de palabra sobre la secuencia plana de fonemas.
  - Umbral de error por palabra: `Levenshtein.distance(expected_seg, actual_seg) >
    len(expected_seg) * 0.4` (l. 253). Sin costos fonéticos: sustituir ð→d cuesta igual que p→s.
- **DTW existe pero es secundario y débil**:
  - `OpenPronounce/speech.py::compare_audio_with_text` (l. 350): `fastdtw` entre embeddings
    wav2vec2 del alumno y de un audio TTS de referencia (`gTTS`, generado por
    `OpenPronounce/audio.py::text2speech` l. 51) → un escalar de distancia global.
  - El "DTW de fonemas" del score global usa `get_phoneme_embeddings` (l. 74):
    `np.array([ord(c) for c in string_de_fonemas])` — DTW sobre codepoints Unicode. Sin ningún
    fundamento fonético. **No reutilizar**; es el argumento empírico para panphon.
  - `OpenPronounce/speech.py::align_sequences_dtw` (l. 308): fastdtw 1-D para igualar longitudes
    de dos curvas (solo para graficar).
- Score: `OpenPronounce/speech.py::compute_pronunciation_score` (l. 329): 0.4·DTW + 0.3·fonemas
  + 0.3·palabras, normalizado ad-hoc (capa "error del alumno", no la usamos).

### 1.3 Prosodia (el enfoque a adaptar con parselmouth)

Tres funciones en `OpenPronounce/speech.py`, librería **librosa** (no parselmouth):

| Función | Qué hace |
|---|---|
| `extract_f0` (l. 396) | `librosa.pyin(y, fmin=50, fmax=300)`; `np.nan_to_num` en frames sordos |
| `interpolate_f0` (l. 409) | interpolación lineal `np.interp` sobre los huecos sordos (máscara `f0 > 0`) para una curva continua |
| `extract_energy` (l. 402) | `librosa.feature.rms` + `MinMaxScaler(feature_range=(0, 250))` para co-graficar energía y F0 en la misma escala |

- Ventanas: defaults de librosa a 16 kHz → `frame_length=2048` (128 ms), `hop=512` (32 ms) tanto
  para pyin como para rms. Es decir: **un punto de F0/energía cada ~32 ms**.
- Salida: dos curvas por utterance (`prosody.f0`, `prosody.energy` en el JSON de
  `compare_audio_with_text`). **No** calcula stats por palabra, ni énfasis, ni contorno final —
  eso es capa nueva de PhonoTrainer.

---

## 2. whisperX (`references/whisperX/whisperx/`)

### 2.1 Forced alignment

- **Función central**: `whisperX/whisperx/alignment.py::align` (l. 117). Alinea el texto de cada
  segmento Whisper contra las emisiones CTC de un wav2vec2, a nivel de **CARÁCTER** (luego agrega
  a palabra). No hay fonemas en ningún punto.
- Modelo: `whisperX/whisperx/alignment.py::load_align_model` (l. 80). Para inglés
  `DEFAULT_ALIGN_MODELS_TORCH["en"] = "WAV2VEC2_ASR_BASE_960H"` (pipeline de torchaudio, l. 32–38);
  otros idiomas vía checkpoints HF (l. 40–77). El diccionario de alineación es
  `{caracter.lower(): id}` del vocab del tokenizer — letras, `|` como espacio.
- Mecánica (copiada del tutorial de forced alignment de torchaudio, así lo declara el comentario
  en l. 426–428):
  - `alignment.py::get_trellis` (l. 431): trellis CTC (quedarse en blank vs avanzar al token).
  - `alignment.py::backtrack` (l. 461): retro-trazado con probabilidad por frame.
  - `alignment.py::merge_repeats` (l. 514): colapsa frames consecutivos del mismo token →
    `Segment(label, start_frame, end_frame, score)`.
  - `alignment.py::merge_words` (l. 532): agrupa segments por separador `|`.
  - Conversión frames→segundos: `ratio = duration / (trellis.size(0) - 1)` (l. 302) dentro de la
    ventana del segmento. `torchaudio.functional.forced_align` implementa exactamente este trellis
    en nativo; no hace falta portar este código.

### 2.2 Manejo de segmentos y huecos (lo que sí copiamos)

- **Alineación local por ventana**: recorta el audio al segmento Whisper
  (`f1 = int(t1*SAMPLE_RATE); f2 = int(t2*SAMPLE_RATE)`, l. 248–252) y alinea solo ahí. Convierte
  un problema global en muchos locales y robustos — misma filosofía que nuestro diff por palabra.
- **Padding mínimo**: si la ventana tiene <400 muestras, pad a 400 (mínimo de wav2vec2, l. 254–258).
- **blank_id**: se localiza buscando `'[pad]'`/`'<pad>'` en el diccionario (l. 273–276) — con
  modelos HF el blank de CTC es el pad token, no siempre el índice 0. Aplica igual a
  wav2vec2-espeak.
- **Wildcard para tokens fuera de vocabulario** (l. 278–289): añade a la emisión una columna
  sintética = `max` por frame de los scores no-blank, y mapea ahí los chars desconocidos
  (dígitos, símbolos). Elegante para OOV; adaptable si algún fono canónico no existiera en el
  vocab del modelo.
- **Degradación elegante**: si un segmento no tiene chars alineables o `backtrack` devuelve
  `None`, conserva los timestamps originales del segmento y sigue (l. 236–244 y 294–297). Nunca
  aborta el pipeline por un segmento malo.
- **Interpolación de huecos**: palabras sin ningún carácter alineable reciben tiempos
  interpolados de sus vecinas — `whisperX/whisperx/utils.py::interpolate_nans` (l. 470;
  `interpolate(method='nearest').ffill().bfill()`).
- **VAD antes del ASR**: `whisperX/whisperx/vads/pyannote.py::Pyannote.merge_chunks` (l. 248)
  binariza y fusiona turnos de voz en chunks ≤30 s; el silencio nunca entra al modelo. (Nosotros
  cubrimos esto con `vad_filter=True` de faster-whisper.)

**Conclusión para PhonoTrainer**: whisperX es la referencia del *mecanismo* (trellis CTC por
ventana, blank=pad, interpolación de huecos, fallback) pero su *alfabeto* es ortográfico. No
sirve como aligner canónico de fonemas; sí como manual de manejo de casos borde.

---

## 3. Repos MDD: decodificación CTC y evaluación fonema-nivel

### 3.1 wav2vec2mdd (`references/wav2vec2mdd/`) — fairseq + Kaldi

- No trae código de inferencia propio. Receta (README, sección "Evaluating a CTC model"):
  `fairseq examples/speech_recognition/infer.py --w2l-decoder viterbi --lm-weight 0
  --criterion ctc --labels phn` → **decodificación Viterbi/greedy sin LM** = argmax por frame +
  colapso de repeticiones + eliminación de blank. Nadie usa beam search para fonos; greedy basta.
- `wav2vec2mdd/result.py::Result.align` (l. 63): genera los 3 textos del protocolo MDD por
  utterance — `ref.txt` (canónico), `annotation.txt` (lo que el humano anotó que se dijo),
  `hypo.txt` (predicción CTC) — filtrando `sil`/`sp`. Después, Kaldi:
  `align-text ark:ref.txt ark:hypo.txt | wer_per_utt_details.pl` para cada par
  (ref↔anno, anno↔hypo, ref↔hypo) y `ins_del_sub_cor_analysis.py` cuenta I/D/S/C.
- **PER = WER sobre strings de fonemas separados por espacio.** Ese es todo el formato.
- Preparación de datos: `wav2vec2mdd/l2_label.py::split_ref_err` (l. 203) parsea el tier de
  L2-ARCTIC con marcas `canónico,percibido,tipo`; `l2_label.py::get_phn` (l. 233) limpia dígitos
  de stress y sufijos (`` ` ``, `*`, `_`).
- **Tablas reutilizables**:
  - `wav2vec2mdd/phone39.table`: mapeo TIMIT 61→39/41 fonos (closures `bcl/dcl/...`→`sil`,
    `q`→None, `ix`→`ih`, `ax-h`→`ax`, `axr`→`er`, `ux`→`uw`…). Cuidado: la línea `sh zh` parece
    un bug del repo (debería ser `sh sh`); verificar antes de copiar.
  - `wav2vec2mdd/data/dict.phn.txt`: vocabulario CTC = 40 unidades ARPAbet en minúscula +
    `sil`, `sp`, `err`.

### 3.2 joint-apa-mdd-mtl (`references/joint-apa-mdd-mtl/`) — HF transformers (la receta que copiamos)

- **Tokenizer** — la línea más útil del repo,
  `joint-apa-mdd-mtl/auxiliary-phone-recognition/trainer_train.py` l. 191:
  ```python
  Wav2Vec2PhonemeCTCTokenizer(VOCAB, unk_token="[UNK]", pad_token="[PAD]",
                              phone_delimiter_token=" ", do_phonemize=False)
  ```
  Es la misma clase de tokenizer que usa `facebook/wav2vec2-lv-60-espeak-cv-ft`
  (`do_phonemize=False` porque sus labels ya son fonemas; en el modelo espeak,
  `do_phonemize=True` con backend espeak-ng fonemiza texto → mismo alfabeto que la salida CTC).
- **Decodificación CTC = greedy**, en tres sitios idénticos:
  - `auxiliary-phone-recognition/trainer_train.py::compute_wer` (l. 90–95) y
    `auxiliary-phone-recognition/trainer_test.py::compute_wer` (l. 89–99):
    `pred_ids = np.argmax(logits, -1)` → `processor.batch_decode(pred_ids)`.
  - `multi-task-learning/test/test.py` l. 119: `processor.batch_decode(torch.argmax(ctc_logits, -1))`.
  - Detalle clave: `batch_decode` con `group_tokens=True` (default) colapsa repeticiones y quita
    blank/pad; para las REFERENCIAS usan `group_tokens=False` (l. 95–96 de trainer_train) porque
    ahí las repeticiones son fonemas legítimos, no frames repetidos. Recordarlo en
    `phones_real.py`.
- **PER**: `evaluate.load("wer")` aplicado a fonemas espacio-separados
  (`multi-task-learning/test/test.py` l. 127–129 y 150, `per_metric.add_batch(...)`).
- **Evaluación 3-vías canónico/anotado/predicho** (el "diff" de MDD, nuestro diff con signo
  invertido):
  - `multi-task-learning/test/test.py` l. 103–105 y 144–148 escribe `PREDS_*`, `ANNOT_*`,
    `CANON_*` (una utterance por línea: `id\tfonemas`).
  - `multi-task-learning/test/kaldi-align.sh`: `align-text --special-symbol='***'` +
    `utils/scoring/wer_per_utt_details.pl` para CANON↔ANNOT, ANNOT↔PREDS, CANON↔PREDS.
  - `multi-task-learning/test/ins_del_sub_cor_analysis.py`: parsea las líneas `ref`/`hyp`/`op`
    y cuenta `I/D/S/C` → jerarquía True-Accept/False-Rejection/False-Accept/True-Rejection.
    PhonoTrainer hace lo mismo pero renombra cada op a fenómeno (S: ʌ→ə = vowel_reduction;
    D de /t/ final = t_deletion; …).
- **Vocab**: `joint-apa-mdd-mtl/vocab/vocab.json` — 49 tokens: ARPAbet mayúscula + unidades de
  error L2-ARCTIC (`ERR`, `AR`, `IR`, `DZ`, `TS`, `TR`, `DR`) + `[UNK]`/`[PAD]`; `" "` = id 0.
- **Mapeo TIMIT→39 en Python** (alternativa a phone39.table):
  `joint-apa-mdd-mtl/data/preprocess_datasets.py::preprocess_timit_phones` (l. 46): descarta
  `h#/epi/pau/q` y closures; mapea `em→m, el→l, en→n, nx→n, eng→ng, ux→uw, axr→er, ix→ih,
  ax→ah, hv→hh`… **y `dx→t`**: colapsa el flap en /t/. Es exactamente la normalización que
  PhonoTrainer NO debe aplicar — el flap ɾ es un fenómeno a detectar, no ruido. Buen recordatorio
  de que las tablas MDD borran lo que nosotros queremos medir.

---

## 4. faster-whisper (`references/faster-whisper/faster_whisper/transcribe.py`)

- **`WhisperModel.transcribe`** (l. 747): `word_timestamps: bool = False` (l. 778),
  `vad_filter: bool = False` (l. 782), `vad_parameters: dict | VadOptions | None` (l. 783).
- **`BatchedInferencePipeline.transcribe`** (l. 254): `word_timestamps=False` (l. 285) pero
  **`vad_filter=True` por defecto** (l. 289). Ojo al default distinto entre las dos clases;
  en PhonoTrainer pasamos ambos explícitos: `transcribe(wav, word_timestamps=True, vad_filter=True)`.
- Word timestamps: dataclass `Word` (l. 32: `start, end, word, probability`) rellenada por
  `WhisperModel.add_word_timestamps` (l. 1567) vía DTW sobre cross-attention.
- VAD: Silero; opciones en `faster_whisper/vad.py::VadOptions` (l. 15): `threshold=0.5`,
  `neg_threshold`, `min_speech_duration_ms`, `max_speech_duration_s`, etc.

---

## 5. Decisiones para PhonoTrainer

### (a) Alineación canónica: `torchaudio.functional.forced_align` sobre el MISMO modelo espeak

- Usamos **`torchaudio.functional.forced_align`** sobre las emisiones (log-softmax de logits) de
  **`facebook/wav2vec2-lv-60-espeak-cv-ft`** — el mismo modelo que produce los fonos reales.
- La secuencia canónica se fonemiza con **el propio tokenizer del modelo**
  (`Wav2Vec2PhonemeCTCTokenizer`, backend espeak-ng, `do_phonemize=True`), de modo que
  **canónico y real comparten exactamente el mismo alfabeto y los mismos ids** — cero tablas de
  conversión en el camino crítico, y un solo modelo grande en memoria (una sola pasada de
  inferencia por ventana sirve para ambas vías).
- El `blank` a pasar a `forced_align` es el id del pad token del tokenizer (lección de whisperX
  l. 273–276: en modelos HF blank = `<pad>`, no asumir 0).
- **`torchaudio.pipelines.MMS_FA` queda descartado**: su diccionario es de caracteres
  (ortografía romanizada), no de fonemas — mismo problema que el aligner de whisperX. Alinearía
  letras de "does" y no /d ʌ z/.
- **MFA queda descartado salvo que la calidad lo exija**: requiere entorno conda propio (contra
  el criterio pip-only del plan). Se reconsidera solo si la validación manual de Fase 3 muestra
  fronteras inaceptables.
- Del manejo de segmentos de whisperX copiamos: alineación por ventana (segmento/palabra del
  ASR), padding a ≥400 muestras, timestamps interpolados para huecos, y fallback a los tiempos
  del ASR si la alineación de una ventana falla.

### (b) Diff: Needleman-Wunsch por ventana de palabra con costos panphon

- Alineamiento global **Needleman-Wunsch** entre fonos reales y canónicos, pero **dentro de la
  ventana temporal de cada palabra** (ambas secuencias ya traen tiempos → el problema es local,
  como los segmentos de whisperX).
- Costos de sustitución = distancia de rasgos articulatorios de **panphon**
  (`panphon.distance.Distance().feature_edit_distance` o pesos derivados): ð↔d y ʌ↔ə baratos,
  p↔s caro. Justificación empírica: OpenPronounce usa `ord()`+DTW y Levenshtein sin pesos
  (§1.2) y eso no distingue una reducción natural de un error grosero.
- Las operaciones I/D/S del alineamiento se etiquetan como fenómenos (tabla de Fase 5 del plan)
  — es el pipeline `align-text` + `ins_del_sub_cor_analysis.py` de los repos MDD (§3) con la
  interpretación invertida: la desviación es fenómeno nativo a enseñar, no error.

### (c) Prosodia: parselmouth adaptando el enfoque de OpenPronounce

El enfoque exacto de OpenPronounce (§1.3) que conservamos, traducido a parselmouth:

1. **Curva de F0 acotada a banda de voz** — ellos: `librosa.pyin(fmin=50, fmax=300)`, un frame
   cada ~32 ms. Nosotros: `parselmouth.Sound(...).to_pitch(time_step=0.01, pitch_floor=75,
   pitch_ceiling=400)` (10 ms como pide el plan; floor/ceiling ajustables por hablante).
2. **Interpolación de huecos sordos** — su `interpolate_f0` (np.interp sobre frames con f0>0)
   se replica igual sobre `pitch.selected_array['frequency']` (0 = sordo) para tener contorno
   continuo graficable.
3. **Energía co-escalada con F0** — su `extract_energy` (RMS + MinMax a [0,250]) se replica con
   `snd.to_intensity()` + reescalado Min-Max, para superponer ambas curvas en el report HTML.

Lo que OpenPronounce NO hace y añadimos en `prosody.py`: stats por segmento (media/rango F0),
palabra enfatizada (pico conjunto F0+intensidad dentro del segmento) y contorno final
rising/falling (pendiente de F0 en el último tramo sonoro).

---

## 6. Mejoras post-validación externa (julio 2026)

Motivadas por la revisión palabra-por-palabra de `out/analysis.json` del clip de
2 Broke Girls (42.7 s). Detalle de implementación en `ipa_maps.py`, `phones_real.py`,
`diff.py` y `phenomena.py`; tests sintéticos por cambio en `tests/`.

### 6.1 Referencia de cada regla (RULE_REFERENCE, Mejora 4)

El canónico espeak en-us **ya incorpora procesos nativos**: fonemiza "better" como
`bɛɾɚ` (flap incluido) y "landed" con `ᵻ`. Eso obliga a ser explícito sobre contra
qué se define cada fenómeno. `phenomena.RULE_REFERENCE` lo registra:

| Regla | Referencia | Por qué |
|---|---|---|
| flapping | **dict** | espeak pre-flapea: un match ɾ↔ɾ solo es flapping si la forma de cita CMUdict tiene /T/ o /D/ (si no, ɾ sería fonema propio de la palabra y no habría proceso). Detección: match/sub sobre aligned + veto por `dict_arpabet`. |
| el resto | **aligned** | El canónico forzado en tiempo comparte alfabeto y pasada acústica con lo real; espeak en-us da formas fuertes por palabra (fonemiza palabra a palabra, sin contexto), así que la desviación real-vs-aligned ES el fenómeno (reducción, elisión, th-stopping, palatalización, linking…). |

`dict` = forma de cita (CMUdict/g2p_en, mostrada como `/…/` en el reporte);
`aligned` = canónico espeak con tiempos (mostrado como `[…]`).

### 6.2 Saneamiento del inventario (Mejora 0)

El checkpoint es multilingüe (392 tokens): en audio inglés filtra dígitos de tono
de mandarín (`ai5`, `ɑu5`), aspiradas (`kʰ`/`kh`), palatalizadas (`nʲ`), SAMPA crudo
(`dZ`, `tS`) y `ᵻ`. `ipa_maps.normalize_espeak` lleva todo token a un inventario
inglés cerrado (`ENGLISH_INVENTORY`, 64 símbolos): tabla explícita + limpieza
(tonos/diacríticos/aspiración) + vecino más cercano por rasgos panphon con warning.
El token crudo se conserva SIEMPRE en `raw_phone`. Cobertura verificada por test
sobre el vocabulario completo del tokenizer. Detalle no obvio: los monoptongos
crudos `o`/`e`/`a` mapean a `ɔ`/`ɛ`/`æ` (monoptongo vecino), NUNCA al diptongo
inglés — mapearlos a `oʊ`/`eɪ` ocultaría la monoptongación que queremos detectar.

### 6.3 Atracción fonética (Mejora 1)

`phones_real.attract_to_canonical` corre tras el reconocimiento y antes del diff:
si un fono real no coincide con su contraparte canónica, la distancia panphon
escalada es ≤ 1.3 y el par NO es variación nativa, se sustituye por el canónico
(`attracted: true`, crudo en `raw_phone`). La contraparte sale del alineamiento
NW (con solape temporal como condición extra), NO del solape máximo: la primera
versión por solape absorbía deleciones reales ("don't"→[doʊn] se volvía [doʊt],
matando t_deletion y el dunno) — cazado comparando con/sin atracción. Protegidos:
`NATIVE_SHIFTS` (ampliada con ʊɹ→ɔːɹ/ɔɹ/oːɹ/ɚ/ə, la variación "yor/yer" de *your*
×5 en el clip), reducciones a schwa/ɪ, candidatos a monoptongación (diptongo
canónico + vocal simple real) y flaps/glotales. j→t (costo 1.5, tope C↔C) va en
`EXTRA_ATTRACT` explícita. Umbral calibrado con pares reales: atraer b→v 0.42,
n→l 0.67, l→d 1.17, h→f 1.25; nunca atraer t→ɾ 0.25, ð→d 0.30, ʌ→ə 0.08.
Riesgo aceptado y documentado: θ→f (th-fronting, 0.46) se atraería — no está en
nuestro inventario de etiquetas; si algún día se etiqueta, añadirlo a NATIVE_SHIFTS.
De paso se corrigió un bug en `phone_cost`: el lookup en NATIVE_SHIFTS ocurría tras
`normalize_for_panphon`, así que pares con ɚ (→ə) jamás matcheaban; ahora se busca
primero el par crudo.

### 6.4 Números de la validación sobre el clip (42.7 s, 2 Broke Girls)

Comparación `out/` (con atracción) vs `out_noattr/` (--no-attraction), misma
transcripción (182 palabras, 545 fonos reales):

- **Mejora 0**: 13 fonos crudos saneados al inventario (ᵻ, monoptongos crudos
  a/e/o, tonos/aspiradas residuales).
- **Mejora 1**: 36 fonos atraídos al canónico (~7% de los fonos reales);
  p.ej. blowing [vloʊɪŋ]→[bloʊɪŋ], know [lɔ]→[nɔ], him [fæ]→[hɪ].
- **Cero etiquetas perdidas por atracción** (diff palabra a palabra con/sin):
  contraction_lex 3=3 (dunno intacto), t_deletion 17=17, linking 30=30,
  flapping 6=6, vowel_reduction 13=13, monophthongization 6=6,
  elision_syllable 10=10, word_elision 6=6.
  La PRIMERA versión (emparejamiento por solape máximo) sí borraba 4 etiquetas
  (don't/and absorbían su t/d elidida); se rediseñó a emparejamiento NW (§6.3)
  antes de dar por buena la mejora.
- **Mejora 2**: 6 monoptongaciones directas (I→æ, know→nɔ, cupcakes eɪ→ɪ,
  I'll→æl/ɑːl, kind→kæd) + 3 casos eɪ→ɐ que van a vowel_reduction por la regla
  de precedencia ≈ los ~9 observados en la validación externa.
- **Mejora 3**: 6 word_elision (6 palabras low_confidence en total):
  3 con realized vacío (And, to, I'm) y 3 con extensión <30% (that., you, downs).
  elision_syllable bajó de 14 (pre-mejoras) a 10. El criterio se corrigió de
  suma-de-spans a extensión temporal: los spans CTC son picos de ~20-40 ms y la
  suma marcaba 32 falsos positivos.
- Umbral de atracción 1.3 sin ajustes tras el rediseño: no borra fenómenos.
