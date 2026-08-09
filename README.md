# PhonoTrainer

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3%2B-blue.svg)](LICENSE)

Analizador fonético de habla nativa en inglés. Dado un **video o audio**, produce una
línea de tiempo alineada con: transcripción, fonos realmente pronunciados, pronunciación
canónica alineada en tiempo, diff etiquetado de fenómenos de *connected speech*
(wanna, gotcha, flapping, schwa…) y prosodia (F0, énfasis, contorno).
Ver [`docs/PLAN.md`](docs/PLAN.md) para el plan completo y
[`references/NOTES.md`](references/NOTES.md) para las decisiones de la Fase 0.

La pregunta que contesta no es "¿lo pronuncias bien?" sino **"¿qué hace de verdad
un nativo aquí, y en qué se aparta de la forma de diccionario?"**. Las
herramientas de detección de errores (MDD) miden esa desviación para corregir a
un alumno; PhonoTrainer la mide con el signo invertido, para enseñar el fenómeno.

> **English summary.** PhonoTrainer analyses native English speech. Given a video
> or audio file it produces a time-aligned view of what was *actually*
> pronounced (wav2vec2 phone CTC) against the time-aligned canonical
> pronunciation (forced alignment over the same acoustic pass), labels the
> differences as connected-speech phenomena (flapping, t-deletion, vowel
> reduction, linking, palatalisation…), and adds prosody (F0, stress, final
> contour). CLI + local React web UI. Docs and code comments are in Spanish;
> the subject matter is English phonetics.

## Uso aceptable

PhonoTrainer es una herramienta de **análisis lingüístico personal** sobre
material al que ya tienes derecho a acceder.

- **No se distribuye ningún material con copyright** con este proyecto: ni
  audio, ni vídeo, ni transcripciones derivadas. Los tests usan fixtures 100 %
  sintéticos (tonos de numpy y media generada con ffmpeg).
- La descarga desde plataformas externas (`yt-dlp`) **puede infringir sus
  términos de servicio**, y esa responsabilidad es de quien la usa. Es una vía
  de entrada más, no la función del programa: lo normal es apuntarlo a archivos
  que ya tienes.
- El flujo previsto son **fragmentos cortos para estudio privado** (cita / *fair
  use*), no archivar obras completas ni redistribuirlas.
- El modelo Whisper que hay debajo pide explícitamente no transcribir a personas
  **sin su consentimiento**, no usarlo para decisiones de alto riesgo y no
  inferir atributos de quien habla. Se traslada tal cual.

## Interfaz web (recomendado)

```bash
make setup          # una vez: dependencias de Python + npm
make ui             # compila la SPA y abre http://127.0.0.1:8000
```

Desde ahí: arrastrar un video/audio (o elegirlo por ruta, o importar un `out/`
que ya exista), seguir el análisis en vivo y explorar el resultado con el audio
sincronizado. Al pulsar una palabra se oye y se compara **fono a fono en tres
filas**: diccionario (forma de cita), canónico alineado y lo realmente
pronunciado. Esa fila de diccionario es la que destapa procesos que espeak-ng ya
trae incorporados —el canónico de *better* es [bɛɾɚ], con flap—; y en los
fenómenos de frontera (linking, palatalización, h muda) la comparación y la
reproducción se extienden hasta la palabra siguiente, que es donde ocurren.

Cada fenómeno viene definido con un ejemplo junto a su etiqueta, cada segmento
puede desplegar su transcripción fonética completa (real vs canónica) y en las
fronteras se muestra el hueco medido en ms, que es lo que distingue un enlace
(~20 ms) de una frontera normal (~60 ms).

Además: filtro por fenómeno, buscador de palabras, bucle, velocidad 0.5×, video
sincronizado y modo de revisión humana que guarda el mismo `review.json` que la
CLI. Los atajos están dentro (botón `?`).

Por seguridad la interfaz solo abre archivos bajo `$HOME` y el directorio de
trabajo; para un disco externo, `phonotrainer ui --allow-dir /mnt/videos`.

> ⚠️ **El servidor no tiene autenticación de ningún tipo.** Escucha en
> `127.0.0.1` a propósito. `--host 0.0.0.0` publica en la red local un navegador
> de archivos de tu `$HOME` que cualquiera puede leer sin credenciales: no lo
> expongas nunca a una interfaz pública.

