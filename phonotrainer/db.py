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
SCHEMA_VERSION = 1

_SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS analyses (
    id          TEXT PRIMARY KEY,
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
"""

_CLEAN_RE = re.compile(r"[^\w']+", re.UNICODE)


def word_key(word: str) -> str:
    """Clave de búsqueda: «That,» y «that» son la misma palabra."""
    return _CLEAN_RE.sub("", str(word).lower())


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
        self._conn = sqlite3.connect(str(self.path), check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        with self._lock, self._conn:
            self._conn.executescript(_SCHEMA)
            self._conn.execute(
                "INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', ?)",
                (str(SCHEMA_VERSION),),
            )

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    # --- escritura ----------------------------------------------------------
    def index_analysis(self, analysis_id: str, analysis: dict,
                       source: str | None = None, result_dir: str | None = None) -> int:
        """Vuelca un análisis (reemplazando el anterior con el mismo id).
        Devuelve cuántas palabras se indexaron."""
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
                """INSERT INTO analyses (id, source, result_dir, duration, language,
                       attraction, asr_model, phone_model, segments, words, indexed_at)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                (analysis_id, source or meta.get("source") or analysis_id, result_dir,
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
        """Saca un análisis del corpus (al borrarlo de la interfaz)."""
        with self._lock, self._conn:
            self._forget(analysis_id)

    def _forget(self, analysis_id: str) -> None:
        for table in ("phenomena", "words", "analyses"):
            column = "id" if table == "analyses" else "analysis_id"
            self._conn.execute(f"DELETE FROM {table} WHERE {column} = ?", (analysis_id,))

    # --- consultas ----------------------------------------------------------
    def _rows(self, sql: str, params: tuple = ()) -> list[dict]:
        with self._lock:
            return [dict(row) for row in self._conn.execute(sql, params).fetchall()]

    def analyses(self) -> list[dict]:
        return self._rows("SELECT * FROM analyses ORDER BY indexed_at DESC")

    def stats(self) -> dict:
        totals = self._rows(
            """SELECT COUNT(*) AS analyses,
                      COALESCE(SUM(words), 0) AS words,
                      COALESCE(SUM(duration), 0) AS duration,
                      COALESCE(SUM(segments), 0) AS segments
               FROM analyses"""
        )[0]
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
        where, params = ["1=1"], []
        if phenomenon:
            where.append(
                """EXISTS (SELECT 1 FROM phenomena p
                           WHERE p.analysis_id = w.analysis_id AND p.segment = w.segment
                             AND p.word_idx = w.word_idx AND p.phenomenon = ?)"""
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
            f"""SELECT w.*, a.source AS analysis_source,
                       (SELECT GROUP_CONCAT(p.phenomenon, ',') FROM phenomena p
                         WHERE p.analysis_id = w.analysis_id AND p.segment = w.segment
                           AND p.word_idx = w.word_idx) AS phenomena
                FROM words w JOIN analyses a ON a.id = w.analysis_id
                WHERE {where}
                ORDER BY w.diff_cost DESC, w.analysis_id, w.segment, w.word_idx
                LIMIT ?""",
            (*params, int(limit)),
        )
        for row in rows:
            row["phenomena"] = row["phenomena"].split(",") if row["phenomena"] else []
            row["low_confidence"] = bool(row["low_confidence"])
            row["oov"] = bool(row["oov"])
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
