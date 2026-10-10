# Reference snapshot exporter

`/api/reference` is the server's vocabulary: the 16 change labels, their families,
descriptions and practice advice, the lexical forms, the IPA tokens and the metric
references. The phone ships a snapshot of it, so all of that is there before the first
pairing and offline, and replaces it at runtime whenever the computer reports another
`api_version` (architecture 15.4 and 7.1 step 5).

```sh
.venv/bin/python android/tools/reference/export.py            # write the asset
.venv/bin/python android/tools/reference/export.py --check    # exit 1 if it is stale
.venv/bin/python -B -m pytest -q -p no:cacheprovider android/tools/reference
```

Run it with the repository's virtual environment after any server change that touches the
reference (and commit the asset with it). From a worktree it imports that worktree's
`phonotrainer` package, not an installed copy.

Output: `android/core/content/src/main/assets/reference/reference.json`, the whole response
body in the server's key order, two-space indent, final newline. The only normalisation is the
order of `ipa_tokens` inside one length (the server builds it from a set): longest first, then
code point order.

It is harmless to run anywhere:
- the app is built with `JobStore(<tmp>/workspace, corpus=None)` on a temporary directory,
  and the database, downloads, web build and allowed roots all point inside it too; never
  `create_app()`'s defaults, whose `data/phonotrainer.db` is relative to the working
  directory (from the repository root, the owner's corpus);
- the request comes from loopback, through the test client: no socket is opened;
- the bytecode cache is off, the temporary directory is removed, and the only file written is
  the asset (never under `web/`). A test runs it with an empty working directory, home and
  temporary directory and checks all three stay empty and `git status --ignored` is unchanged.