## Material: de YouTube al análisis

```bash
phonotrainer download "https://youtu.be/…"                 # → downloads/
phonotrainer analyze "https://youtu.be/…" -o out/          # descarga y analiza de un tirón
phonotrainer analyze "https://youtu.be/…" --audio-only     # sin vídeo, más rápido
```

Desde la interfaz basta con pegar la URL. La descarga (yt-dlp, vídeo ≤720p
remuxado con ffmpeg) es una etapa más de la barra de progreso, y el archivo se
reutiliza si ya está: reanalizar la misma URL no vuelve a bajar nada.

## Corpus: todo lo analizado, junto

Cada análisis terminado (o importado) se indexa en SQLite. Eso contesta lo que
un `analysis.json` suelto no puede:

```bash
phonotrainer corpus                        # cuántos fenómenos llevas y en cuántos vídeos
phonotrainer corpus -p flapping            # todas sus apariciones, la más divergente primero
phonotrainer corpus -w to                  # cómo se ha pronunciado "to" en todo el corpus
```

En la interfaz, la pestaña **Corpus** hace lo mismo y cada aparición abre su
análisis en esa palabra exacta.

## Uso desde la CLI

```bash
source .venv/bin/activate            # venv creado con: uv venv --python 3.12 .venv
phonotrainer analyze episodio.webm -o out/          # video
phonotrainer analyze entrevista.mp3 -o out/          # o solo audio
phonotrainer ui --import-dir out/                   # abrir ese resultado en la interfaz
```

Salidas en `out/`: `audio.wav`, `transcript.json`, `canonical.json`,
`phones_real.json`, `analysis.json` y `report.html` (autocontenido, con modo oscuro).

Opciones: `--whisper-model tiny|base|small|medium` (default `small`),
`--language en`, `--no-attraction` (desactiva la atracción fonética, para
comparar salidas). `--phone-engine` acepta `wav2vec2` (el único implementado, y
el default) y `allosaurus`, que está reservado como punto de extensión y hoy
falla con `NotImplementedError`.

Validación humana muestreada (prioriza palabras atraídas/baja confianza/diff alto):

```bash
phonotrainer review out/analysis.json -n 20 --seed 48   # → out/review.json
```

## Setup desde cero

Dos programas del sistema que `pip` no puede instalar. **ffmpeg** se invoca como
proceso aparte (extraer y remuxar audio); **espeak-ng** lo carga `phonemizer`
para fonemizar el canónico. Sin ellos el pipeline no arranca.

| Sistema | Comando |
|---|---|
| Fedora / RHEL | `sudo dnf install -y ffmpeg espeak-ng` |
| Debian / Ubuntu | `sudo apt install -y ffmpeg espeak-ng` |
| macOS (Homebrew) | `brew install ffmpeg espeak-ng` |
| Windows | Vía WSL2 con las órdenes de Debian/Ubuntu |

Los dos son software libre con licencia propia (GPL) y **no se distribuyen con
este proyecto**: los instalas tú.

```bash
uv venv --python 3.12 .venv
uv pip install --python .venv/bin/python torch torchaudio --index-url https://download.pytorch.org/whl/cpu
uv pip install --python .venv/bin/python -r requirements.txt -e .
.venv/bin/python scripts/download_models.py   # ~1.8 GB de modelos (una vez)
```

## Qué genera la herramienta (y qué no se versiona)

`downloads/` (vídeos bajados), `data/phonotrainer.db` (el corpus), `workspace/`
(los análisis de la interfaz) y `out*/` están en `.gitignore`: **cada clon
empieza limpio** y todo se regenera. El corpus es un índice derivado —se puede
borrar y reconstruir reanalizando o reimportando.

## Arquitectura de la interfaz

- **`phonotrainer/jobs.py`**: cada análisis es un *job* que corre en un hilo (de
  uno en uno), publica progreso y sobrevive a un reinicio (`workspace/<id>/job.json`).
  Cada cambio se anuncia a los observadores (`subscribe`), que es de donde come el
  WebSocket. Los archivos locales se referencian sin copiarse; los `out/` externos
  se importan.
