"""SQLite corpus: indexing, cross-analysis queries and cleanup."""

import json

import pytest
from conftest import mk_analysis, mk_analysis_word

from phonotrainer.db import Corpus, word_key


@pytest.fixture
def corpus():
    db = Corpus(":memory:")
    yield db
    db.close()


def test_indexes_a_complete_analysis(corpus):
    n = corpus.index_analysis("job1", mk_analysis("ep1.webm"))

    assert n == 5
    (row,) = corpus.analyses()
    assert row["source"] == "ep1.webm"
    assert row["words"] == 5 and row["segments"] == 2
    assert row["asr_model"].startswith("faster-whisper")


def test_reindexing_replaces_instead_of_duplicating(corpus):
    corpus.index_analysis("job1", mk_analysis())
    corpus.index_analysis("job1", mk_analysis())

    assert len(corpus.analyses()) == 1
    assert corpus.stats()["words"] == 5


def test_stats_add_up_across_every_analysis(corpus):
    corpus.index_analysis("job1", mk_analysis("ep1.webm"))
    corpus.index_analysis("job2", mk_analysis("ep2.webm"))

    stats = corpus.stats()
    assert stats["analyses"] == 2
    assert stats["words"] == 10
    assert stats["duration"] == 6.0
    counts = {p["phenomenon"]: p for p in stats["phenomena"]}
    assert counts["t_deletion"]["count"] == 2
    assert counts["t_deletion"]["analyses"] == 2      # it shows up in both


def test_finds_occurrences_of_a_phenomenon_across_analyses(corpus):
    corpus.index_analysis("job1", mk_analysis("ep1.webm"))
    corpus.index_analysis("job2", mk_analysis("ep2.webm"))

    rows = corpus.occurrences(phenomenon="t_deletion")
    assert len(rows) == 2
    assert {r["analysis_source"] for r in rows} == {"ep1.webm", "ep2.webm"}
    first = rows[0]
    assert first["word"] == "that"
    assert first["phenomena"] == ["t_deletion"]
    # the exact position, so that the interface can jump there
    assert (first["segment"], first["word_idx"]) == (0, 1)
    assert first["start"] == 0.4


def test_counts_the_total_even_when_the_listing_is_truncated(corpus):
    """The "200 occurrences" figure must not really be the query's limit."""
    corpus.index_analysis("job1", mk_analysis())

    assert len(corpus.occurrences(limit=2)) == 2
    assert corpus.count_occurrences() == 3          # the 3 words with a phenomenon
    assert corpus.count_occurrences(phenomenon="t_deletion") == 1
    assert corpus.count_occurrences(word="does") == 1


def test_with_no_filter_only_words_with_a_phenomenon_are_listed(corpus):
    """The full list ordered by divergence is mostly alignment failures: that is
    not what should be shown first."""
    corpus.index_analysis("job1", mk_analysis())

    unfiltered = corpus.occurrences()
    assert {row["word"] for row in unfiltered} == {"does", "that", "wanna"}
    assert all(row["phenomena"] for row in unfiltered)

    # searching for a specific word does show every occurrence of it
    assert [r["word"] for r in corpus.occurrences(word="work")] == ["work"]


def test_each_occurrence_carries_what_it_links_to_and_which_analysis_it_comes_from(corpus):
    """In linking, the phenomenon happens between two words; and two analyses of
    the same material with different settings are not native variation."""
    analysis = mk_analysis()
    analysis["meta"]["attraction"] = False
    corpus.index_analysis("/tmp/out", analysis, job_id="job1")

    (row,) = corpus.occurrences(phenomenon="vowel_reduction")
    assert row["word"] == "does" and row["next_word"] == "that"
    assert row["analysis_attraction"] is False
    assert row["job_id"] == "job1"


