# Third-party notices — PhonoTrainer

PhonoTrainer se distribuye bajo **GPL-3.0-or-later** (ver [`LICENSE`](LICENSE)).
Este archivo acredita todo lo que el proyecto usa, referencia o descarga, y deja
explícito qué se redistribuye y qué no.

**Este repositorio no redistribuye código, pesos ni corpus de terceros.** Todo lo
que aparece abajo se instala con `pip`/`npm` o se descarga en el primer análisis
a la caché del usuario (`~/.cache/huggingface`, `~/nltk_data`). Por eso la
obligación aquí es *informar*, no incluir los textos de licencia — salvo los del
bundle de la interfaz, que sí viaja compilado (ver §2).

---

## 1. Por qué GPL-3.0-or-later

No es una preferencia: es la única licencia coherente con lo que el programa
enlaza en su camino principal.

| Dependencia | Licencia | Dónde entra |
|---|---|---|
| `phonemizer` 3.3.0 | **GPL-3.0-or-later** | El tokenizer del modelo (`Wav2Vec2PhonemeCTCTokenizer`, `phonemizer_backend="espeak"`) fonemiza **cada palabra canónica** en `align_canonical.py`. No aparece en ningún `import` nuestro, pero se carga siempre. |
| `praat-parselmouth` 0.4.7 | **GPL-3.0-or-later** | `import parselmouth` directo en `prosody.py`: F0, intensidad y contorno. |
| `espeak-ng` (sistema) | GPL-3.0-only AND GPL-3.0-or-later AND Apache-2.0 AND BSD-2-Clause AND Unicode-DFS-2016 AND CC-BY-SA-3.0 | `phonemizer` lo carga con `dlopen`. Binario del sistema, no se redistribuye. |

Ambas bibliotecas se importan en el mismo proceso, así que el trabajo combinado
que se distribuye es GPL-3.0. Una licencia permisiva (MIT/Apache) sería
engañosa: nadie podría redistribuir el resultado bajo esos términos.

Para hacer PhonoTrainer permisivo habría que eliminar las dos rutas —fonemización
canónica y prosodia— y sustituirlas por equivalentes no copyleft. Es trabajo de
ingeniería real, no un cambio de metadatos.

**Compatibilidad verificada.** `distance` 0.1.3 (dependencia declarada por
`g2p-en`) es **GPL-2.0-only**, que sería incompatible con GPL-3.0 — pero **no lo
importa nadie**: ni `g2p_en` ni ningún otro paquete del entorno. Al no combinarse
nunca con el programa, es mera agregación y no afecta. Si algún día algo empieza
a importarlo, esta decisión hay que rehacerla.

---

## 2. Interfaz web — sí se redistribuye compilada

`web/dist/` no está versionado, pero el backend lo sirve, así que el bundle se
distribuye a quien despliegue la aplicación. Estos tres paquetes acaban dentro:

| Paquete | Versión | Licencia | Copyright |
|---|---|---|---|
| `react` | 19.2.8 | MIT | Copyright (c) Meta Platforms, Inc. and affiliates |
| `react-dom` | 19.2.8 | MIT | Copyright (c) Meta Platforms, Inc. and affiliates |
| `scheduler` | 0.27.0 | MIT | Copyright (c) Meta Platforms, Inc. and affiliates |

El minificador borra los banners `@license`, así que `web/vite.config.ts` repone
la nota MIT en la cabecera del bundle (plugin `phonotrainer:license-banner`).
Texto completo: <https://github.com/facebook/react/blob/main/LICENSE>.

El resto de `node_modules` (vite, vitest, typescript, testing-library, jsdom…)
es solo de desarrollo: no se compila ni se distribuye. `lightningcss` (MPL-2.0)
entra como transitiva de vite pero no se activa (`css.transformer` sin definir)
y aporta cero bytes al bundle.

Las tipografías IPA (`Charis SIL`, `Doulos SIL`, `Gentium Plus`, `DejaVu Sans`)
se nombran por familia en CSS; **no se incrustan**, así que no generan obligación.

---

## 3. Dependencias Python (se instalan con pip, no se redistribuyen)

