"""Corpus: a SQLite index of everything that has been analyzed.

Each `analysis.json` lives in its own directory and answers "what happens in
this video" perfectly well. What it cannot answer is "show me every instance of
flapping I have seen so far" or "how has *to* been pronounced across the six
episodes?". That is what this table is for: when an analysis finishes (or is
imported), its words and phenomena are dumped here and become queryable across
analyses.

The database is derived and disposable: it can be deleted and rebuilt by
reindexing the `analysis.json` files. That is why `data/` is in .gitignore and
every clone of the project starts with an empty corpus.
"""

from __future__ import annotations

import json
import re
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path

from . import metrics as metrics_mod
from .phones_real import engine_of

DEFAULT_DB = Path("data/phonotrainer.db")
SCHEMA_VERSION = 4
# Upgrades that keep the data: stored version → statements that bring it to the
# next one. Anything without an entry is rebuilt instead. Adding a column here
# spares the corpus a rebuild, which matters for what the CLI indexed: no
# workspace job would ever reindex that.
_MIGRATIONS = {
    3: ("ALTER TABLE analyses ADD COLUMN metrics TEXT",),
}
# Below this, a "word" is a stray CTC spike rather than something anyone could
# hear or judge: it stays in the corpus, but it does not head the list.
MIN_AUDIBLE = 0.06   # s
# Opening words that make up the fingerprint of the material. There are only a
# few on purpose: a clip cut from the same video must share it, and two
# different recordings starting with the same 4 words AT THE SAME INSTANTS does
# not happen.
_MATERIAL_WORDS = 4

_SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS analyses (
    -- Stable identity of the analysis: the directory where its analysis.json
    -- lives. Were it the job id, the same out/ analyzed by the CLI and later
    -- imported into the interface would be counted twice.
    id          TEXT PRIMARY KEY,
    job_id      TEXT,                    -- the interface's job, if any
    -- Fingerprint of the material: the first words and their times. Two
    -- analyses of the same audio (with and without attraction, or a clip cut
    -- from it) share it, so the corpus can tell how many genuinely distinct
    -- recordings there are.
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
    indexed_at  TEXT NOT NULL,
    -- metrics.compute() as JSON. NULL (or an older version) is backfilled from
    -- result_dir/analysis.json the first time corpus metrics are asked for.
    metrics     TEXT
);