def test_occurrences_come_out_from_most_to_least_divergent(corpus):
    analysis = mk_analysis()
    analysis["segments"][0]["words"][2]["diff_cost"] = 1.4
    analysis["segments"][0]["words"][2]["phenomena"] = ["t_deletion"]
    corpus.index_analysis("job1", analysis)

    rows = corpus.occurrences(phenomenon="t_deletion")
    assert [r["word"] for r in rows] == ["work", "that"]


def test_finds_a_word_regardless_of_punctuation_and_case(corpus):
    analysis = mk_analysis()
    analysis["segments"][0]["words"][0] = mk_analysis_word("Does,", 0.0, "d ʌ z", "d ə z")
    corpus.index_analysis("job1", analysis)

    assert word_key("Does,") == "does"
    rows = corpus.occurrences(word="does")
    assert len(rows) == 1 and rows[0]["word"] == "Does,"


def test_pronunciation_variants_of_a_word(corpus):
    """The question a single analysis cannot answer: how *to* has been said
    across everything seen so far."""
    for i, realized in enumerate(["t ə", "t ə", "t ʊ"]):
        analysis = mk_analysis(f"ep{i}.webm")
        analysis["segments"][0]["words"] = [mk_analysis_word("to", 0.0, "t u", realized)]
        corpus.index_analysis(f"job{i}", analysis)

    variants = corpus.word_variants("to")
    assert [(v["realized_ipa"], v["count"]) for v in variants] == [("tə", 2), ("tʊ", 1)]
    assert variants[0]["analyses"] == 2


def test_repeated_material_is_recognized_even_under_another_name(corpus):
    """A clip cut from the same video has a different name and is still the same
    recording: counting it as another source inflates every figure."""
    whole = mk_analysis("episode.webm")
    clip = mk_analysis("clip.mp3")
    # the first few seconds: same words, same instants, less material
    clip["segments"][1]["words"] = clip["segments"][1]["words"][:1]
    other = mk_analysis("something-else.webm")
    other["segments"][0]["words"][0]["start"] = 7.5     # another recording

    corpus.index_analysis("/a", whole)
    corpus.index_analysis("/b", clip)
    corpus.index_analysis("/c", other)

    stats = corpus.stats()
    assert stats["analyses"] == 3
    assert stats["sources"] == 3            # three file names…
    assert stats["materials"] == 2          # …but two recordings
    by_id = {a["id"]: a for a in corpus.analyses()}
    assert by_id["/a"]["duplicate_source"] and by_id["/b"]["duplicate_source"]
    assert not by_id["/c"]["duplicate_source"]


def test_one_frame_words_do_not_head_the_list(corpus):
    """They last 20 ms: stray CTC spikes, not something anyone can hear or
    judge. They stay in the corpus, but at the end."""
    analysis = mk_analysis()
    short = analysis["segments"][0]["words"][0]
    short["end"] = short["start"] + 0.02
    short["diff_cost"] = 9.9                # the most divergent of them all

    corpus.index_analysis("job1", analysis)
    rows = corpus.occurrences()

    assert rows[0]["word"] != short["word"]
    assert rows[-1]["word"] == short["word"]
    assert rows[-1]["too_short"] is True
    assert rows[0]["too_short"] is False


def test_searching_without_the_apostrophe_finds_the_word(corpus):
    analysis = mk_analysis()
    analysis["segments"][0]["words"][0] = mk_analysis_word("don't", 0.0, "d oʊ n t", "d oʊ n")
    corpus.index_analysis("job1", analysis)

    assert corpus.occurrences(word="dont")[0]["word"] == "don't"
    assert corpus.occurrences(word="DON'T")[0]["word"] == "don't"


def test_forgetting_an_analysis_deletes_all_of_it(corpus):
    corpus.index_analysis("job1", mk_analysis("ep1.webm"))
    corpus.index_analysis("job2", mk_analysis("ep2.webm"))

    corpus.forget("job1")

    assert [a["id"] for a in corpus.analyses()] == ["job2"]
    assert corpus.stats()["words"] == 5
    assert all(r["analysis_id"] == "job2" for r in corpus.occurrences())


