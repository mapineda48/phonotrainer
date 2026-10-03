"""Command line: the corpus overview, without models."""

from click.testing import CliRunner
from conftest import mk_analysis

from phonotrainer.cli import main
from phonotrainer.db import Corpus


def test_the_corpus_overview_puts_its_reduction_next_to_the_references(tmp_path):
    path = tmp_path / "corpus.db"
    db = Corpus(path)
    db.index_analysis("out", mk_analysis())
    db.close()

    # wide enough that rich does not wrap a cell over two lines
    result = CliRunner().invoke(main, ["corpus", "--db", str(path)], env={"COLUMNS": "200"})
    assert result.exit_code == 0, result.output
    assert "Reduction across 1 recording(s)" in result.output
    assert "words that lose a whole segment" in result.output
    assert "25.0 % (1/4)" in result.output
    assert "Johnson 2004" in result.output


def test_an_empty_corpus_says_so(tmp_path):
    result = CliRunner().invoke(main, ["corpus", "--db", str(tmp_path / "c.db")])
    assert result.exit_code == 0
    assert "The corpus is empty" in result.output


def test_only_engines_that_exist_are_accepted(tmp_path):
    result = CliRunner().invoke(main, ["analyze", str(tmp_path / "x.wav"),
                                       "--phone-engine", "allosaurus"])
    assert result.exit_code != 0
    assert "allosaurus" in result.output and "timit61" in result.output
