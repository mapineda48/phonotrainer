"""Corpus: índice SQLite de todo lo que se ha analizado.

Cada `analysis.json` vive en su directorio y contesta bien a «qué pasa en este
vídeo». Lo que no contesta es «enséñame todos los flapping que llevo vistos» o
«¿cómo se ha pronunciado *to* en los seis episodios?». Para eso está esta tabla:
al terminar (o importar) un análisis, sus palabras y fenómenos se vuelcan aquí y
quedan consultables entre análisis.

La base de datos es derivada y desechable: se puede borrar y reconstruir
reindexando los `analysis.json`. Por eso `data/` está en .gitignore y cada clon
del proyecto empieza con el corpus vacío.
"""

from __future__ import annotations

import json
import re
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_DB = Path("data/phonotrainer.db")
SCHEMA_VERSION = 3
# Por debajo de esto la "palabra" es un pico de CTC suelto, no algo que se pueda
# oír ni juzgar: sigue en el corpus, pero no encabeza la lista.
MIN_AUDIBLE = 0.06   # s
# Palabras iniciales que forman la huella del material. Son pocas a propósito:
# un recorte del mismo vídeo debe compartirla, y que dos grabaciones distintas
# empiecen con las mismas 4 palabras EN LOS MISMOS INSTANTES no pasa.
_MATERIAL_WORDS = 4

_SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS analyses (
    -- Identidad estable del análisis: el directorio donde vive su analysis.json.
    -- Si fuera el id del job, el mismo out/ analizado por la CLI e importado
    -- luego en la interfaz contaría dos veces.
    id          TEXT PRIMARY KEY,
    job_id      TEXT,                    -- el job de la interfaz, si lo hay
    -- Huella del material: las primeras palabras y sus tiempos. Dos análisis
    -- del mismo audio (con y sin atracción, o un recorte) la comparten, y así
    -- el corpus puede decir cuántas grabaciones distintas hay de verdad.
    material    TEXT,
    source      TEXT NOT NULL,
    result_dir  TEXT,
    duration    REAL,
    language    TEXT,
    attraction  INTEGER,
    asr_model   TEXT,
    phone_model TEXT,
    segments    INTEGER NOT NULL DEFAULT 0,
    words       INTEGER NOT NULL DEFAULT 0,
    indexed_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS words (
    analysis_id      TEXT    NOT NULL,
    segment          INTEGER NOT NULL,
    word_idx         INTEGER NOT NULL,
    word             TEXT    NOT NULL,
    word_key         TEXT    NOT NULL,   -- minúsculas y sin puntuación, para buscar
    start            REAL    NOT NULL,
    end              REAL    NOT NULL,
    dict_ipa         TEXT,
    canonical_ipa    TEXT,
    realized_ipa     TEXT,
    realized_raw_ipa TEXT,
    diff_cost        REAL,
    attracted_count  INTEGER,
    low_confidence   INTEGER,
    oov              INTEGER,
    lexical_form     TEXT,
    PRIMARY KEY (analysis_id, segment, word_idx)
);

CREATE TABLE IF NOT EXISTS phenomena (
    analysis_id TEXT    NOT NULL,
    segment     INTEGER NOT NULL,
    word_idx    INTEGER NOT NULL,
    phenomenon  TEXT    NOT NULL,
    PRIMARY KEY (analysis_id, segment, word_idx, phenomenon)
);

CREATE INDEX IF NOT EXISTS idx_words_key   ON words (word_key);
CREATE INDEX IF NOT EXISTS idx_phen_name   ON phenomena (phenomenon);
CREATE INDEX IF NOT EXISTS idx_phen_word   ON phenomena (analysis_id, segment, word_idx);
CREATE INDEX IF NOT EXISTS idx_analyses_job ON analyses (job_id);
"""

_CLEAN_RE = re.compile(r"[^\w]+", re.UNICODE)
# Un esquema distinto o una base ilegible se rehacen; lo demás (bloqueos,
# permisos) es del entorno y no debe costarle el corpus a nadie.
_BROKEN = ("not a database", "malformed", "encrypted", "esquema")


def word_key(word: str) -> str:
    """Clave de búsqueda: «That,», «that» y «dont» / «don't» son lo mismo."""
    return _CLEAN_RE.sub("", str(word).lower())


def material_key(analysis: dict) -> str | None:
    """Huella del material: las primeras palabras con su instante.

    Analizar el mismo vídeo dos veces (p. ej. con y sin atracción) o un recorte
    suyo produce la misma cabecera; sin esto, el corpus presenta como tres
    grabaciones lo que es una, e infla todas sus cifras.
    """
    import hashlib

    piezas = []
    for segment in analysis.get("segments") or []:
        for word in segment.get("words") or []:
            piezas.append(f"{word_key(word.get('word', ''))}@{float(word.get('start', 0)):.1f}")
            if len(piezas) >= _MATERIAL_WORDS:
                break
        if len(piezas) >= _MATERIAL_WORDS:
            break
    if len(piezas) < _MATERIAL_WORDS:
        return None                      # demasiado corto para reconocer nada
    return hashlib.sha1("|".join(piezas).encode("utf-8")).hexdigest()[:16]


def _looks_broken(exc: Exception) -> bool:
    if isinstance(exc, sqlite3.OperationalError) and "esquema" not in str(exc):
        return False
    return any(marca in str(exc).lower() for marca in _BROKEN)


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


class Corpus:
    """Acceso al índice. Seguro entre hilos (el trabajador escribe mientras la
    interfaz consulta), con un lock: el volumen es de una herramienta local."""

    def __init__(self, path: str | Path = DEFAULT_DB):
        self.path = Path(path)
        if str(self.path) != ":memory:":
            self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self.rebuilt = False       # True si hubo que empezar de cero
        try:
            self._open()
        except sqlite3.DatabaseError as exc:
            # Solo se aparta lo que de verdad está roto o es de otro esquema.
            # Un `database is locked` o un archivo sin permiso de escritura son
            # problemas del entorno: retirar por eso borraba un corpus sano.
            if not _looks_broken(exc):
                raise
            self._retire()
            self._open()

    def _open(self) -> None:
        self._conn = sqlite3.connect(str(self.path), check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        with self._lock, self._conn:
            # WAL: la interfaz consulta mientras el trabajador indexa, y puede
            # haber más de un `phonotrainer ui` contra la misma base.
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute("PRAGMA busy_timeout=5000")
            version = self._stored_version()
            if version is not None and version != SCHEMA_VERSION:
                raise sqlite3.DatabaseError(
                    f"esquema {version}, se esperaba {SCHEMA_VERSION}")
            self._conn.executescript(_SCHEMA)
            self._conn.execute(
                "INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', ?)",
                (str(SCHEMA_VERSION),),
            )

    def _stored_version(self) -> int | None:
        try:
            row = self._conn.execute(
                "SELECT value FROM meta WHERE key = 'schema_version'").fetchone()
        except sqlite3.OperationalError:
            return None            # base nueva: aún no hay tabla meta
        return int(row["value"]) if row else None

    def _retire(self) -> None:
        """Aparta la base ilegible (no la borra: por si el usuario quiere verla).

        Se lleva también `-wal` y `-shm`: dejarlos sueltos corrompía la base
        nueva, y el WAL puede contener lo último indexado.
        """
        try:
            self._conn.close()
        except Exception:                                 # noqa: BLE001
            pass
        if str(self.path) == ":memory:":
            self.rebuilt = True
            return
        marca = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
        for sufijo in ("", "-wal", "-shm"):
            viejo = self.path.with_name(self.path.name + sufijo)
            if viejo.exists():
                viejo.replace(viejo.with_name(f"{viejo.name}.{marca}.corrupta"))
        self.rebuilt = True

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    # --- escritura ----------------------------------------------------------
    def index_analysis(self, analysis_id: str, analysis: dict,
                       source: str | None = None, result_dir: str | None = None,
                       job_id: str | None = None) -> int:
        """Vuelca un análisis (reemplazando el anterior con el mismo id).

        `analysis_id` debe ser una identidad estable del material —usamos el
        directorio de salida—, no el id del job: si no, analizar por CLI e
        importar después en la interfaz contaría el mismo material dos veces.
        Devuelve cuántas palabras se indexaron.
        """
        meta = analysis.get("meta") or {}
        segments = analysis.get("segments") or []
        models = meta.get("models") or {}

        word_rows, phen_rows = [], []
        for si, segment in enumerate(segments):
            for wi, word in enumerate(segment.get("words") or []):
                word_rows.append((
                    analysis_id, si, wi, word.get("word", ""), word_key(word.get("word", "")),
                    float(word.get("start", 0.0)), float(word.get("end", 0.0)),
                    word.get("dict_ipa"), word.get("canonical_ipa"),
                    word.get("realized_ipa"), word.get("realized_raw_ipa"),
                    float(word.get("diff_cost", 0.0) or 0.0),
                    int(word.get("attracted_count", 0) or 0),
                    int(bool(word.get("low_confidence"))), int(bool(word.get("oov"))),
                    word.get("lexical_form"),
                ))
                for phenomenon in word.get("phenomena") or []:
                    phen_rows.append((analysis_id, si, wi, phenomenon))

        with self._lock, self._conn:
            self._forget(analysis_id)
            self._conn.execute(
                """INSERT INTO analyses (id, job_id, material, source, result_dir, duration,
                       language, attraction, asr_model, phone_model, segments, words, indexed_at)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (analysis_id, job_id, material_key(analysis),
                 source or meta.get("source") or analysis_id, result_dir,
                 meta.get("duration"), meta.get("language"),
                 int(bool(meta.get("attraction", True))),
                 models.get("asr"), models.get("phones"),
                 len(segments), len(word_rows), _now()),
            )
            self._conn.executemany(
                """INSERT INTO words (analysis_id, segment, word_idx, word, word_key,
                       start, end, dict_ipa, canonical_ipa, realized_ipa, realized_raw_ipa,
                       diff_cost, attracted_count, low_confidence, oov, lexical_form)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                word_rows,
            )
            self._conn.executemany(
                "INSERT INTO phenomena (analysis_id, segment, word_idx, phenomenon) VALUES (?,?,?,?)",
                phen_rows,
            )
        return len(word_rows)

    def index_file(self, analysis_id: str, analysis_path: str | Path, **kwargs) -> int:
        """Indexa desde un analysis.json en disco."""
        analysis_path = Path(analysis_path)
        analysis = json.loads(analysis_path.read_text(encoding="utf-8"))
        kwargs.setdefault("result_dir", str(analysis_path.parent))
        return self.index_analysis(analysis_id, analysis, **kwargs)

    def forget(self, analysis_id: str) -> None:
        """Saca un análisis del corpus por su identidad estable."""
        with self._lock, self._conn:
            self._forget(analysis_id)

    def forget_job(self, job_id: str, keep_analysis: bool = False) -> None:
        """Suelta del corpus lo que indexó un job de la interfaz (al borrarlo).

        `keep_analysis` para los jobs importados: sus datos siguen en disco (el
        borrado del job no los toca) y puede que los indexara la CLI, así que la
        fila se queda y solo pierde su job.
        """
        with self._lock, self._conn:
            filas = self._conn.execute(
                "SELECT id FROM analyses WHERE job_id = ?", (job_id,)).fetchall()
            for row in filas:
                if keep_analysis:
                    self._conn.execute("UPDATE analyses SET job_id = NULL WHERE id = ?",
                                       (row["id"],))
                else:
                    self._forget(row["id"])

    def job_ids(self) -> set[str]:
        """Jobs que han dejado algo en el corpus (para detectar huérfanos)."""
        return {row["job_id"] for row in
                self._rows("SELECT DISTINCT job_id FROM analyses WHERE job_id IS NOT NULL")}

    def _forget(self, analysis_id: str) -> None:
        for table in ("phenomena", "words", "analyses"):
            column = "id" if table == "analyses" else "analysis_id"
            self._conn.execute(f"DELETE FROM {table} WHERE {column} = ?", (analysis_id,))

    # --- consultas ----------------------------------------------------------
    def _rows(self, sql: str, params: tuple = ()) -> list[dict]:
        with self._lock:
            return [dict(row) for row in self._conn.execute(sql, params).fetchall()]

    def analyses(self) -> list[dict]:
        """Qué compone el corpus. `duplicate_source` avisa de que el mismo
        material está contado más de una vez (p. ej. el mismo clip analizado con
        y sin atracción): si no, las cifras globales engañan."""
        rows = self._rows("SELECT * FROM analyses ORDER BY indexed_at DESC")
        veces: dict[str, int] = {}
        for row in rows:
            clave = row["material"] or row["source"]
            veces[clave] = veces.get(clave, 0) + 1
        for row in rows:
            row["attraction"] = bool(row["attraction"])
            # Mismo material: el nombre del archivo no basta (un recorte del
            # mismo vídeo se llama distinto y sigue siendo la misma grabación).
            row["duplicate_source"] = veces[row["material"] or row["source"]] > 1
        return rows

    def stats(self) -> dict:
        totals = self._rows(
            """SELECT COUNT(*) AS analyses,
                      COALESCE(SUM(words), 0) AS words,
                      COALESCE(SUM(duration), 0) AS duration,
                      COALESCE(SUM(segments), 0) AS segments
               FROM analyses"""
        )[0]
        totals["sources"] = self._rows(
            "SELECT COUNT(DISTINCT source) AS n FROM analyses")[0]["n"]
        # Grabaciones distintas de verdad: si es menor que `analyses`, hay
        # material analizado más de una vez y las cifras lo cuentan doble.
        totals["materials"] = self._rows(
            "SELECT COUNT(DISTINCT COALESCE(material, id)) AS n FROM analyses")[0]["n"]
        totals["phenomena"] = self._rows(
            """SELECT phenomenon,
                      COUNT(*) AS count,
                      COUNT(DISTINCT analysis_id) AS analyses
               FROM phenomena GROUP BY phenomenon ORDER BY count DESC"""
        )
        totals["top_words"] = self._rows(
            """SELECT w.word_key AS word, COUNT(*) AS count
               FROM phenomena p JOIN words w
                 ON w.analysis_id = p.analysis_id AND w.segment = p.segment
                AND w.word_idx = p.word_idx
               GROUP BY w.word_key ORDER BY count DESC, word LIMIT 15"""
        )
        return totals

    def _occurrence_filter(self, phenomenon: str | None, word: str | None,
                           analysis_id: str | None) -> tuple[str, list]:
        # Sin filtro de palabra listamos solo lo etiquetado: la lista completa
        # encabezada por la divergencia son sobre todo fallos de alineamiento.
        where, params = (["1=1"] if word else
                         ["""EXISTS (SELECT 1 FROM phenomena p0
                                     WHERE p0.analysis_id = w.analysis_id
                                       AND p0.segment = w.segment
                                       AND p0.word_idx = w.word_idx)"""]), []
        if phenomenon:
            # Arrancar por idx_phen_name (y no escanear `words` entera) importa
            # en cuanto el corpus tiene unos cuantos vídeos.
            where.append(
                """(w.analysis_id, w.segment, w.word_idx) IN
                   (SELECT p.analysis_id, p.segment, p.word_idx FROM phenomena p
                     WHERE p.phenomenon = ?)"""
            )
            params.append(phenomenon)
        if word:
            where.append("w.word_key = ?")
            params.append(word_key(word))
        if analysis_id:
            where.append("w.analysis_id = ?")
            params.append(analysis_id)
        return " AND ".join(where), params

    def count_occurrences(self, phenomenon: str | None = None, word: str | None = None,
                          analysis_id: str | None = None) -> int:
        """Cuántas hay en total, para no llamar «200 apariciones» al tope de la consulta."""
        where, params = self._occurrence_filter(phenomenon, word, analysis_id)
        return self._rows(f"SELECT COUNT(*) AS n FROM words w WHERE {where}",
                          tuple(params))[0]["n"]

    def occurrences(self, phenomenon: str | None = None, word: str | None = None,
                    analysis_id: str | None = None, limit: int = 100) -> list[dict]:
        """Apariciones entre todos los análisis, de la más divergente a la menos.

        Devuelve dónde está cada una (análisis, segmento, índice, tiempo) para
        que la interfaz pueda saltar directamente.
        """
        where, params = self._occurrence_filter(phenomenon, word, analysis_id)
        rows = self._rows(
            f"""SELECT w.*, a.source AS analysis_source, a.attraction AS analysis_attraction,
                       a.job_id AS job_id,          -- con esto la interfaz puede saltar
                       n.word AS next_word,
                       (SELECT GROUP_CONCAT(p.phenomenon, ',') FROM phenomena p
                         WHERE p.analysis_id = w.analysis_id AND p.segment = w.segment
                           AND p.word_idx = w.word_idx) AS phenomena
                FROM words w
                JOIN analyses a ON a.id = w.analysis_id
                -- la palabra siguiente: en linking o palatalización el fenómeno
                -- ocurre entre las dos, listar solo la primera lo descontextualiza
                LEFT JOIN words n ON n.analysis_id = w.analysis_id
                                 AND n.segment = w.segment
                                 AND n.word_idx = w.word_idx + 1
                WHERE {where}
                -- Las palabras de uno o dos frames son casi siempre fallos de
                -- alineación (y duran menos de lo que se puede oír): al final,
                -- por muy alta que sea su divergencia.
                ORDER BY (w.end - w.start) < ?, w.diff_cost DESC,
                         w.analysis_id, w.segment, w.word_idx
                LIMIT ?""",
            (*params, MIN_AUDIBLE, int(limit)),
        )
        for row in rows:
            row["phenomena"] = row["phenomena"].split(",") if row["phenomena"] else []
            row["low_confidence"] = bool(row["low_confidence"])
            row["oov"] = bool(row["oov"])
            row["analysis_attraction"] = bool(row["analysis_attraction"])
            row["too_short"] = (row["end"] - row["start"]) < MIN_AUDIBLE
        return rows

    def word_variants(self, word: str) -> list[dict]:
        """Cómo se ha pronunciado realmente una palabra, y cuántas veces cada forma.
        Es la pregunta que el corpus contesta y un análisis suelto no."""
        return self._rows(
            """SELECT realized_ipa, COUNT(*) AS count,
                      COUNT(DISTINCT analysis_id) AS analyses,
                      MIN(dict_ipa) AS dict_ipa
               FROM words WHERE word_key = ?
               GROUP BY realized_ipa ORDER BY count DESC""",
            (word_key(word),),
        )
