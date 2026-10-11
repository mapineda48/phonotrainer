# Shared practice history: hand-off for the browser client

Status: hand-off, revision 1, 2026-10-11 (change request CR-01; includes the review fixes of the same day, architecture
25.6 C-72). Owner: software architect. Audience: the owner of the
browser client in `web/`.

**Nothing under `web/` is changed by the Android project, and nothing here is a deadline.** The owner asked that
practice progress sync between the phone, the computer and the browser (spec 7.13, decision D-55). The phone and the
computer do it on their own; this document says exactly what the browser would do to join, whenever its owner chooses
to. Until then the browser keeps its history in `localStorage` exactly as today, and no phone ever claims to share
with it (the phone's words say "your computer", never "the browser").

Sources: `android/docs/product-spec.md` 7.13 (the product rules and the five browser requirements),
`android/docs/server-contract.md` 6.12 and 6.13 (the routes), `android/docs/architecture.md` 7.8 (how the phone does
the same), and today's browser code `web/src/features/practice/quiz.ts` (`STATS_KEY`, `loadStats`, `saveStats`,
`clearStats`) and `PracticePage.tsx` (the answer, the stats card and the reset dialog).

## 1. What the browser is asked to do (spec 7.13, requirements 1 to 5)

1. On adoption, add its existing `phonotrainer:practice-stats` history to the shared history **once**, and never again
   (a reload or a second tab must not add it twice).