| Paquete | Versión | Licencia | Titular |
|---|---|---|---|
| faster-whisper | 1.2.1 | MIT | Copyright (c) 2023 SYSTRAN |
| CTranslate2 | 4.8.1 | MIT | OpenNMT |
| transformers | 5.14.1 | Apache-2.0 | Copyright 2018– The Hugging Face team |
| torch | 2.13.0 | BSD-3-Clause | Meta Platforms / PyTorch contributors |
| torchaudio | 2.11.0 | BSD-2-Clause | Copyright (c) 2017 Facebook Inc. (Soumith Chintala) |
| **phonemizer** | 3.3.0 | **GPL-3.0-or-later** | Mathieu Bernard, Hadrien Titeux (CNRS / bootphon) |
| **praat-parselmouth** | 0.4.7 | **GPL-3.0-or-later** | Yannick Jadoul — envoltorio de Praat (Boersma & Weenink) |
| g2p-en | 2.1.0 | Apache-2.0 | Kyubyong Park & Jongseok Kim |
| panphon | 0.22.2 | MIT | Copyright (c) 2015 Carnegie Mellon University (David R. Mortensen) |
| nltk | 3.10.0 | Apache-2.0 | NLTK Project |
| numpy | 2.5.1 | BSD-3-Clause | NumPy Developers |
| soundfile | 0.14.0 | BSD-3-Clause (su *wheel* incrusta libsndfile, LGPL-2.1) | Copyright (c) 2013 Bastian Bechtold |
| click | 8.4.2 | BSD-3-Clause | Pallets |
| rich | 15.0.0 | MIT | Will McGugan |
| fastapi | 0.140.0 | MIT | Sebastián Ramírez |
| uvicorn | 0.51.0 | BSD-3-Clause | Encode |
| python-multipart | 0.0.32 | Apache-2.0 | Andrew Dunham |
| yt-dlp | 2026.7.4 | Unlicense | yt-dlp contributors |

Transitivas que también piden aviso: `huggingface_hub`, `safetensors` y
`tokenizers` (Apache-2.0, Hugging Face), `certifi` (MPL-2.0) y `tqdm`
(MPL-2.0 AND MIT). MPL-2.0 es copyleft por archivo y su §3.3 permite
expresamente combinar con GPL-3.0; como no modificamos ninguno de sus archivos,
solo corresponde acreditarlos.

`praat-parselmouth` es un envoltorio de **Praat**, de Paul Boersma y David
Weenink (Universidad de Ámsterdam); el envoltorio es de Yannick Jadoul.

`panphon` pide cita académica: Mortensen, Littell, Bharadwaj, Goyal, Dyer,
Levin — *PanPhon: A Resource for Mapping IPA Segments to Articulatory Feature
Vectors*, COLING 2016. Sus tablas (`ipa_all.csv`) son el sustrato de todos los
costes del diff, así que la cita es justa además de barata.

---

## 4. Modelos y datos lingüísticos (se descargan en el primer uso)

Nada de esto viaja en el repositorio.