def test_indexes_from_a_file(corpus, tmp_path):
    path = tmp_path / "analysis.json"
    path.write_text(json.dumps(mk_analysis("ep.webm")), encoding="utf-8")

    corpus.index_file("job1", path)

    (row,) = corpus.analyses()
    assert row["result_dir"] == str(tmp_path)


def test_an_empty_analysis_breaks_nothing(corpus):
    corpus.index_analysis("empty", {"meta": {}, "segments": []})
    assert corpus.stats()["analyses"] == 1
    assert corpus.occurrences() == []


def test_an_unreadable_database_is_set_aside_and_rebuilt(tmp_path):
    """The corpus is derived: it must never keep the application from starting."""
    path = tmp_path / "phonotrainer.db"
    path.write_bytes(b"this is not a database file")

    db = Corpus(path)
    try:
        assert db.rebuilt is True
        assert list(tmp_path.glob("phonotrainer.db.*.corrupt"))   # set aside, not deleted
        db.index_analysis("x", mk_analysis())        # and it works from scratch
        assert db.stats()["analyses"] == 1
    finally:
        db.close()


def test_a_locked_database_is_not_taken_for_a_corrupt_one(tmp_path):
    """Retiring a healthy database over a transient lock used to cost the user
    the whole corpus."""
    import sqlite3

    from phonotrainer.db import _looks_broken

    assert _looks_broken(sqlite3.OperationalError("database is locked")) is False
    assert _looks_broken(sqlite3.OperationalError("attempt to write a readonly database")) is False
    assert _looks_broken(sqlite3.DatabaseError("file is not a database")) is True
    assert _looks_broken(sqlite3.DatabaseError("database disk image is malformed")) is True

    path = tmp_path / "healthy.db"
    first = Corpus(path)
    try:
        first.index_analysis("job1", mk_analysis())
        second = Corpus(path)                        # a second connection: no harm done
        try:
            assert second.rebuilt is False
            assert second.stats()["analyses"] == 1
        finally:
            second.close()
    finally:
        first.close()


def test_an_old_schema_is_also_rebuilt(tmp_path):
    import sqlite3

    path = tmp_path / "phonotrainer.db"
    con = sqlite3.connect(path)
    con.executescript("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);"
                      "INSERT INTO meta VALUES ('schema_version', '1');"
                      "CREATE TABLE analyses (id TEXT PRIMARY KEY);")
    con.commit()
    con.close()

    db = Corpus(path)
    try:
        assert db.rebuilt is True
        db.index_analysis("x", mk_analysis())
        assert db.stats()["words"] == 5
    finally:
        db.close()


def test_uses_wal_to_coexist_with_another_process(tmp_path):
    db = Corpus(tmp_path / "corpus.db")
    try:
        mode = db._rows("PRAGMA journal_mode")[0]
        assert list(mode.values())[0] == "wal"
    finally:
        db.close()


def test_the_database_creates_itself(tmp_path):
    path = tmp_path / "data" / "phonotrainer.db"
    db = Corpus(path)
    try:
        db.index_analysis("job1", mk_analysis())
        assert path.is_file()
    finally:
        db.close()

    # and it can be reopened without losing anything: it is the corpus across sessions
    second = Corpus(path)
    try:
        assert second.stats()["words"] == 5
    finally:
        second.close()


# --- reduction metrics --------------------------------------------------------------
def test_indexing_stores_the_metrics_of_the_analysis(corpus):
    corpus.index_analysis("job1", mk_analysis())
    (row,) = corpus.analyses()
    assert row["metrics"]["words"]["total"] == 5
    assert row["metrics"]["segment_loss"] == {"count": 1, "of": 4, "pct": 25.0}