2. Add every answer given in Practice to the shared history, in the same per-change shape.
3. Show the shared totals; the line "Kept only in this browser." changes so it no longer claims the history is kept
   only there (the exact words are the web owner's).
4. Make "Forget your practice history?" the shared reset, and say in its text that it clears the history on every
   device.
5. Never read, change or delete the Saved list (the browser has no Saved feature, and none is requested).

Only practice accuracy is shared with the browser: per change, answers given (`seen`) and answers right (`correct`),
the shape `quiz.ts` already keeps.

## 2. How to know the computer supports it

Call `GET /api/progress/practice` (same origin, no credential).
- `200` with `"shared_progress": 1` in the body: supported. Use it.
- `404` (an older PhonoTrainer: the SPA's catch-all answers unknown `/api/` paths with 404; a `POST` there gets
  `405`), `403` (the page is not served from the computer itself: the routes are loopback-only), or a network error
  before adoption: **not supported**; keep today's behaviour and ask again on the next page load.

`reference.api_version >= 7` is a hint only; the route is the authority.

## 3. The routes (server-contract 6.13)

**`GET /api/progress/practice`** reads. **`POST /api/progress/practice`** adds and reads. Same origin only (an `Origin`
of another site gets `403 cross_origin`; `POST` also needs a same-origin `Sec-Fetch-Site`, which `fetch` from the SPA
sends by itself), `Content-Type: application/json`, body at most 16 KiB, at most 100 answers per request.

Request (every part optional; `{}` only reads):

```json
{"baseline": {"id": "00000000-0000-4000-8000-0000000000b2", "at": 1791622800,
              "stats": {"flapping": {"seen": 10, "correct": 7}, "linking": {"seen": 4, "correct": 4}}},
 "reset": {"id": "00000000-0000-4000-8000-0000000000c2", "at": 1791622850},
 "answers": [{"id": "00000000-0000-4000-8000-0000000000a2", "change": "flapping", "correct": true,
              "at": 1791622900}]}
```

The example names every field; a real request never carries a baseline and answers together (6.2).

Answer `200`:

```json
{"shared_progress": 1, "server_time": 1791622960,
 "reset": {"id": "00000000-0000-4000-8000-0000000000c2", "at": 1791622850},
 "totals": {"flapping": {"seen": 12, "correct": 8}}, "accepted": 1, "ignored": 0}
```

- Ids are canonical UUID strings (`crypto.randomUUID()`; `localhost` and `127.0.0.1` are secure contexts).
- `at` is the computer's clock in whole unix seconds. The browser runs on the computer, so
  `Math.floor(Date.now() / 1000)` is that clock.
- `change` is the phenomenon label `quiz.ts` already uses as its key (`"flapping"`); `0 ≤ correct ≤ seen`.
- `reset` in the answer is null when nobody ever reset; `totals` holds every change with answers after the current
  reset; `accepted` and `ignored` are diagnostics only.
- `ignored` counts what the computer did not add (an id it already had, an answer older than the current reset, or
  one of its storage bounds); it is never an error, and the browser treats every item it sent as handled.
- Errors: `400 {"detail", "code": "invalid_request"}` (a malformed body: a defect, never retried unchanged, 6.2),
  `413 too_large` (a body over 16 KiB: a defect of the paging below), `411 length_required` and
  `415 unsupported_type` (the request was not a plain JSON `fetch` with a body: a defect),
  `403 cross_origin`, `403 lan_forbidden`, a `403 {"detail": "cross-origin request"}` without a code (the server's
  existing `Sec-Fetch-Site` filter), `503 store_unavailable` (the computer's copy cannot be read right now), other
  `5xx` and network errors (try again later). The computer never refuses a whole request because a store is full: it
  skips the items over its bounds and reports them in `ignored`.

## 4. The rules the computer applies (server-contract 6.12)

1. Every answer id and every baseline id counts **once**, however often it is sent. Resending is always safe.
2. A reset clears every answer and baseline with `at` at or before the reset's `at`, wherever it was given, and keeps
   later ones; an older reset than the current one changes nothing.
3. A baseline is a device's history from before it shared, counted as given at its `at`.
4. Totals are the sum over every device (phones and the browser).

## 5. The browser's identity and local state

The browser's identity is its **history id**: a UUID created once, at adoption, and used as the id of its baseline. It
is not secret and is never shown. All shared-progress state lives under **one** new `localStorage` key, so it is
written and cleared as a whole:

```json
"phonotrainer:practice-sync" = {
  "version": 1,
  "historyId": "00000000-0000-4000-8000-0000000000b2",
  "baseline": {"at": 1791622800, "stats": {"flapping": {"seen": 10, "correct": 7}}},
  "pending": [{"id": "00000000-0000-4000-8000-0000000000a2", "change": "flapping", "correct": true, "at": 1791622900}],
  "pendingReset": null,
  "totals": {"flapping": {"seen": 11, "correct": 7}},
  "resetAt": null,
  "lastSyncedAt": 1791622850
}
```

`baseline` is null once the computer has it; `pending` holds answers the computer has not confirmed; `pendingReset` a
reset not confirmed yet; `totals`, `resetAt` and `lastSyncedAt` are the computer's last answer.

Every read-modify-write of this key runs inside `navigator.locks.request("phonotrainer:practice-sync", …)` (the Web
Locks API), so two tabs never interleave. The lock is held **only** for the read-modify-write itself, never while a
request is on the network: a slow or hung request must never delay an answer being written (6.3), or an answer could be
lost when its tab closes while it waits. Correctness does not depend on the lock for answers (their ids make a resend
harmless); it does for adoption (step 6.1), which must create exactly one history id.

## 6. Algorithms

**6.1 Adoption (once per browser profile).** Only after section 2 said "supported". Under the lock:
1. If `phonotrainer:practice-sync` exists, adoption already happened: if `phonotrainer:practice-stats` still exists
   (a tab closed between steps 3 and 4), remove it and stop. Never build a second baseline.
2. Create `historyId = crypto.randomUUID()`. Read the old history with `loadStats()` and normalize it the way the
   computer validates it, so one odd entry can never make every later sync fail with `400`: keep only keys matching
   `^[a-z][a-z0-9_]{0,39}$`; `seen = Math.min(Math.floor(seen), 1000000)`, `correct = Math.min(Math.floor(correct),
   seen)` (`loadStats` already clamps both at 0 but accepts fractions and `correct > seen`); drop entries with
   `seen = 0`. If anything is left, `baseline = {at: now, stats}`, else `baseline = null`.
3. Write `phonotrainer:practice-sync` with `pending: []`, `totals` = the old stats (so the screen shows the same
   numbers until the first answer), `resetAt: null`, `lastSyncedAt: null`.
4. Remove `phonotrainer:practice-stats` (`clearStats()`). From now on it is never read or written again: the old
   history lives in `baseline` until the computer has it.
Then run 6.2. If the computer already has a baseline with this id (impossible unless the key was copied), it ignores it.

**6.2 Sync.** One sync at a time per tab; repeat while something is left to send (at most a few rounds):
1. **Under the lock, briefly**: read the key and build the body: `reset` = `pendingReset` if set; then either
   `baseline` = `{id: historyId, at, stats}` if `baseline` is set (and no answers in this request), or else `answers` =
   the first 100 of `pending`. Remember exactly what was put in the body (the ids, whether a baseline and which reset
   id), then release the lock. The baseline goes alone because a baseline and 100 answers together could cross the
   16 KiB limit; the answers follow in the next round.
2. **Without the lock**: `POST /api/progress/practice` with a 10-second timeout (`AbortSignal.timeout(10000)`). On a
   network error, the timeout, `5xx` (including `503 store_unavailable`), `404`, `405` or `403`: change nothing, stop,
   try again at the next trigger (6.4); a resend is harmless because every id counts once.
3. On `400`, `411`, `413` or `415` (a defect: section 3): log it once to the console with the code, then, under the
   lock, remove from the key **what this request carried** (its answers from `pending` by id, the baseline if it
   carried it, the reset if it carried it) and continue with the next round. A request the computer refuses can never
   be accepted unchanged, so keeping it would block every later sync; dropping it costs only what it carried.
4. On `200`, **under the lock again**, re-read the key (another tab may have added answers meanwhile) and apply: remove
   the sent answers from `pending` (by id); if the baseline was sent, set `baseline = null`; if a reset was sent and the
   answer's `reset.at` is at or after it, set `pendingReset = null` (only if `pendingReset` still has the id that was
   sent); set `resetAt = reset?.at ?? null` and drop every `pending` answer with `at <= resetAt` (the computer ignores
   those); store `totals` and `lastSyncedAt = server_time`.

**6.3 An answer.** Where `PracticePage` calls `recordAnswer` and `saveStats` today: under the lock, append
`{id: crypto.randomUUID(), change: item.phenomenon, correct, at: now}` to `pending` and write the key (no request is
ever made while the lock is held, so this never waits on the network), then start a sync debounced by about 2 s.

**6.4 When to sync.** When Practice opens; 2 s after the last answer; when the page becomes visible again
(`visibilitychange`), so answers given on a phone appear; after a reset. No polling is needed.

**6.5 Reset.** The confirmation (section 7) then, under the lock: `pending = []`, `totals = {}`,
`pendingReset = {id: crypto.randomUUID(), at: now}`; sync. Answers given after it count as usual.

**6.6 What to display.** Per change: `totals` plus the `pending` answers (`seen + 1` each, `correct + 1` when right).
With a `pendingReset`, `totals` is already empty, so only answers given after the reset show. The weak-spot weighting
(`1.5 − accuracy`) and every place that reads `stats` today use these numbers.

## 7. What the browser shows (the words are the web owner's)

- Under "Your accuracy so far": instead of "Kept only in this browser.", a line that no longer claims the history stays
  in the browser. A suggestion consistent with the phone's copy: "Shared with your phones through this computer."
  (and, if desired, when it was last synced).
- The reset dialog becomes the shared reset (requirement 4). A suggestion after the phone's copy: title "Forget your
  practice history everywhere?"; body "Your accuracy per change is shared with your phones through this computer. This
  clears every answer given until now, here and on every phone that shares it. Your clips are not touched."; buttons
  "Cancel" and "Forget it".
- When the computer cannot be reached (the server is stopped), the page shows the last totals plus the answers given
  since, as today; nothing more is required.
- No Saved list, and no other new screen.

## 8. Tests to write (vitest, in `web/`)

1. Adoption sends the old `phonotrainer:practice-stats` as one baseline with a new history id, removes the old key,
   and a second load does not send another baseline (requirement 1); an old history with a fraction, `correct > seen`
   or a malformed key is normalized into a baseline the computer accepts.
2. Two tabs adopting at once (two calls racing through a fake `navigator.locks`) create one history id and one
   baseline.
3. A crash between writing the sync key and removing the old key: the next load removes the old key and sends no new
   baseline.
4. An answer is added to `pending` with a UUID and the computer's time, sent, and removed from `pending` only after a
   `200`; a lost answer (network error after the request left) is sent again with the same id.
5. Display = `totals` + `pending`, before and after a sync; weak-spot weights use it.
6. Reset: `pending` and `totals` cleared at once, `pendingReset` sent first, cleared when confirmed; an answer given
   after it counts; `pending` answers older than the computer's `reset.at` are dropped.
7. Server unsupported (`404`), not loopback (`403`) or stopped (network error): before adoption nothing changes and the
   old key stays; after adoption the data stays and is sent later.
8. Paging: 250 pending answers go in three requests of at most 100; a waiting baseline goes in a request of its own,
   before them.
9. No request ever goes to `/api/phone/…` or touches a Saved list.
10. Site data cleared after adoption (both keys gone): the next load adopts again with an empty old history, so it
    sends no baseline, and the totals come back from the computer; nothing is counted twice.
11. The lock is never held across a request: with a `fetch` that never resolves, an answer given in another tab (a
    fake `navigator.locks` shared by two instances) is written at once; the hung request is aborted after 10 s and the
    data is kept for the next sync.
12. A `400` for a page of answers: the page's answers are dropped from `pending` after one console line, the next page
    is sent in the same sync, and later answers keep syncing; an answer given in another tab while that request was out
    is kept.

## 9. Things the browser must not do

- Send its old history twice, or rebuild a baseline once `phonotrainer:practice-sync` exists.
- Infer support from `api_version` alone, keep retrying a `400`, or hold the Web Lock while a request is out.
- Store anything shared in a second `localStorage` key, or keep writing `phonotrainer:practice-stats` after adoption
  (a stray copy of the totals there could be mistaken for history and baselined again by an older build).
- Read or change the Saved list, or call any `/api/phone/` route.

## 10. Notes for the integrator of the Android project

- `tests/test_phone_compat.py` reads `STATS_KEY = "phonotrainer:practice-stats"` and the `{seen, correct}` shape from
  `quiz.ts`, because this hand-off migrates exactly that history. When the web owner adopts it, that guard is expected
  to change with the browser (the integrator of the current wave owns the fix, never by editing `web/`).
- The browser's adoption never blocks an Android work package or gate.