CREATE TABLE IF NOT EXISTS words (
    analysis_id      TEXT    NOT NULL,
    segment          INTEGER NOT NULL,
    word_idx         INTEGER NOT NULL,
    word             TEXT    NOT NULL,
    word_key         TEXT    NOT NULL,   -- lowercased and unpunctuated, for searching
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
# A different schema or an unreadable database gets rebuilt; anything else
# (locks, permissions) belongs to the environment and must not cost anyone
# their corpus.
_BROKEN = ("not a database", "malformed", "encrypted", "schema version")


def word_key(word: str) -> str:
    """Search key: "That,", "that" and "dont" / "don't" are the same thing."""
    return _CLEAN_RE.sub("", str(word).lower())


def material_key(analysis: dict) -> str | None:
    """Fingerprint of the material: the first words with their timestamps.

    Analyzing the same video twice (e.g. with and without attraction) or a clip
    cut from it produces the same opening; without this, the corpus presents as
    three recordings what is really one, and inflates every figure it reports.
    """
    import hashlib

    pieces = []
    for segment in analysis.get("segments") or []:
        for word in segment.get("words") or []:
            pieces.append(f"{word_key(word.get('word', ''))}@{float(word.get('start', 0)):.1f}")
            if len(pieces) >= _MATERIAL_WORDS:
                break
        if len(pieces) >= _MATERIAL_WORDS:
            break
    if len(pieces) < _MATERIAL_WORDS:
        return None                      # too short to recognize anything
    return hashlib.sha1("|".join(pieces).encode("utf-8")).hexdigest()[:16]


def _looks_broken(exc: Exception) -> bool:
    if isinstance(exc, sqlite3.OperationalError) and "schema version" not in str(exc):
        return False
    return any(marker in str(exc).lower() for marker in _BROKEN)


# A word with something to measure against: older analyses carry no
# `no_canonical` flag, and an empty canonical says the same thing.
_HAS_CANONICAL = "COALESCE(w.canonical_ipa, '') != ''"


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _load_metrics(raw: str | None) -> dict | None:
    try:
        return json.loads(raw) if raw else None
    except ValueError:
        return None


def _dump_metrics(metrics: dict | None) -> str | None:
    return json.dumps(metrics, ensure_ascii=False) if metrics else None


def _current_metrics(metrics: dict | None) -> dict | None:
    """The metrics as they are, or None if they predate the current definitions."""
    if not metrics or metrics.get("version") != metrics_mod.METRICS_VERSION:
        return None
    return metrics


class Corpus:
    """Access to the index. Thread-safe (the worker writes while the interface
    queries) by way of a lock: the volume is that of a local tool."""

    def __init__(self, path: str | Path = DEFAULT_DB):
        self.path = Path(path)
        if str(self.path) != ":memory:":
            self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self.rebuilt = False       # True if we had to start from scratch
        try:
            self._open()
        except sqlite3.DatabaseError as exc:
            # Only what is genuinely broken or belongs to another schema gets
            # set aside. A `database is locked` or a file without write
            # permission are environment problems: retiring the database for
            # those used to wipe out a perfectly healthy corpus.
            if not _looks_broken(exc):
                raise
            self._retire()
            self._open()

    def _open(self) -> None:
        # Autocommit while we set up, so the upgrade below is ONE explicit
        # transaction: python's sqlite3 runs DDL outside any implicit one, and
        # `executescript` commits on its own. An upgrade split across several
        # commits could die after the ALTER and before the version was written,
        # and every later open then failed on `duplicate column name`.
        self._conn = sqlite3.connect(str(self.path), check_same_thread=False,
                                     isolation_level=None)
        self._conn.row_factory = sqlite3.Row
        with self._lock:
            # WAL: the interface queries while the worker indexes, and there may
            # be more than one `phonotrainer ui` against the same database.
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute("PRAGMA busy_timeout=5000")
            # IMMEDIATE takes the write lock BEFORE the version is read: a second
            # process opening the same corpus waits here, then finds it upgraded.
            self._conn.execute("BEGIN IMMEDIATE")
            try:
                self._upgrade()
                self._conn.execute("COMMIT")
            except BaseException:
                self._conn.execute("ROLLBACK")
                raise
        self._conn.isolation_level = ""      # back to the implicit transactions

    def _upgrade(self) -> None:
        version = self._stored_version()
        while version is not None and version != SCHEMA_VERSION:
            if version not in _MIGRATIONS:
                raise sqlite3.DatabaseError(
                    f"schema version {version}, expected {SCHEMA_VERSION}")
            for statement in _MIGRATIONS[version]:
                self._migrate(statement)
            version += 1
        for statement in _SCHEMA.split(";"):
            if statement.strip():
                self._conn.execute(statement)
        self._conn.execute(
            "INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', ?)",
            (str(SCHEMA_VERSION),),
        )

    def _migrate(self, statement: str) -> None:
        """Run one upgrade step, idempotently: a corpus left half-upgraded by an
        older version of this code may already have the column."""
        try:
            self._conn.execute(statement)
        except sqlite3.OperationalError as exc:
            if "duplicate column name" not in str(exc):
                raise

    def _stored_version(self) -> int | None:
        try:
            row = self._conn.execute(
                "SELECT value FROM meta WHERE key = 'schema_version'").fetchone()
        except sqlite3.OperationalError:
            return None            # brand-new database: no meta table yet
        return int(row["value"]) if row else None

    def _retire(self) -> None:
        """Set the unreadable database aside (not delete it: the user may want
        to look at it).

        `-wal` and `-shm` go with it: leaving them behind corrupted the new
        database, and the WAL may hold the most recently indexed data.
        """
        try:
            self._conn.close()
        except Exception:                                 # noqa: BLE001
            pass
        if str(self.path) == ":memory:":
            self.rebuilt = True
            return
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
        for suffix in ("", "-wal", "-shm"):
            old = self.path.with_name(self.path.name + suffix)
            if old.exists():
                old.replace(old.with_name(f"{old.name}.{stamp}.corrupt"))
        self.rebuilt = True

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    # --- writing ------------------------------------------------------------
    def index_analysis(self, analysis_id: str, analysis: dict,
                       source: str | None = None, result_dir: str | None = None,
                       job_id: str | None = None) -> int:
        """Dump an analysis into the index (replacing any earlier one with the
        same id).

        `analysis_id` must be a stable identity of the material —we use the
        output directory—, not the job id: otherwise, analyzing from the CLI and
        importing afterward from the interface would count the same material
        twice. Returns how many words were indexed.
        """
        meta = analysis.get("meta") or {}
        segments = analysis.get("segments") or []
        models = meta.get("models") or {}
        metrics = _current_metrics((analysis.get("summary") or {}).get("metrics"))
        if metrics is None:
            metrics = metrics_mod.safe_compute(analysis)     # backfill on (re)index

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
                       language, attraction, asr_model, phone_model, segments, words,
                       indexed_at, metrics)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (analysis_id, job_id, material_key(analysis),
                 source or meta.get("source") or analysis_id, result_dir,
                 meta.get("duration"), meta.get("language"),
                 int(bool(meta.get("attraction", True))),
                 models.get("asr"), models.get("phones"),
                 len(segments), len(word_rows), _now(), _dump_metrics(metrics)),
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
        """Index from an analysis.json on disk."""
        analysis_path = Path(analysis_path)
        analysis = json.loads(analysis_path.read_text(encoding="utf-8"))
        kwargs.setdefault("result_dir", str(analysis_path.parent))
        return self.index_analysis(analysis_id, analysis, **kwargs)

    def forget(self, analysis_id: str) -> None:
        """Drop an analysis from the corpus by its stable identity."""
        with self._lock, self._conn:
            self._forget(analysis_id)

    def forget_job(self, job_id: str, keep_analysis: bool = False) -> None:
        """Release what an interface job indexed (when the job is deleted).

        Use `keep_analysis` for imported jobs: their data is still on disk
        (deleting the job does not touch it) and the CLI may have indexed it, so
        the row stays and merely loses its job.
        """
        with self._lock, self._conn:
            rows = self._conn.execute(
                "SELECT id FROM analyses WHERE job_id = ?", (job_id,)).fetchall()
            for row in rows:
                if keep_analysis:
                    self._conn.execute("UPDATE analyses SET job_id = NULL WHERE id = ?",
                                       (row["id"],))
                else:
                    self._forget(row["id"])

    def job_ids(self) -> set[str]:
        """Jobs that left something in the corpus (to spot orphans)."""
        return {row["job_id"] for row in
                self._rows("SELECT DISTINCT job_id FROM analyses WHERE job_id IS NOT NULL")}

    def _forget(self, analysis_id: str) -> None:
        for table in ("phenomena", "words", "analyses"):
            column = "id" if table == "analyses" else "analysis_id"
            self._conn.execute(f"DELETE FROM {table} WHERE {column} = ?", (analysis_id,))

    # --- queries ------------------------------------------------------------
    def _rows(self, sql: str, params: tuple = ()) -> list[dict]:
        with self._lock:
            return [dict(row) for row in self._conn.execute(sql, params).fetchall()]

    def analyses(self) -> list[dict]:
        """What the corpus is made of. `duplicate_source` warns that the same
        material is counted more than once (e.g. the same clip analyzed with and
        without attraction): otherwise the global figures are misleading."""
        rows = self._rows("SELECT * FROM analyses ORDER BY indexed_at DESC")
        counts: dict[str, int] = {}
        for row in rows:
            key = row["material"] or row["source"]
            counts[key] = counts.get(key, 0) + 1
        for row in rows:
            row["attraction"] = bool(row["attraction"])
            row["metrics"] = _load_metrics(row["metrics"])
            # Same material: the file name is not enough (a clip cut from the
            # same video has a different name and is still the same recording).
            row["duplicate_source"] = counts[row["material"] or row["source"]] > 1
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
        # Genuinely distinct recordings: if this is lower than `analyses`, some
        # material was analyzed more than once and the figures double-count it.
        totals["materials"] = self._rows(
            "SELECT COUNT(DISTINCT COALESCE(material, id)) AS n FROM analyses")[0]["n"]
        totals["phenomena"] = self._rows(
            f"""SELECT p.phenomenon AS phenomenon,
                       COUNT(*) AS count,
                       COUNT(DISTINCT p.analysis_id) AS analyses
                FROM phenomena p JOIN words w
                  ON w.analysis_id = p.analysis_id AND w.segment = p.segment
                 AND w.word_idx = p.word_idx
                WHERE {_HAS_CANONICAL}
                GROUP BY p.phenomenon ORDER BY count DESC"""
        )
        totals["top_words"] = self._rows(
            f"""SELECT w.word_key AS word, COUNT(*) AS count
                FROM phenomena p JOIN words w
                  ON w.analysis_id = p.analysis_id AND w.segment = p.segment
                 AND w.word_idx = p.word_idx
                WHERE {_HAS_CANONICAL}
                GROUP BY w.word_key ORDER BY count DESC, word LIMIT 15"""
        )
        return totals

    def metrics(self) -> dict:
        """Reduction metrics pooled over the corpus, token by token, per engine.

        The phone engines are pooled apart (`by_engine`, the default engine
        first): the recognizer and the canonical both change with the engine, so
        adding their figures together would measure neither. Within an engine,
        one analysis per material (the most recently indexed): the same clip
        analyzed with and without attraction would otherwise count its words
        twice. Rows indexed before the metrics existed (or under an older
        definition) are backfilled from their analysis.json while it is still on
        disk; the ones that cannot be are reported as `missing`, not guessed.
        """
        rows = self._rows(
            """SELECT id, material, result_dir, phone_model, metrics FROM analyses
               ORDER BY indexed_at DESC, id""")
        chosen, seen = [], set()
        for row in rows:
            key = (row["material"] or row["id"],
                   engine_of({"models": {"phones": row["phone_model"]}}))
            if key not in seen:
                seen.add(key)
                chosen.append(row)
        items, missing = [], 0
        for row in chosen:
            metrics = _current_metrics(_load_metrics(row["metrics"])) or self._backfill(row)
            if metrics is None:
                missing += 1
            else:
                items.append(metrics)
        return {"analyses": len(rows),
                "materials": len({row["material"] or row["id"] for row in chosen}),
                "measured": len(items), "missing": missing,
                "by_engine": metrics_mod.aggregate_by_engine(items)}

    def _backfill(self, row: dict) -> dict | None:
        path = Path(row["result_dir"] or row["id"]) / "analysis.json"
        try:
            analysis = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        metrics = metrics_mod.safe_compute(analysis)
        if metrics is not None:
            with self._lock, self._conn:
                self._conn.execute("UPDATE analyses SET metrics = ? WHERE id = ?",
                                   (_dump_metrics(metrics), row["id"]))
        return metrics

    def _occurrence_filter(self, phenomenon: str | None, word: str | None,
                           analysis_id: str | None) -> tuple[str, list]:
        # With no word filter we list only what was tagged: the full list headed
        # by divergence is mostly alignment failures. Nor words without a
        # canonical ("9" in analyses made before numerals were spoken out): every
        # phone there was an insertion against nothing, so they would head it.
        where, params = (["1=1"] if word else
                         ["""EXISTS (SELECT 1 FROM phenomena p0
                                     WHERE p0.analysis_id = w.analysis_id
                                       AND p0.segment = w.segment
                                       AND p0.word_idx = w.word_idx)""",
                          _HAS_CANONICAL]), []
        if phenomenon:
            # Starting from idx_phen_name (instead of scanning the whole `words`
            # table) matters as soon as the corpus holds a few videos.
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
        """How many there are in total, so that "200 occurrences" is not really
        just the query's limit."""
        where, params = self._occurrence_filter(phenomenon, word, analysis_id)
        return self._rows(f"SELECT COUNT(*) AS n FROM words w WHERE {where}",
                          tuple(params))[0]["n"]

    def occurrences(self, phenomenon: str | None = None, word: str | None = None,
                    analysis_id: str | None = None, limit: int = 100) -> list[dict]:
        """Occurrences across every analysis, most divergent first.

        Returns where each one sits (analysis, segment, index, time) so that the
        interface can jump straight to it.
        """
        where, params = self._occurrence_filter(phenomenon, word, analysis_id)
        rows = self._rows(
            f"""SELECT w.*, a.source AS analysis_source, a.attraction AS analysis_attraction,
                       a.job_id AS job_id,          -- with this the interface can jump
                       n.word AS next_word,
                       (SELECT GROUP_CONCAT(p.phenomenon, ',') FROM phenomena p
                         WHERE p.analysis_id = w.analysis_id AND p.segment = w.segment
                           AND p.word_idx = w.word_idx) AS phenomena
                FROM words w
                JOIN analyses a ON a.id = w.analysis_id
                -- the following word: in linking or palatalization the
                -- phenomenon happens between the two, so listing only the first
                -- strips it of its context
                LEFT JOIN words n ON n.analysis_id = w.analysis_id
                                 AND n.segment = w.segment
                                 AND n.word_idx = w.word_idx + 1
                WHERE {where}
                -- Words one or two frames long are almost always alignment
                -- failures (and last less than anyone can hear): they go last,
                -- however high their divergence.
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
        """How a word has actually been pronounced, and how often each form.
        This is the question the corpus answers and a single analysis cannot."""
        return self._rows(
            """SELECT realized_ipa, COUNT(*) AS count,
                      COUNT(DISTINCT analysis_id) AS analyses,
                      MIN(dict_ipa) AS dict_ipa
               FROM words WHERE word_key = ?
               GROUP BY realized_ipa ORDER BY count DESC""",
            (word_key(word),),
        )