def test_metrics_already_in_the_analysis_are_kept_as_they_are(corpus):
    from phonotrainer.metrics import METRICS_VERSION, compute

    analysis = mk_analysis()
    stored = compute(analysis)
    stored["deviate"] = {"count": 7, "of": 7, "pct": 100.0}      # a marker
    analysis["summary"]["metrics"] = stored
    corpus.index_analysis("job1", analysis)
    assert corpus.analyses()[0]["metrics"]["deviate"]["count"] == 7

    stored["version"] = METRICS_VERSION - 1                        # stale: recomputed
    corpus.index_analysis("job1", analysis)
    assert corpus.analyses()[0]["metrics"]["deviate"]["count"] != 7


def test_corpus_metrics_count_each_recording_once(corpus):
    # the same material indexed twice (with and without attraction, say)
    corpus.index_analysis("out", mk_analysis("ep1.webm"))
    corpus.index_analysis("out_noattr", mk_analysis("ep1.webm"))
    result = corpus.metrics()
    assert (result["analyses"], result["materials"], result["measured"]) == (2, 1, 1)
    assert result["by_engine"]["espeak"]["words"]["total"] == 5


def test_corpus_metrics_are_pooled_per_engine_never_across(corpus):
    # the same clip heard by both engines: one recording, two measurements that
    # are never added together (their canonicals and recognizers differ)
    timit = mk_analysis("ep1.webm")
    timit["meta"]["phone_engine"] = "timit61"
    timit["meta"]["models"]["phones"] = "excalibur12/wav2vec2-large-lv60_phoneme-timit"
    corpus.index_analysis("out", mk_analysis("ep1.webm"))
    corpus.index_analysis("out_timit", timit)
    result = corpus.metrics()
    assert (result["analyses"], result["materials"], result["measured"]) == (2, 1, 2)
    assert list(result["by_engine"]) == ["timit61", "espeak"]      # default first
    for engine, pooled in result["by_engine"].items():
        assert pooled["engine"] == engine and pooled["analyses"] == 1
        assert pooled["words"]["total"] == 5


def test_rows_without_metrics_are_backfilled_from_their_analysis_json(corpus, tmp_path):
    result_dir = tmp_path / "out"
    result_dir.mkdir()
    (result_dir / "analysis.json").write_text(json.dumps(mk_analysis()), encoding="utf-8")
    corpus.index_file(str(result_dir), result_dir / "analysis.json")
    corpus.index_analysis("gone", {**mk_analysis("other.webm"), "segments": []})
    with corpus._conn:                     # as if indexed before metrics existed
        corpus._conn.execute("UPDATE analyses SET metrics = NULL")

    result = corpus.metrics()
    assert (result["measured"], result["missing"]) == (1, 1)   # "gone" has no file
    assert result["by_engine"]["espeak"]["words"]["total"] == 5
    stored = {row["id"]: row["metrics"] for row in corpus.analyses()}
    assert stored[str(result_dir)]["words"]["total"] == 5      # persisted
    assert stored["gone"] is None


def test_an_empty_corpus_has_no_metrics(corpus):
    assert corpus.metrics() == {"analyses": 0, "materials": 0, "measured": 0,
                                "missing": 0, "by_engine": {}}


def test_a_version_3_corpus_is_migrated_without_losing_it(tmp_path):
    import sqlite3

    from phonotrainer import db as db_mod

    path = tmp_path / "phonotrainer.db"
    con = sqlite3.connect(path)
    start = db_mod._SCHEMA.index(",\n    -- metrics.compute()")
    end = db_mod._SCHEMA.index("metrics     TEXT") + len("metrics     TEXT")
    v3 = db_mod._SCHEMA[:start] + db_mod._SCHEMA[end:]
    assert "metrics" not in v3
    con.executescript(v3)
    con.execute("INSERT INTO meta VALUES ('schema_version', '3')")
    con.execute("INSERT INTO analyses (id, source, segments, words, indexed_at) "
                "VALUES ('cli-out', 'ep.webm', 0, 0, 'then')")
    con.commit()
    con.close()

    db = Corpus(path)
    try:
        assert db.rebuilt is False
        (row,) = db.analyses()                      # what the CLI indexed is still there
        assert row["id"] == "cli-out" and row["metrics"] is None
        version = db._rows("SELECT value FROM meta WHERE key = 'schema_version'")
        assert version[0]["value"] == str(db_mod.SCHEMA_VERSION)
    finally:
        db.close()