- **`phonotrainer/server.py`**: API REST local (FastAPI) + servido de la SPA y del
  audio con Range. El estado de los análisis viaja empujado por WebSocket
  (`/ws/jobs`: snapshot al conectar y, tras cada cambio, el job completo y fresco,
  así que aplicar eventos es idempotente) —la interfaz no sondea `/api/jobs`.
  `/api/reference` publica la taxonomía de fenómenos, así que la UI no mantiene una
  copia de las etiquetas.
- **`phonotrainer/download.py`**: yt-dlp con una costura (`ydl_factory`) para
  que los tests no salgan a la red; el progreso se publica como el del pipeline.
- **`phonotrainer/db.py`**: el corpus. Tres tablas (análisis, palabras,
  fenómenos) y consultas entre análisis; se alimenta solo al terminar o importar.
- **`web/`**: React + TypeScript (Vite). Un solo `<audio>` gobierna la app; el
  tiempo se publica por un store externo (`player/clock.ts`) para no re-renderizar
  la transcripción 60 veces por segundo. La lista de análisis y sus logs llegan
  por otro store externo (`jobs/channel.ts`), un WebSocket con reconexión que
  comparten todos los componentes vía `JobsProvider`. Los colores son los mismos
  4 slots categóricos validados que usa `report.html`.

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
make test                                   # pytest + vitest
.venv/bin/python -m pytest tests/ -q        # solo backend
cd web && npm test                          # solo interfaz
```

Fixtures 100 % sintéticos (tonos numpy + media generada con ffmpeg): no hay audio
con copyright en el repo; los episodios se procesan solo localmente. Los tests del
servidor sustituyen el pipeline por un doble (`tests/conftest.py::fake_analyze`),
así que corren en segundos y sin cargar modelos.

## Licencia

**GPL-3.0-or-later** ([`LICENSE`](LICENSE)).

No es una preferencia estética: el camino principal del programa carga **en el
mismo proceso** tres cadenas de código copyleft, y eso fija la licencia de la
obra combinada.

- **`phonemizer`** (+ `espeak-ng`, cargado con `dlopen`) — el tokenizer del
  modelo fonemiza **cada palabra canónica** en `align_canonical.py`. Es
  invisible en los `import` del proyecto, pero está siempre ahí.
- **`praat-parselmouth`** — envoltorio de Praat, se carga en `prosody.py` y el
  pipeline lo instancia siempre.
- **`av` (PyAV)**, que arrastra `faster-whisper` al importarse: su *wheel* trae
  un FFmpeg propio con **libx264 y libx265** (GPL-2.0-or-later) que se enlazan
  en proceso. Distinto del `ffmpeg` del sistema, que sí se invoca como proceso
  aparte y por eso no cuenta.

Publicar esto como MIT o Apache sería engañoso: nadie podría redistribuir el
resultado bajo esos términos. El desglose completo —qué se descarga, qué se
redistribuye y qué no, y qué haría falta para volverlo permisivo— está en
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

## Créditos

No se copió código de ningún proyecto de referencia (se verificó por contenido,
no por nombre de archivo: 5 líneas coincidentes de ~5.900, todas `import` de la
stdlib). Lo que se tomó son ideas, y se agradecen igual:

- **[whisperX](https://github.com/m-bain/whisperX)** (BSD-2-Clause, Max Bain) —
  alinear dentro de la ventana de cada segmento, `blank` = token `<pad>`,
  interpolar huecos y degradar sin abortar.
- **[OpenPronounce](https://github.com/Halleck45/OpenPronounce)** (MIT,
  Jean-François Lépine) — el enfoque de prosodia: F0 acotada, interpolación de
  tramos sordos, energía coescalada.
- **[faster-whisper](https://github.com/SYSTRAN/faster-whisper)** (MIT, SYSTRAN)
  y **[joint-apa-mdd-mtl](https://github.com/rhss10/joint-apa-mdd-mtl)** (MIT,
  Hyungshin Ryu) — ASR con timestamps y la receta de decodificación CTC de fonemas.
- **panphon** (Mortensen et al., *COLING 2016*) — los rasgos articulatorios que
  dan sentido a los costes del diff.
