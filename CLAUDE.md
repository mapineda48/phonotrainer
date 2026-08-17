# CLAUDE.md

PhonoTrainer analyzes English pronunciation phenomena (connected speech) from
video/audio. It is a Python package, `phonotrainer/` (CLI + FastAPI server),
plus a React + TypeScript web UI in `web/`, covered by a pytest suite in
`tests/` and a vitest suite under `web/`. Licensed **GPL-3.0-or-later**.

## Language policy (MANDATORY)

**ALL repository content MUST be in English.** This covers code, identifiers,
comments, docstrings, tests and test names, documentation, UI strings, error
messages, and commit messages — without exception.

No Spanish (or any other language) may be committed. The user communicates in
Spanish and you may reply to them in Spanish, but everything written into the
repository must be English.

This is mandatory, not a preference. If you find Spanish in a file you are
editing, translate it as part of the change.

Note: the subject matter is English phonetics, so IPA symbols, phone labels,
and linguistic examples are content, not language violations.

## Tests

```bash
.venv/bin/pytest -q          # Python suite
cd web && npx vitest run     # web UI suite
make test                    # both
```

## Pointers

- `README.md` — usage, setup, architecture summary.
- `docs/PLAN.md` — original design document and phase plan.
- `references/NOTES.md` — Phase 0 research and the decisions it produced.
- `THIRD-PARTY-NOTICES.md` — why the license is GPL-3.0-or-later; read before
  adding any dependency.
</content>