def _write_v3_corpus(path, with_metrics_column=False):
    """A corpus as schema 3 left it (optionally with the column a crashed
    upgrade had already added)."""
    import sqlite3

    from phonotrainer import db as db_mod

    con = sqlite3.connect(path)
    start = db_mod._SCHEMA.index(",\n    -- metrics.compute()")
    end = db_mod._SCHEMA.index("metrics     TEXT") + len("metrics     TEXT")
    con.executescript(db_mod._SCHEMA[:start] + db_mod._SCHEMA[end:])
    if with_metrics_column:
        con.execute("ALTER TABLE analyses ADD COLUMN metrics TEXT")
    con.execute("INSERT INTO meta VALUES ('schema_version', '3')")
    con.execute("INSERT INTO analyses (id, source, segments, words, indexed_at) "
                "VALUES ('cli-out', 'ep.webm', 0, 0, 'then')")
    con.commit()
    con.close()


def test_a_half_migrated_corpus_still_opens(tmp_path):
    """Column already added, version never written (a crash between the two):
    reopening used to fail forever with `duplicate column name`."""
    path = tmp_path / "phonotrainer.db"
    _write_v3_corpus(path, with_metrics_column=True)

    db = Corpus(path)
    try:
        assert db.rebuilt is False
        assert [row["id"] for row in db.analyses()] == ["cli-out"]
    finally:
        db.close()


def test_an_upgrade_that_fails_halfway_leaves_nothing_behind(tmp_path, monkeypatch):
    """The upgrade is one transaction: if it dies after the ALTER, the ALTER is
    rolled back too, and the next open simply upgrades again."""
    import sqlite3

    from phonotrainer import db as db_mod

    path = tmp_path / "phonotrainer.db"
    _write_v3_corpus(path)
    good = db_mod._SCHEMA
    monkeypatch.setattr(db_mod, "_SCHEMA", good + "\nTHIS IS NOT SQL;")
    with pytest.raises(sqlite3.OperationalError):
        Corpus(path)
    monkeypatch.setattr(db_mod, "_SCHEMA", good)

    db = Corpus(path)
    try:
        assert db.rebuilt is False
        assert [row["id"] for row in db.analyses()] == ["cli-out"]
        version = db._rows("SELECT value FROM meta WHERE key = 'schema_version'")
        assert version[0]["value"] == str(db_mod.SCHEMA_VERSION)
    finally:
        db.close()


def test_two_processes_upgrading_at_once_both_succeed(tmp_path, monkeypatch):
    """`phonotrainer ui` and a CLI `analyze` opening the same schema-3 corpus at
    the same time: the second one used to die on `duplicate column name`."""
    import threading
    import time

    from phonotrainer import db as db_mod

    path = tmp_path / "phonotrainer.db"
    _write_v3_corpus(path)
    original = db_mod.Corpus._stored_version
    first_read = threading.Event()
    calls = []

    def slow_first_read(self):
        version = original(self)
        calls.append(version)
        if len(calls) == 1:          # the first opener stalls right after reading
            first_read.set()
            time.sleep(0.4)
        return version

    monkeypatch.setattr(db_mod.Corpus, "_stored_version", slow_first_read)
    errors, opened = [], []

    def open_corpus():
        try:
            opened.append(Corpus(path))
        except Exception as exc:     # noqa: BLE001
            errors.append(exc)

    first = threading.Thread(target=open_corpus)
    first.start()
    assert first_read.wait(5)
    second = threading.Thread(target=open_corpus)
    second.start()
    first.join(10)
    second.join(10)
    for db in opened:
        db.close()
    assert errors == []
    assert len(opened) == 2