| Recurso | Licencia | Titular | Uso |
|---|---|---|---|
| `Systran/faster-whisper-small` | MIT | SYSTRAN (conversión CTranslate2) | ASR con timestamps por palabra |
| `openai/whisper-small` (pesos originales) | Apache-2.0 | OpenAI | Base de la conversión anterior |
| `facebook/wav2vec2-lv-60-espeak-cv-ft` | Apache-2.0 | Meta AI — Xu, Baevski, Auli ([arXiv:2109.11680](https://arxiv.org/abs/2109.11680)) | Fonos reales (CTC) **y** alineación forzada del canónico, en la misma pasada |
| CMU Pronouncing Dictionary `cmudict.0.7a` (vía NLTK) | BSD-2-Clause | Copyright (C) 1993-2008 Carnegie Mellon University | Forma de cita y detección de OOV |
| NLTK `averaged_perceptron_tagger(_eng)` | MIT | NLTK Project (tagger original de Matthew Honnibal) | POS para homógrafos en `g2p_en` |

**Cautelas del modelo Whisper**, que su ficha pide trasladar: no está pensado
para transcribir a personas sin su consentimiento, ni para decisiones de alto
riesgo, ni para inferir atributos de quien habla. Quien use PhonoTrainer es
responsable de tener derecho a procesar el material que le da.

**No usado a propósito:** `torchaudio.pipelines.MMS_FA` es **CC-BY-NC 4.0**
(no comercial). Alinea caracteres, no fonemas, así que se descartó por razones
técnicas — pero adoptarlo más adelante impondría una restricción no comercial a
todos los usuarios. No sustituir el aligner por él.

---

## 5. Requisitos del sistema (procesos externos, no enlazados)

| Programa | Licencia | Uso |
|---|---|---|
| `ffmpeg` | GPL-3.0-or-later (según empaquetado) | Extracción y remuxado de audio/vídeo (`audio.py`, `download.py`) |
| `espeak-ng` | GPL-3.0 y otras (ver §1) | Backend de fonemización de `phonemizer` |

Los instala el usuario y no se redistribuyen aquí, pero la diferencia entre los
dos importa y conviene no "optimizarla" luego:

- **ffmpeg se invoca como proceso aparte** (`subprocess.run` con una lista de
  argumentos, `audio.py`). Cruzar la frontera de proceso es mera agregación: no
  crea obra combinada. El ffmpeg de esta máquina está compilado con
  `--enable-gpl` y `--enable-libfdk-aac`, cuya licencia **no** es compatible con
  GPL — razón de más para no enlazarlo nunca en proceso ni empaquetar un binario
  suyo en una release.
- **espeak-ng se carga con `dlopen`** dentro del mismo proceso, vía `ctypes`
  desde `phonemizer`. Eso sí es enlace, y es parte de por qué el resultado es
  GPL-3.0.

---

## 6. Proyectos de referencia — estudiados, **no** copiados

`references/` contiene clones de cinco repositorios que sirvieron para decidir la
arquitectura (el análisis está en [`references/NOTES.md`](references/NOTES.md)).
**Están en `.gitignore` y no se distribuyen.**

Se verificó por contenido, no por nombre de archivo: de las ~5.900 líneas largas
del código versionado, solo 5 coinciden con alguna de las ~6.400 líneas de los
clones, y las cinco son `import` de la stdlib o llamadas documentadas a
dependencias declaradas. Ninguna función, tabla o vocabulario de esos repos está
en `phonotrainer/`. Lo que se tomó son **ideas de diseño**, reimplementadas con
otras bibliotecas y otros parámetros — no expresión protegida. Por eso PhonoTrainer
no hereda de ellos ninguna obligación de aviso; el crédito de abajo es voluntario
y merecido.

| Proyecto | Commit consultado | Licencia | Qué aportó |
|---|---|---|---|
| [whisperX](https://github.com/m-bain/whisperX) | `2cfd7b7` | BSD-2-Clause · Copyright (c) 2024, Max Bain | Alinear dentro de la ventana de cada segmento; `blank` = token `<pad>`; interpolar huecos y degradar sin abortar |
| [OpenPronounce](https://github.com/Halleck45/OpenPronounce) | `759ab4c` | MIT · Copyright (c) 2025 Jean-François Lépine | Enfoque de prosodia: F0 acotada, interpolación de tramos sordos, energía coescalada |
| [faster-whisper](https://github.com/SYSTRAN/faster-whisper) | `ed9a06c` | MIT · Copyright (c) 2023 SYSTRAN | Además de dependencia: defaults de `word_timestamps` / `vad_filter` |
| [joint-apa-mdd-mtl](https://github.com/rhss10/joint-apa-mdd-mtl) | `5fbc315` | MIT · Copyright 2023 Hyungshin Ryu | Receta de decodificación CTC de fonemas y evaluación a nivel de fonema |
| [wav2vec2mdd](https://github.com/vocaliodmiku/wav2vec2mdd) | `760ccca` | **SIN LICENCIA** (todos los derechos reservados) | Solo lectura: protocolo de evaluación MDD de tres vías |

> ⚠️ **wav2vec2mdd no tiene licencia** — ni ahora ni en ningún punto de su
> historial. Se comprobó que no se copió nada de él (ni `phone39.table`, ni
> `dict.phn.txt`, ni ninguno de sus identificadores). Sus datos derivan además de
> L2-ARCTIC y TIMIT, con sus propias restricciones de corpus. **Es material de
> lectura: nunca copiar código, tablas ni datos de ahí a `phonotrainer/`.**

`references/NOTES.md` cita fragmentos cortos de estos repos (unas 5 líneas en
total, la más larga ~120 caracteres) con fines de comentario crítico e
identificación. Cada repositorio se rige por su propia licencia.
