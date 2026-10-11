# PhonoTrainer for Android: server contract

Status: v1 contract, revision 3, 2026-10-10 (revisions 1 and 2: the same day). Owner: software architect.

**Revision 3 notes (pre-implementation closure).** No route, field or code changed. Spec revision 3 gave the job codes
`live_stream` and `too_long` their own copy (spec 8.6, D-40), so the "Spec 8.6" cells of 8.2 rows 4 and 5 name it, and
the 1-hour default of `max_link_duration_s` is confirmed (D-43). Architecture 25.4 lists the closure.

**Revision 2 notes.** These changes answer the security, Android-platform and delivery reviews of revision 1. Each
finding and its disposition is listed in architecture section 25 (Review log).
1. **The phone proves who answers before it trusts it (6.2)**: `hello` takes `device_id` and a `challenge` and
   returns an HMAC `proof` keyed by the device's key, plus `revoked` and `server_time`. A host that only claims the
   paired server id gets nothing from the phone.
2. **Requests are signed, not carrying a bearer token (4.3)**: `Authorization: PT1 …` carries an HMAC of the method,
   path, time and a nonce. The token crosses the network once, in the pairing answer. Replays are refused.
3. **Link downloads are confined (4.5, 9)**: phone-origin downloads use only site-specific extractors (no generic
   extractor), refuse live streams, are bounded in size and duration, re-check the link before every download and
   re-run, and refuse NAT64 and 6to4 targets. New job codes `too_long`, `live_stream`, `refused_address` (8.2).
4. **Uploads cannot wedge or flood (4.7, 6.6)**: a body that sends nothing for 30 s is aborted, a newer request for
   the same key replaces an idle older one, declared bytes are reserved against free space, and sessions and channels
   are bounded per device.
5. **Phone access is tied to approved networks (2.1, 4.4, 5.4)** and the loopback Host rule against DNS rebinding
   applies in every mode (1.3, 4.4).
6. **The smoke test talks to a host-side fake computer (12)** built from the real server code with fake analysis and
   download functions, on throwaway directories, so it can never run the pipeline, touch the owner's corpus database
   or reach a website.
7. Smaller fixes: `phone on` on a server started without phone access (2.2), terminal lines that name where a pairing
   came from (2.2), the default computer name no longer reveals the host name (3.2), access log off with phone
   access on (3.3), `device_id` only on own jobs (6.3), unknown media facts as 0 (6.4.3), the cancel message (8.2),
   the downloader version string (3.4).

Inputs: `android/docs/product-spec.md` (the S-nn requirements, 7.x and 11.x; it fixes behaviour),
`android/docs/architecture.md` (the client that consumes this contract; it cites this document as "server-contract
N"), `android/docs/work-plan.md` (WP-01 builds v1-core, WP-27 builds v1-complete), and the server as it is today
(`phonotrainer/server.py`, `jobs.py`, `cli.py`, `download.py`, `tests/`).

This document fixes, for every v1-core and v1-complete server requirement: the command line, where state and secrets
live, the access rules for LAN clients, the UDP discovery datagram, every HTTP and WebSocket route with its request,
response, status and error codes, idempotency and versioning, the Python module layout and the pytest plan.

Conventions
- "MUST" / "SHOULD" as in RFC 2119. "Phone" means any paired client of the phone API; "browser client" is the SPA in
  `web/`.
- Example addresses use the documentation range 192.0.2.0/24; example ids are made up. Nothing in this document is a
  real address, id, code, credential or personal path. The two product constants are the HTTP port 8000 (the
  existing default) and the discovery port 45317/udp.
- JSON examples are normative in shape: field names, types and nullability. Field order is not significant.
- "Tier" is the spec's release tier (spec 14.1): **core** = v1-core, **complete** = v1-complete.

---------------------------------------------------------------------------------------------------------------

## 1. Scope, versions and compatibility

### 1.1 What this contract covers

| Id | Requirement (spec 12) | Tier | Sections |
|---|---|---|---|
| S-01 | Phone access, opt-in, remembered | core | 2.1, 3 |
| S-02 | Identity, library id, capabilities, identity-only answer, health never waits on the worker | core | 3, 6.2 |
| S-03 | UDP discovery | core | 5 |
| S-04 | Pairing and device management from the terminal | core | 2.2, 2.3, 6.2 |
| S-05 | Authentication (signed requests) of every non-loopback call, artifact and channel | core | 4.3, 7 |
| S-06 | LAN restrictions, no absolute paths | core | 4.2, 4.6 |
| S-07 | SSRF guard for links, and confined phone-origin downloads | core | 4.5, 6.5, 6.7, 9 |
| S-08 | Host validation (and the loopback Host rule in every mode) | core | 1.3, 4.4 |
| S-09 | Idempotent creation, key kept with the job, submitting device recorded | core | 6.1, 6.5, 6.6, 6.7 |
| S-10 | Resumable, bounded uploads | complete (core: single bounded upload) | 6.6 |
| S-11 | Sync listing | core | 6.3, 6.4 |
| S-12 | Phone renditions, phone video on request | complete | 6.4 |
| S-13 | Machine-readable job state, error codes, clean messages, site | core | 6.3, 8 |
| S-14 | Job channel for phones; lean events | core (lean events delivered in core, 7) | 7 |
| S-15 | `interrupted` code | core | 8.2 |
| S-16 | Record yourself for phones | complete | 6.9 |
| S-17 | Reference for phones, cacheable by API version | core | 6.8 |
| S-21 | Compatibility with the browser client | core | 1.3, 1.4 |
| S-22 | Re-run of own failed, cancelled or interrupted jobs | core | 6.7 |
| S-23 | Link path hardening | core | 9 |

Not in this document: S-18 is build tooling (architecture 15); S-20 is the QA link gate (work-plan WP-25); S-L1 to
S-L5 are Later.

### 1.2 API versions

| `API_VERSION` | Ships with | Adds |
|---|---|---|
| 5 | today | record yourself (unchanged) |
| **6** | WP-01 (core) | `/api/phone/*`, phone access, discovery, pairing, listing, idempotent creation, re-run, phone channel, downloader hardening |
| **7** | WP-27 (complete) | renditions, phone video on request, resumable uploads, record yourself for phones; the matching capability flags turn `true` |

Rules:
1. `phonotrainer/server.py` keeps one `API_VERSION`; its comment block gains the lines for 6 and 7.
2. The browser client checks `reference.api_version >= 5` (`REQUIRED_API_VERSION = 5`, `web/src/api.ts`); 6 and 7
   pass that check unchanged. A pytest guards it (11.3, `test_phone_compat.py`).
3. The phone requires `api_version >= 6` (`MIN_SERVER_API`, architecture 5.4). It never infers v1-complete features
   from the number: it reads the capability flags of `health` (6.2). A v6 server simply reports them `false`.
4. The server requires `app_version >= MIN_APP_VERSION` (an integer, the app's `versionCode`; 1 in v1). Pairing
   refuses an older app (`app_too_old`, 6.2) and `health` reports the value so the phone can show "Update this app".
5. Inside `/api/phone/`, changes are additive only: new routes, new optional request fields, new response fields.
   Removing or renaming a field or changing its meaning requires a new prefix (`/api/phone/v2/`), which v1 does not
   plan.
6. Unknown request fields are ignored by the server (`extra="ignore"` models); unknown response fields are ignored by
   the phone (architecture 5.4).

### 1.3 What does not change for the browser client (S-21)

With phone access off (the default) the server behaves exactly as today, byte for byte, for every existing route,
with one deliberate exception (a request whose `Host` header names a host that is not this machine, see below):
- `phonotrainer ui` binds `127.0.0.1:8000` by default; `--host` keeps its meaning.
- Every existing route, status code and payload under `/api/` (including `GET /api/health` with its `workspace` field,
  `GET /api/jobs` with `result_dir`, 422 validation lists) and `/ws/jobs` are unchanged. The only middleware installed
  in this mode is `LoopbackHostGuard` (4.4): a request from a loopback peer whose `Host` is not an IP literal,
  `localhost`, `*.localhost`, the machine's host name or `<hostname>.local` gets `400 bad_host`. Without it, a page
  in the owner's browser could use DNS rebinding to read `/api/browse` of the home directory (pre-existing gap,
  security review M-9). The browser client, the Vite proxy and `curl` send `127.0.0.1:<port>` or
  `localhost:<port>`, so nothing they do changes.
- The progress message texts are unchanged, in particular `Downloading from YouTube… N%` for every site:
  `jobs.py` (`_DOWNLOAD_RE`, `_STAGE_PERCENT`) and the SPA (`web/src/jobs/stages.ts`, `PREFIXES` and `DOWNLOAD_RE`)
  parse them. The site a link came from reaches the phone as a field (`source.site`, 6.3), never through the text.
- `job.json` gains optional fields (`device_id`, `client_request_id`, `rerun_of`); older files load as before and
  `Job.to_public()` is unchanged.

The only change every user sees is S-23 (section 9): new downloads prefer H.264, then VP9, then other codecs, and
yt-dlp is given a JavaScript runtime when one is installed. It changes no route or payload.

### 1.4 What changes with phone access on

- The process listens on every IPv4 interface (`0.0.0.0`) unless `--host` names one, but answers LAN clients only on
  the addresses of **approved networks** (2.1): a LAN request that arrives on any other local address gets
  `403 lan_forbidden`, and discovery answers only sources inside an approved network.
- Loopback clients keep the whole existing API with no credential (the browser client works unchanged on the computer
  itself). Non-loopback clients reach only `/api/phone/*` (4.2).
- Host validation applies to every request (4.4); uvicorn's proxy-header handling and its access log are turned off
  (3.3).
- The discovery responder runs (5).

---------------------------------------------------------------------------------------------------------------

## 2. Phone access and the operator's command line (S-01, S-04)

### 2.1 `phonotrainer ui`

New options on the existing command (`phonotrainer/cli.py`):

| Option | Effect |
|---|---|
| `--phone-access` | turns phone access on, **remembers it** (`server.json`, 3.2), approves the networks the computer is on now (below) and starts the server with it |
| `--no-phone-access` | turns it off, remembers it, starts without it |
| `--phone-interface <name>` | repeatable; also approves a virtual interface or a public network, both excluded by default (below); without `--phone-access`, with phone access already on, it approves only the interfaces it names |
| neither | uses the remembered value; the default for a computer that never chose is off (S-01, Q-5 default) |

**Approved networks** (security review M-6). Phone access stays on across restarts (Q-5), but a laptop must not
expose it on a café, hotel or office Wi-Fi it joins later. Turning phone access on (`--phone-access` or
`phonotrainer phone on`) records, in `server.json`, one entry per up IPv4 interface that is not loopback and not a
virtual interface (one the kernel reports as virtual: `/sys/class/net/<name>` under `/sys/devices/virtual/`, or a
hardware type other than Ethernet; or one whose name starts with `docker`, `br-`, `virbr`, `veth`, `tun`, `tap`,
`wg`, `tailscale`, `zt`, `ppp`, `nordlynx`, `proton`, `cscotun`, `gpd`, `ipsec`, `vboxnet`, `vmnet`, `podman`,
`cni`, `lxcbr`, `lxdbr`, `flannel`, `cali`, `vxlan`, `ham`, `nebula`) and not on a public network (one whose network
is global unicast: a computer plugged straight into an ISP modem, a campus network with public addressing; `phone on`
and `ui --phone-access` print one line per interface left out for this reason), unless named with
`--phone-interface`: the interface name, the network (address and prefix, for example `192.0.2.0/24`) and the MAC
address of that network's default gateway when the kernel knows it (`/proc/net/route` and `/proc/net/arp`, read by
`netinfo`, 10.1; omitted when unknown). At each start, and every 30 s while running, the server computes the
**active approved addresses**: the local addresses of up interfaces whose (name, network, gateway MAC) match an
approved entry (a missing MAC on either side matches on name and network). If phone access is remembered on but no
approved network is up, the server starts with phone access **suspended**: it binds and writes `runtime.json` as with
phone access on, but no local address is approved, so every LAN request gets `403 lan_forbidden`, no responder runs,
loopback works as always, and it prints the lines below. It becomes active by itself within 30 s once an approved
network comes up or the operator approves the current one. A suspended server still listens on every interface on
purpose: a phone that reaches it gets `403 lan_forbidden` and can say "Your computer answered, but phone access is off
there or not approved for this network." (F-02-AC12, spec D-42) instead of reporting a computer that never answered;
the connection limits of 4.7 bound what another network can hold open.

```
Phone access is ON, but this computer is not on a network you approved for phones.
Run `phonotrainer phone on` here to approve this network.
```

`phonotrainer phone on` run again on a new network adds that network (it never removes one); `phonotrainer phone off`
clears the list. The list lives only in the configuration directory (3.1), never in the repository.

With phone access on:
- bind address: `--host` if given, otherwise `0.0.0.0`; the browser opens `http://127.0.0.1:<port>` (not the
  wildcard);
- `runtime.json` (3.2) is written at start and removed at a clean stop;
- the start banner (stdout, never a log file) says, in this wording:

```
PhonoTrainer UI → http://127.0.0.1:8000  (workspace: workspace)
Phone access is ON: paired phones on this Wi-Fi can reach this computer at
  192.0.2.10:8000   (wlan0)
Pair a phone:            phonotrainer phone pair
Turn phone access off:   phonotrainer phone off   (or start with --no-phone-access)
```

  The addresses are the active approved addresses (above), one line each with the interface name. No secret is ever
  printed by `ui`.
- uvicorn runs with `access_log=False` (its access log would print every client address and request path, 3.3).
- `--reload` passes the setting to the reloading factory through `PHONOTRAINER_PHONE_ACCESS=1` (as the workspace and
  roots already are).

Phone access can also be switched while the server runs (`phonotrainer phone on|off`), **but only in a process that
started with phone access on** (or suspended): such a process re-reads `server.json` when its modification time
changes (checked at most once per second). Turned off live, every phone route except `hello` answers
`403 phone_access_off` and discovery stops; the LAN restrictions of 4.2 stay in force for the life of the process
(its sockets still listen on every interface). A process started with phone access off is bound to loopback and has
no phone routes, middleware, warmer or responder, so it cannot follow a live `phone on` (delivery review D-16); the
CLI detects this (no `runtime.json`, 3.2) and says so (2.2).

### 2.2 `phonotrainer phone` (terminal-only device management, S-04)

A new click group, registered from `phonotrainer/cli.py`, implemented in `phonotrainer/phone/cli.py`. It works on the
configuration directory (3.1) directly; it never calls the HTTP API, so pairing and device management have no network
surface of their own.

| Command | Does | Output (stdout) and exit code |
|---|---|---|
| `phonotrainer phone status` | phone access on, suspended or off, the computer's name, the first 8 characters of the server id, the approved networks (interface and network), the number of paired phones, whether a server with phone access is running (from `runtime.json` and a live pid) and its port | 0 |
| `phonotrainer phone on` | sets the remembered value and approves the networks the computer is on now (2.1), printing one "Approved for phones: <interface> <network>" line each; a running server that started with phone access on or suspended follows within a second; with no such server running it adds "Restart `phonotrainer ui` to apply it." | "Phone access is now ON." 0 |
| `phonotrainer phone off` | sets the remembered value and clears the approved networks; a running server follows within a second | "Phone access is now OFF." 0 |
| `phonotrainer phone pair [--no-wait]` | creates a new pairing code (2.3), replacing any previous one; prints the code, its validity and the addresses; without `--no-wait` it waits until a phone pairs or the code expires, printing each wrong attempt; Ctrl-C deletes the code | see below; 0 paired or code shown, 1 phone access off or no running server with phone access, 3 expired without pairing, 4 locked after wrong attempts |
| `phonotrainer phone list [--all]` | paired phones: name, device id prefix, paired at, last seen (relative), app version; `--all` adds revoked ones | 0 |
| `phonotrainer phone revoke <device-id-prefix or name>` | revokes one phone; its credential stops working at its next request | 0; 1 not found; 2 ambiguous (lists the matches) |
| `phonotrainer phone revoke --all [--yes]` | revokes every phone, after a "Revoke all N phones? [y/N]" prompt unless `--yes` | 0 |
| `phonotrainer phone name "<name>"` | renames the computer as phones see it (1 to 40 characters, control and format characters removed, 2.3) | 0; 1 invalid |

`phone pair` output, exact wording:

```
Pairing code: 482 913   (valid for 5 minutes, single use)
On the phone: Connect to your computer, pick "PhonoTrainer computer", or enter one of these addresses:
  192.0.2.10:8000   (wlan0)
Waiting for a phone… (Ctrl-C cancels the code)
Wrong code tried 1 time (another device on this network may be guessing).
Paired: Pixel 9 (from 192.0.2.23)
```

- "Paired: … (from <address>)" names the source address of the pairing request, so the operator can compare it with
  the address the phone shows in Connection details (security review M-2: a rogue that relays the code from another
  address becomes visible). The server hands that address to the waiting command through `pairing.json` (3.2), which
  the command deletes as soon as it has read it; nothing else stores it.
- After the fifth wrong attempt it prints "The code is now locked after 5 wrong attempts (another device on this
  network may be guessing). Run `phonotrainer phone pair` for a new code." and exits 4 (security review L-2).
- With phone access remembered off it prints "Phone access is OFF. Turn it on with: phonotrainer phone on" and exits
  1 without creating a code (a code the server would refuse with `phone_access_off` is never shown, delivery review
  D-16).
- With phone access on but no running server with phone access it prints "PhonoTrainer is not running with phone
  access on. Start it with: phonotrainer ui --phone-access" and exits 1 without creating a code.

The command lines the phone shows in its Connect instruction card (spec F-02 behaviour 2) are these product strings,
with no address: `phonotrainer ui --phone-access` and `phonotrainer phone pair`.

### 2.3 Pairing code rules

- 6 decimal digits from `secrets.randbelow(10**6)`, zero padded; shown grouped "482 913"; the phone accepts the six
  digits without the space.
- Single use; valid for 300 s from creation (`PAIRING_CODE_TTL_S`); one code at a time (a new `phone pair` replaces it).
- Stored only as `sha256(salt ‖ code)` with a 16-byte random salt (3.2); the plain code exists only in the terminal.
- Five wrong attempts lock the code (`PAIRING_MAX_WRONG = 5`): the sixth and later attempts get `code_locked`, even
  with the right digits, until a new code is created (spec F-02-AC3).
- An attempt when no code exists answers `code_invalid` (no oracle about whether a code is active).
- Rate limits on `POST /api/phone/pair`, in memory: 10 attempts per minute per source address and 30 per minute in
  total; above them `429 rate_limited` with `Retry-After`.
- Text that reaches a terminal or a phone screen (`device_name`, the computer's `name`) is cleaned the same way:
  trimmed, Unicode categories `Cc`, `Cf`, `Zl` and `Zp` removed (control characters, bidirectional overrides,
  zero-width characters, line and paragraph separators), so neither side can be spoofed (security review L-2).

---------------------------------------------------------------------------------------------------------------

## 3. Identity, state and secrets: where they live (S-01, S-02, S-04)

### 3.1 The configuration directory

`PHONOTRAINER_CONFIG_DIR` if set; otherwise `$XDG_CONFIG_HOME/phonotrainer`; otherwise `~/.config/phonotrainer`.
It is outside the repository and outside every workspace, so a workspace switch never breaks pairing (D-34) and
nothing in it can be committed by accident. The directory is created with mode `0700`; every file in it is written
with mode `0600` through a temporary file and `os.replace` (atomic); read-modify-write cycles hold an exclusive
`fcntl.flock` on `<dir>/.lock`, so the CLI and a running server never lose each other's writes.

Every open (not only the creation) checks that the directory and `devices.json` belong to the current user and are
not group- or world-writable; a wider mode is tightened to `0700`/`0600` with one warning line, and a directory owned
by another user is refused with an error that names no path beyond "the PhonoTrainer configuration directory"
(security review L-9). `devices.json` holds the per-device MAC keys (3.2), so it is a secret file.

The tests set `PHONOTRAINER_CONFIG_DIR` to a temporary directory for every test (autouse fixture, 11.2), and
`create_app()` never touches the directory unless phone settings are passed to it (10.2), so the existing suite cannot
write to a real configuration directory.

### 3.2 Files

**`server.json`** (machine identity and the operator's choices; written by the CLI and at first start)

```json
{
  "server_id": "srv_example1",
  "name": "PhonoTrainer computer",
  "phone_access": true,
  "approved_networks": [
    {"interface": "wlan0", "network": "192.0.2.0/24", "gateway_mac": "00:00:5e:00:53:01",
     "approved_at": "2026-10-10T09:00:00+00:00"}
  ],
  "max_upload_bytes": 2147483648,
  "max_link_duration_s": 3600,
  "created_at": "2026-10-10T09:00:00+00:00"
}
```
- `server_id`: `srv_` + 26 lower-case base32 characters (16 random bytes), created once per machine and never changed.
- `name`: default "PhonoTrainer computer" (never the host name, which often carries the owner's name and would be
  broadcast in every discovery reply, security review L-3); editable with `phone name`; the first start prints
  "Rename this computer for your phone with: phonotrainer phone name \"…\"".
- `approved_networks`: 2.1; `gateway_mac` is omitted when unknown (the example uses the documentation MAC range).
- `max_upload_bytes`: default 2 GiB; editable by hand (documented in the README).
- `max_link_duration_s`: default 3600; the longest video a phone-origin link may download (9); editable by hand.

**`devices.json`** (paired phones; written by the server at pairing, last-seen updates and self-unpair; by the CLI at
revoke)

```json
{
  "devices": [
    {
      "device_id": "dev_example1",
      "name": "Pixel 9",
      "key_sha256": "9b1d…64 hex…",
      "paired_at": "2026-10-10T09:12:03+00:00",
      "last_seen_at": "2026-10-10T09:40:11+00:00",
      "app_version": 1,
      "app_version_name": "1.0.0",
      "revoked_at": null
    }
  ]
}
```
- The token is never stored. `key_sha256` is `sha256(token)` in hex: the device's **MAC key** `K` (its 32 raw bytes),
  with which the server checks request signatures (4.3) and computes identity proofs (6.2). The phone derives the
  same `K` from its token. A leak of this file lets the holder sign requests as the phone, so it is protected like a
  secret (3.1, 3.3); revoking the phone (or pairing it again) replaces the key.
- A revoked device keeps its row (`revoked_at` set) so that "Connect again" can re-activate the same `device_id`
  (6.2, `previous_device_id` with its proof).
- `last_seen_at` is written at most once per minute per device.
- The server re-reads the file when its modification time changes (checked on each authenticated request with one
  `stat`), so a CLI revoke applies at the phone's next request (spec F-02-AC8, QT-8).

**`pairing.json`** (the current code; written by `phone pair`, updated by the server on each attempt; on success the
server replaces the code fields with the outcome, which the waiting `phone pair` reads and then deletes the file)

```json
{"salt": "8c2f…32 hex…", "code_sha256": "3a7e…64 hex…", "created_at": "2026-10-10T09:10:00+00:00",
 "expires_at": "2026-10-10T09:15:00+00:00", "wrong_attempts": 2, "locked": false}
```
On success: `{"paired": {"name": "Pixel 9", "from": "192.0.2.23"}}`. The source address lives here only until the
waiting command prints it (2.2); a server start deletes a leftover file.

**`runtime.json`** (written by a server running with phone access on or suspended, removed at a clean stop; read by
`phone status`, `phone on` and `phone pair`)

```json
{"pid": 41234, "port": 8000, "started_at": "2026-10-10T09:00:00+00:00", "state": "on"}
```
`state` is `on` or `suspended` (2.1).

**`<workspace>/library.json`** (the library id of a workspace; created the first time a phone-enabled server opens
the workspace)

```json
{"library_id": "lib_example1", "created_at": "2026-10-10T09:00:00+00:00"}
```
The job store ignores it (it only loads directories with a `job.json`).

**`<workspace>/.phone-uploads/`** (upload sessions, 6.6): `<client_request_id>.json` (session) and
`<client_request_id>.part` (bytes received). Same filesystem as the jobs, so a finished upload is moved into its job
with a rename (no second copy, S-10). Session files are named by the key alone, so a key held by another device's
open upload session (a UUID clash) answers `409 key_conflict` (6.1).

**`<workspace>/<job id>/phone/`** (derived per-job files, regenerable at any time): `checksums.json`, `media.json`
(6.4), and in v1-complete the renditions and `renditions.json`. Deleting a job deletes them with its directory.

### 3.3 Secrets and what never leaves the computer

| Value | Where | Protection |
|---|---|---|
| Device tokens | only on the phone; they cross the network once, in the pairing answer (6.2) | never stored on the computer |
| Device MAC keys `K = sha256(token)` | `devices.json` | `0600` file in a `0700` directory checked at every open (3.1); compared with `hmac.compare_digest` |
| Pairing code | the terminal; `sha256(salt ‖ code)` on disk | single use, 5 minutes, 5 wrong attempts |
| Server id, library id, device ids | config dir, workspace, the phone | not secret, never committed (outside the repository) |
| Addresses | printed in the terminal at runtime only (the banner, `phone pair`, the "Paired: … (from …)" line) | never written to a log, never in an HTTP or UDP payload (the phone takes the address from the reply's source); the only file that ever holds one is `pairing.json` for the seconds 3.2 describes |

Rules: no token, key, code or hash in any log line, exception message, HTTP response other than the pairing grant, or
CLI output. With phone access on (or suspended) uvicorn's access log is off (`access_log=False`), because it would
print every client address and path (security review L-5); with phone access off it stays at its default (loopback
only, unchanged behaviour, 1.3). The README documents the configuration directory without any personal path.

### 3.4 Capabilities

Computed at start and cached, so `health` never imports a heavy module or waits on the analysis worker (S-02):

| Flag | Meaning | How |
|---|---|---|
| `link_downloads` | the downloader can be used | `importlib.util.find_spec("yt_dlp")` |
| `downloader` | `{name, version, js_runtime, challenge_solver}` | `version` is the string `importlib.metadata.version("yt-dlp")` returns, unchanged (for example `2026.8.19`, not zero-padded; delivery review D-28); the runtime chosen by 9; `find_spec("yt_dlp_ejs")` |
| `dialogue_separation` | separation can run | `find_spec("demucs")` (never imports torch) |
| `discovery` | the UDP responder is bound | set by the responder |
| `renditions` | compact clip data, Opus audio and posters are produced (complete) | `true` from API 7 when ffmpeg is present |
| `phone_video` | phone video on request (complete) | `true` from API 7 when ffmpeg has `libopenh264` (checked once with `ffmpeg -encoders`) |
| `resumable_uploads` | `GET`/`PATCH` upload sessions (complete) | `true` from API 7 |
| `record_compare` | `/api/phone/jobs/{id}/contour` and `/compare` (complete) | `true` from API 7 |
| `max_upload_bytes`, `max_chunk_bytes` | limits of 6.6 | `server.json`; 8 MiB |
| `max_link_duration_s` | the longest phone-origin link video (9) | `server.json` |
| `free_bytes` | free space of the workspace's filesystem minus the open upload reservations (4.7) | `shutil.disk_usage`, cached for 5 s |

`low_on_space` (top-level in `health`) is `free_bytes < LOW_SPACE_BYTES` (4 GiB).

---------------------------------------------------------------------------------------------------------------

## 4. Access control (S-05 to S-08, spec 11.5)

### 4.1 Loopback and LAN clients

A request is **loopback** when its peer address (`request.client.host`, the ASGI `client`) parses as an IP address in
`127.0.0.0/8`, `::1` or an IPv4-mapped loopback. Anything else, including a peer that is not an IP literal, is a
**LAN** client. uvicorn runs with `proxy_headers=False` in every mode (phone access off included), so no header can
change the peer.

### 4.2 Route matrix

| Route | Phone access off, loopback | Phone access off, LAN (`--host` by hand) | On, loopback | On, LAN on an approved address | On (or suspended), LAN on any other address |
|---|---|---|---|---|---|
| Every existing `/api/*` route, `/ws/jobs`, the SPA | as today | as today (README warns) | as today | `403 lan_forbidden` | `403 lan_forbidden` |
| `GET /api/phone/hello` | `phone_access: false` | `phone_access: false` | full answer | full answer | `403 lan_forbidden` |
| `POST /api/phone/pair` | `403 phone_access_off` | `403 phone_access_off` | allowed | allowed | `403 lan_forbidden` |
| Every other `/api/phone/*` route and `/api/phone/events` | `403 phone_access_off` | `403 phone_access_off` | signature required | signature required | `403 lan_forbidden` |

This is an allowlist: a LAN client can never reach the disk browser, path creation, import, review writes, deletion
of finished analyses, the corpus endpoints, `/api/health` (which carries the workspace path) or `/ws/jobs`. Every
item of S-06 follows from it. Phone routes enforce their own ownership rules (6.7). "Approved address" is 2.1.

`403 lan_forbidden` body: `{"detail": "This part of PhonoTrainer is only available on the computer itself.",
"code": "lan_forbidden"}`; a forbidden WebSocket upgrade is closed before accept (HTTP 403).

`PhoneAccessMiddleware` is a **pure ASGI** middleware that sees `http` and `websocket` scopes alike (FastAPI's
`@app.middleware("http")`, like the existing `same_origin_only`, never sees WebSocket upgrades). It matches the
route on the percent-decoded path after refusing any path with a `.` or `..` segment or an encoded slash
(`400 invalid_request`), so `/api/phone/../browse` and `/api/phone/%2e%2e/health` never reach another route. An
unknown `/api/phone/…` path answers a JSON `404 {"code": "unknown_route"}`, never the SPA (security review L-10).

### 4.3 Authentication: signed requests (S-05)

The phone never sends its token after pairing. It signs each request with its MAC key `K = sha256(token)` (32 raw
bytes, 3.2), so a token read once on the air is not a reusable credential and a captured request cannot be replayed
(security review M-1).

- Header on every phone route except `hello` and `pair`, on artifact downloads and on the `/api/phone/events`
  upgrade request (no query-string or cookie form exists):

  ```
  Authorization: PT1 device=<device_id>, ts=<unix seconds>, nonce=<32 lower-case hex>, mac=<64 lower-case hex>
  ```
  `mac = hex(HMAC-SHA256(K, "pt-req-v1|" + METHOD + "|" + path_and_query + "|" + ts + "|" + nonce))`, where
  `METHOD` is upper case and `path_and_query` is the request target exactly as sent, still percent-encoded (OkHttp's
  `encodedPath` plus `?encodedQuery`; on the server the ASGI `raw_path` plus `?query_string`), for example
  `/api/phone/jobs/20261010-091503-4be1/artifacts/audio_mix`. For the WebSocket upgrade it is `/api/phone/events`. The body is not covered: an active attacker on the
  network is out of scope in v1 (4.8), and upload bodies are bound by their declared SHA-256 anyway.
- The server accepts the request when the device exists and is not revoked, `|ts − server time| ≤ 300 s`, the nonce
  was not seen for that device in the last 600 s (an in-memory cache per device, bounded at 4,096 entries), and the
  MAC matches (`hmac.compare_digest`).
- Token format (the pairing secret, 6.2): `pt1_` + 43 base64url characters (32 random bytes,
  `secrets.token_urlsafe(32)`).
- Missing or malformed header, unknown or revoked device, wrong MAC, or a replayed nonce:
  `401 {"detail": "This phone is not paired with this computer.", "code": "credential_rejected"}` with
  `WWW-Authenticate: PT1`. This is the stable code the phone maps to "Connect again" (spec 7.4); there is no separate
  code for a missing header.
- A timestamp outside the window: `401 {"detail": "This phone's clock differs from this computer's.", "code":
  "clock_skew", "server_time": <unix seconds>}`. The phone stores the offset (it also learns it from every `hello`,
  6.2) and retries once; this is never "Connect again".
- The same rule applies to loopback clients on phone routes (the device-QA kit and the emulator smoke test use a real
  pairing).
- A successful request updates `last_seen_at` (throttled, 3.2).

### 4.4 Host validation (S-08)

Two rules, applied by two middlewares:
- **`LoopbackHostGuard` (every mode, also with phone access off; 1.3)**: a request or WebSocket upgrade from a
  loopback peer, or one that arrived on a loopback local address (ASGI `server`) whatever its peer, MUST carry a
  `Host` that is an IP literal (any IPv4 or bracketed IPv6 address: DNS rebinding needs a name, so a literal is safe;
  the emulator reaches the host's loopback with `Host: 10.0.2.2:<port>`, 12), `localhost`, `*.localhost`, the
  machine's host name or `<hostname>.local`; any port. Otherwise
  `400 {"detail": "Unexpected Host header.", "code": "bad_host"}` (security review M-9). The existing
  `same_origin_only` and `_ws_origin_allowed` checks stay as they are behind it.
- **LAN rule (phone access on)**: the `Host` header MUST be an IPv4 literal equal to the local address the
  connection arrived on (the ASGI `server` tuple), with the port equal to the listening port or omitted, and that
  address MUST be an active approved address (2.1). Host names are refused, which defeats DNS rebinding against the
  LAN address. A refused Host gives `400 bad_host`; for a WebSocket, the upgrade is closed before accept. The peer
  MUST lie inside the network of the approved interface it reached and MUST NOT be global unicast unless that network
  is (the rule of 5.4); otherwise `403 lan_forbidden`.

### 4.5 SSRF guard for links (S-07)

`ssrf.check_link(url, resolver)` runs at three moments for every phone-origin link: at creation
(`POST /api/phone/jobs/link`, before a job exists), at a re-run (6.7), and immediately before the download starts
(the phone-origin download wrapper of 9), because DNS answers can change between those moments (security review
H-2c). Rules:
1. `url` is trimmed, at most 2,048 characters, scheme `http` or `https`, with a host and no user-info part; otherwise
   `400 invalid_url`.
2. The host (IDNA-encoded) is resolved with `getaddrinfo` (the resolver is injectable; 3 s limit). If the host is an
   IP literal it is used as is.
3. Every resolved address is normalized first: IPv4-mapped IPv6 is unwrapped; NAT64 (`64:ff9b::/96`,
   `64:ff9b:1::/48`), 6to4 (`2002::/16`) and Teredo (`2001::/32`) addresses are refused outright (Python's
   `is_global` is `True` for a NAT64 address that embeds a private IPv4, checked on the project's Python 3.12).
   Every remaining address MUST be global unicast: `ipaddress.ip_address(a).is_global and not .is_multicast`.
   Loopback, private, link-local, CGNAT, reserved, documentation and multicast targets give
   `400 {"detail": "This link points to a private or local address.", "code": "refused_address"}` at creation; at a
   re-run the same `400 refused_address`; before a download, the job fails with the job code `refused_address`
   (8.2).
4. Resolution failure or timeout gives `503 cannot_check_link` (transient: the phone retries on its schedule; a link is
   never accepted unchecked). Before a download it fails the job with `network`.

The downloader itself is confined for phone-origin jobs (9): only site-specific extractors (never yt-dlp's generic
extractor, which fetches arbitrary pages and the URLs embedded in them), no live streams, and bounded size and
duration.

The page a link names can hand over to other URLs, and service, media and redirect URLs are read from it, so the
phone-origin download is confined twice: `allowed_extractors = ["default", "-generic", "-mediasite.*"]` (no extractor
whose links may name any host; a test scans the remaining ones), and `ssrf.confined_connections()` checks every socket
connect of the download (global unicast only, at the address actually reached) and refuses outside programs whose
arguments carry a network URL; yt-dlp's Urllib/Requests/Websockets handlers only, `external_downloader: native`,
`concurrent_fragment_downloads: 1`, `proxy: ""`. A refused connect fails the job with `refused_address`.

Residual risk, documented in the README and `android/SECURITY.md`: DNS lookups go through the computer's resolver; the
JavaScript runtime runs YouTube's player code. Only phones the operator paired can submit links.

### 4.6 No absolute paths or user names to LAN clients (S-06)

- Phone responses are built by their own serializer (`phone/shape.py`); they never contain `result_dir`, `media_path`,
  the workspace, the `progress` log, `meta.source` or any path. File names are base names only.
- Error messages are cleaned (8.3): absolute paths are reduced to their base name, the user's home directory and user
  name never appear, ANSI escape codes are removed.
- A test (11.3, `test_phone_access.py`) walks every JSON response of a full phone session, **and every served JSON
  artifact** (`analysis`, `analysis_compact` once decompressed), and fails if it contains the temporary workspace
  path, the home directory or the user name (security review L-4; today's analyses hold only base names,
  `pipeline.py` writes `meta.source` as a base name).

### 4.7 Resource limits

| Limit | Value | Answer |
|---|---|---|
| JSON request bodies on phone routes | `Content-Length` required, at most 16 KiB | `411 length_required` / `413 too_large` |
| LAN request head | complete within 10 s of the connection or of the previous answer | the connection is closed |
| LAN request body that is not a file upload | complete within 10 s of its head | the connection is closed |
| LAN connections | at most 16 per peer address, 128 in total on approved addresses, 16 in total on other local addresses; loopback unlimited; a connection that becomes a WebSocket leaves the counts (the channel has its own limits) | closed at once |
| Upload size | `max_upload_bytes` (2 GiB default) | `413 too_large` |
| Free space for uploads | the declared bytes are **reserved**: a declaration is accepted only if `free − Σ open reservations − bytes ≥ MIN_FREE_BYTES` (2 GiB); a reservation ends when its session ends | `507 no_space` |
| Open upload sessions | at most 4 per device | `409 upload_busy` (detail: "Too many unfinished uploads from this phone.") |
| Concurrent upload bodies | at most 1 `PUT` or `PATCH` in progress per device; a new one for the **same key** replaces an older one that received no byte for 10 s ("newest wins"); otherwise `409 upload_busy` | `409 upload_busy` |
| Idle upload body | a body that delivers no byte for 30 s is aborted (core: the part is deleted; complete: the bytes on disk are kept) and the session is released | the connection is closed |
| Upload chunk (complete) | at most `max_chunk_bytes` (8 MiB) | `413 chunk_too_large` |
| Abandoned upload sessions | deleted after 48 h without activity | — |
| Phone channels | at most 8 open `/api/phone/events` sockets, at most 2 per device | upgrade refused |
| Pairing attempts | 2.3 | `429 rate_limited` |
| `hello` with a challenge (proofs) | 20 per minute per source address, 120 per minute in total | `429 rate_limited` |
| Discovery replies | 5.4 | silently dropped |
| Phone-origin link downloads | 9: no live stream, at most `max_link_duration_s`, at most `max_upload_bytes`, free space checked before the download | job codes `live_stream`, `too_long`, `no_space` (8.2) |
| Record yourself | unchanged: 8 MiB + 64 KiB, 30 s, concurrency 2 | existing codes |

Upload bodies are awaited on the event loop and only each received chunk's write is dispatched to a worker thread
(a dedicated `anyio.CapacityLimiter(4)`), so an idle or slow upload holds no thread of the pool the browser client's
synchronous routes use (security review M-5). A per-device cap on queued jobs is **not** added: the spec withdrew it
(S-19 moved to Later as S-L5, spec 11.5 and 12); idempotency keys already make a retried request return its job
instead of creating another.

### 4.8 Accepted risks (spec 11.6, Q-1 default)

- **Plain HTTP on the home network**: someone on the same Wi-Fi can read clips, transcripts and recordings that cross
  it. Mitigations: phone access is opt-in, tied to approved networks (2.1) and visible at every start; requests are
  signed per device (4.3), so a passive listener captures no reusable credential (only the pairing answer carries
  the token, once); credentials are per device and revocable; LAN clients reach only the phone API. TLS with a
  certificate pinned at pairing is Later (S-L3).
- **An active attacker on the network** (ARP spoofing, a rogue access point) can still read or alter what crosses it
  and can relay a pairing code typed into a rogue responder (the phone lists computers by name). Detection: the
  terminal line "Paired: … (from <address>)" (2.2) and the phone's own address in Connection details; prevention
  needs TLS or a PAKE (S-L3).
- **The library is shared among paired phones**: any paired phone may list every job and download any job's
  artifacts (spec 7.6); a phone sees another phone's job only as `own: false`, without its device id (6.3), and acts
  only on its own jobs (6.7).
- **`devices.json` holds MAC keys** (3.2): a local user who can read the operator's configuration directory can act
  as a paired phone. The file is `0600` in a `0700` directory checked at every open.

The README's phone-access section and `android/SECURITY.md` state these in these terms.

---------------------------------------------------------------------------------------------------------------

## 5. Discovery (S-03)

### 5.1 Port and lifecycle

- UDP port **45317** (`DISCOVERY_PORT` in `phonotrainer/phone/__init__.py`; the same constant as the app's
  `DiscoveryProtocol.PORT`). It is in the unprivileged range the development computer's firewall allows, and it is
  unassigned in that computer's services database (checked 2026-10-10). If a conflict ever appears, both constants
  change together.
- The responder is a daemon thread owning one socket bound to `0.0.0.0:45317`, started by the app's lifespan when phone
  access is on (not while suspended, 2.1) and stopped at shutdown or when phone access is switched off live. If the
  port is busy it logs one warning line (no address), sets `capabilities.discovery = false` and the server runs on:
  manual address entry still works.
- A server bound to a loopback address (`--host 127.0.0.1`) runs no responder: no phone could connect to it.
- No mDNS, no multicast, no broadcast from the computer: the phone broadcasts, the computer answers by unicast.

### 5.2 Probe datagram (phone → broadcast)

UTF-8 JSON object, at most 512 bytes:

```json
{"t": "phonotrainer-probe", "v": 1, "nonce": "6f1c2a9e4b7d3f80"}
```
- `t` exactly `phonotrainer-probe`; `v` the integer 1; `nonce` 16 to 64 lower-case hex characters, new per search.
- Anything else (size, encoding, type, fields) is dropped without an answer. Unknown extra fields are ignored.

### 5.3 Reply datagram (computer → the probe's source address and port, unicast)

```json
{"t": "phonotrainer-here", "v": 1, "nonce": "6f1c2a9e4b7d3f80",
 "server_id": "srv_example1", "name": "Study desk", "http_port": 8000, "api_version": 6}
```
- Carries only server id, display name, HTTP port and API version (S-03): never a credential, path, user name or
  address. The phone takes the address from the reply's source. The display name defaults to "PhonoTrainer computer",
  never the host name (3.2).
- `http_port` is the port the HTTP server listens on.

### 5.4 Rules

- Replies go only to sources in private or link-local IPv4 ranges (10/8, 172.16/12, 192.168/16, 169.254/16) **that lie
  inside an active approved network** (2.1); others are dropped. Sources on virtual interfaces (container bridges,
  VPN tunnels) therefore get no answer unless the operator approved them.
- Rate limits: at most 5 replies per second per source address and 50 per second in total; excess probes are dropped
  silently.
- The phone side (architecture 6.1) sends the probe at 0, 1 and 3 s to `255.255.255.255` and to the directed broadcast
  of its Wi-Fi network, matches replies by nonce, keeps one entry per (server id, source address), and confirms each
  with `GET /api/phone/hello` at the source address before listing it. The server id is public (every reply carries
  it), so it is never proof of identity: a paired phone trusts an address only after `hello`'s HMAC proof (6.2), and
  two sources answering with one server id are a **conflict** the phone shows, never a duplicate it merges.

---------------------------------------------------------------------------------------------------------------

## 6. HTTP API for phones (`/api/phone/`)

### 6.1 Conventions

- Base URL `http://<IPv4>:<port>`; every route below is under `/api/phone/`. JSON bodies use
  `application/json; charset=utf-8`; JSON responses carry `Cache-Control: no-store`.
- Authentication as in 4.3; "auth" in the tables means a valid request signature (`Authorization: PT1 …`).
- Timestamps: ISO-8601 with UTC offset and second resolution, as the job store writes them
  (`2026-10-04T13:35:19+00:00`).
- Ids: job id `^\d{8}-\d{6}-[0-9a-f]{4}$` (server generated); `device_id` `dev_…`, `server_id` `srv_…`, `library_id`
  `lib_…` (prefix + 26 base32 characters); `client_request_id` a UUID in canonical text form, case-insensitive,
  normalized to lower case. The examples in this document use short placeholders (`srv_example1`,
  `00000000-0000-4000-8000-000000000001`, `pt1_<43 base64url characters>`) so that no committed file holds anything
  shaped like a real id or credential.
- **Errors**: every non-2xx answer of a phone route is `{"detail": "<English sentence>", "code": "<stable code>"}`
  (section 8 lists every code), sometimes with extra fields named in the route (`job`, `offset`, `server_time`). FastAPI validation
  errors on phone routes are re-wrapped as `400 {"detail": "…", "code": "invalid_request"}`; on every other route the
  existing 422 list stays as it is (1.3).
- **Idempotency (S-09)**: creation routes (links, uploads, re-runs) take a `client_request_id` chosen by the phone. Keys
  are scoped to the device. The first request with a key creates; every later request with the same key from the same
  device returns the job that key created (`200`), whatever its status, as long as that job exists. The key is stored
  in the job's `job.json` (`client_request_id`, with `device_id`), so it survives restarts. A key reused for a
  different request (another URL, another file, another job to re-run) gives `409 key_conflict`. Lookups and creation
  happen under the store's lock, so two concurrent requests with one key create one job. Upload session files are
  named by the key alone (3.2), so a key held by another device's open upload session (a UUID clash) answers
  `409 key_conflict`.
- Cancel, delete and unpair are naturally idempotent (6.2, 6.7).

### 6.2 Identity, pairing, health

**`GET /api/phone/hello`** (no auth; exists in every API ≥ 6 server, whatever the phone-access state)

Optional query parameters, sent by a paired phone: `device_id` (its id) and `challenge` (32 to 64 lower-case hex
characters, new for each call). Before pairing the phone sends neither.

```json
{"service": "phonotrainer", "phone_access": true, "server_id": "srv_example1",
 "name": "Study desk", "api_version": 6, "server_time": 1791622800,
 "proof": "5d1f…64 hex…", "revoked": false}
```
- `server_time`: the computer's clock in unix seconds, always present; the phone keeps the offset for signing (4.3).
- **Identity proof** (security review H-1): when `device_id` and `challenge` are given and that device has a row
  (revoked or not), `proof = hex(HMAC-SHA256(K, "pt-hello-v1|" + server_id + "|" + device_id + "|" + challenge))`
  with the device's key `K` (3.2), and `revoked` tells whether the row is revoked. For an unknown device, or without
  the parameters, `proof` and `revoked` are `null`. Only the computer that issued the phone's token can compute the
  proof, so a host that merely repeats the paired server id (which every discovery reply broadcasts) cannot pass.
  The phone trusts an address only after a valid proof (architecture 5.3) and sends nothing else there before.
- Challenge calls are rate-limited (4.7).

With phone access off (or suspended, from loopback): `{"service": "phonotrainer", "phone_access": false,
"api_version": 6, "server_time": 1791622800}` (no identity, no proof). Nothing else is ever returned before
authentication (spec 11.5).

A server older than API 6 answers `404` here; the phone then calls `GET /api/health` at the same candidate address,
without any credential (architecture 5.4, `ComputerClient.legacyHealth`): an answer with `api_version` means
"PhonoTrainer, too old" (spec F-02 "Needs a newer PhonoTrainer on your computer"); anything else means "not
PhonoTrainer". An old server answers the LAN only when its operator started it on a network address by hand.

**`POST /api/phone/pair`** (no auth; phone access on)

Request:
```json
{"code": "482913", "device_name": "Pixel 9", "app_version": 1, "app_version_name": "1.0.0",
 "previous_device_id": null, "previous_token_proof": null}
```
- `code`: exactly 6 ASCII digits. `device_name`: 1 to 60 characters after cleaning (2.3). `app_version`: integer ≥ 1.
- `previous_device_id` and `previous_token_proof` ("Connect again"): the device id this phone held before and
  `hex(HMAC-SHA256(K_old, "pt-reactivate-v1|" + code))` computed with its previous key. Both or neither.

`201 Created`:
```json
{"device_id": "dev_example1", "token": "pt1_<43 base64url characters>",
 "server_id": "srv_example1", "name": "Study desk", "library_id": "lib_example1",
 "api_version": 6, "min_app_version": 1, "server_time": 1791622800}
```
Effects: the code is consumed. When `previous_device_id` names a device of this server (revoked or not) **and**
`previous_token_proof` verifies with that row's key, the row is re-activated with the new token (its old key stops
working) so the jobs it submitted stay its own. Otherwise (no previous id, an unknown one, or a proof that does not
verify) a new device row is created: knowing another phone's device id is never enough to take over its row and its
jobs (security review L-1). `devices.json` is written; a waiting `phone pair` prints "Paired: <name> (from
<address>)" (2.2). The token is in this answer only; the phone never sends it back (4.3).

Errors: `403 code_invalid` (wrong digits, or no code active), `403 code_expired`, `403 code_locked`,
`429 rate_limited` (`Retry-After`), `426 app_too_old` (`app_version < MIN_APP_VERSION`, nothing consumed),
`403 phone_access_off`, `400 invalid_request`.

**`GET /api/phone/health`** (auth) — the authenticated probe (architecture 6.2 rule 3)

```json
{
  "server_id": "srv_example1",
  "name": "Study desk",
  "library_id": "lib_example1",
  "api_version": 6,
  "min_app_version": 1,
  "version": "0.1.0",
  "server_time": 1791622800,
  "device": {"device_id": "dev_example1", "name": "Pixel 9"},
  "queue": {"analyzing": 1, "in_line": 2},
  "low_on_space": false,
  "capabilities": {
    "link_downloads": true,
    "downloader": {"name": "yt-dlp", "version": "2026.8.19", "js_runtime": "node", "challenge_solver": false},
    "dialogue_separation": true,
    "discovery": true,
    "renditions": false,
    "phone_video": false,
    "resumable_uploads": false,
    "record_compare": false,
    "max_upload_bytes": 2147483648,
    "max_chunk_bytes": 8388608,
    "max_link_duration_s": 3600,
    "free_bytes": 52613349376
  }
}
```
- `queue.analyzing` is 1 while a job runs; `queue.in_line` counts queued jobs not being cancelled. All jobs count,
  whoever submitted them (spec 7.5 "analyzing 1 · 2 in line").
- Built from memory and cached values only: it MUST answer while an analysis holds the worker (a test blocks the fake
  analysis on an event and expects `health` within 1 s).

**`DELETE /api/phone/devices/self`** (auth) — "Forget this computer"

`204`: the calling device is revoked. A repeat gets `401 credential_rejected`, which the phone treats as done. The
phone sends it only while connected, at the moment the learner forgets the computer; it is never queued, because the
phone deletes its key right after (architecture 6.3, delivery review D-32).

### 6.3 The library listing and the job shape (S-11, S-13, S-15)

**`GET /api/phone/library`** (auth)

```json
{
  "api_version": 6,
  "server_id": "srv_example1",
  "library_id": "lib_example1",
  "generated_at": "2026-10-10T09:41:00+00:00",
  "complete": true,
  "jobs": ["<PhoneJob>", "…"]
}
```
- Every job of the workspace (done, running, queued, failed, cancelled; browser, CLI and phone jobs alike), ordered by
  `id` ascending (stable; `created` collides within a second). `complete` is always `true` in v1: there is no cursor
  and no tombstone; the phone detects deletions as architecture 7.7 says.
- About 1.5 KB per job; no progress logs.

**`GET /api/phone/jobs/{id}`** (auth): one `PhoneJob` (with `artifacts`); `404 unknown_job`.

**`GET /api/phone/requests/{client_request_id}`** (auth): `{"job": PhoneJob}` for a key this device used;
`404 unknown_request` otherwise (another device's key is unknown to the caller).

**PhoneJob**

```json
{
  "id": "20261010-091503-4be1",
  "revision": "5c0e8a1f9b2d7e64",
  "status": "done",
  "stage": null,
  "progress": {"percent": 100, "segment": null, "segments": null, "download_percent": null},
  "separation": null,
  "queue_position": null,
  "title": "Ordering coffee",
  "source": {"kind": "link", "url": "https://youtu.be/abc123def45", "site": "youtube", "file_name": null},
  "options": {"audio_only": false, "separate_dialogue": true},
  "device_id": "dev_example1",
  "own": true,
  "client_request_id": "00000000-0000-4000-8000-000000000001",
  "rerun_of": null,
  "created": "2026-10-10T09:15:03+00:00",
  "started": "2026-10-10T09:15:04+00:00",
  "finished": "2026-10-10T09:19:31+00:00",
  "error": null,
  "summary": {"duration_s": 260.5, "segments": 87, "words": 691,
              "phenomena_counts": {"flapping": 12, "linking": 30}},
  "analysis": {"rules_version": 3, "phone_engine": "timit61", "dialogue_separation": true,
               "phonotrainer_version": "0.1.0"},
  "media": {"kind": "video", "codec": "h264", "width": 1280, "height": 720, "rotation": 0,
            "start_offset_ms": 0, "duration_s": 260.6},
  "artifacts": ["<Artifact, 6.4>", "…"]
}
```

| Field | Type | Meaning |
|---|---|---|
| `revision` | 16 hex | first 16 hex digits of `sha256` over the JSON list `[status, error.code, [[name, status, sha256] for each artifact]]`. It changes when the status, the error, an artifact or a rendition status changes, and **not** with percent, stage or queue position |
| `status` | enum | `queued`, `running`, `done`, `error`, `cancelled` (the store's statuses) |
| `stage` | enum or null | `download`, `extract`, `separate`, `transcribe`, `phones`, `save` — the web's `StageKey` values — derived from the furthest progress message with the prefix table of `web/src/jobs/stages.ts` (`PREFIXES`); null while queued, when done, or before any stage message |
| `progress` | object | `percent` (the store's estimate, 0–100); `segment`/`segments` from `Segment i/n`; `download_percent` from `Downloading from YouTube… N%` |
| `separation` | enum or null | `off` (option off), `skipped` (a `Dialogue separation skipped` message); null otherwise. For done jobs `analysis.dialogue_separation` is authoritative |
| `queue_position` | int or null | 1 for the next job to start; counts only queued jobs not being cancelled, in arrival order (the store's enqueue sequence, not `created`) |
| `title` | string or null | done jobs and downloaded links: `meta.source` / the store's `source` without a trailing ` [<id>]` and without the extension; uploads and path jobs: the file's base name without extension; a link before its download: null |
| `source.kind` | enum | `link` (has `source_url`), `upload` (`media/` inside the job), `path` (created by path), `import` |
| `source.url` | string or null | links only, as stored |
| `source.site` | enum or null | links only: `youtube_shorts` (host `youtube.com`/`www.`/`m.` and path `/shorts/…`), `youtube` (`youtube.com`, `www.`, `m.`, `music.`, `youtu.be`), `x` (`x.com`, `twitter.com`, `mobile.twitter.com`, `t.co`), otherwise `other` |
| `source.file_name` | string or null | uploads and path jobs: base name only |
| `options` | object | `audio_only` (links), `separate_dialogue` |
| `device_id` | string or null | the device that submitted it, **only when `own`**; null for another phone's job and for browser and CLI jobs, so one phone never learns another's device id (security review L-1, delivery review D-34) |
| `own` | bool | the job was submitted by the calling device |
| `client_request_id` | string or null | the key that created it, only when `own` |
| `rerun_of` | string or null | the job id this job replaced (6.7) |
| `error` | object or null | `{"code", "message"}` for `error` and `cancelled` statuses (section 8.2); download failures add `"downloader_version"`. The phone treats a code it does not know as `processing_failed` (NFR-08) |
| `summary` | object or null | from the store's summary: `duration_s`, `segments`, `words`, `phenomena_counts` |
| `analysis` | object or null | from `meta`: `rules_version`, `phone_engine`, `dialogue_separation` (applied), `phonotrainer_version` |
| `media` | object or null | the original's facts (6.4.3): `kind` (`video`, `audio`, `none`), `codec` (ffprobe `codec_name`: `av1`, `h264`, `hevc`, `vp9`, `vp8`, …, or null when unknown), `width`, `height` (stored size, null when unknown), `rotation` (0, 90, 180, 270; **0 when unknown**), `start_offset_ms` (**0 when unknown**), `duration_s` (null when unknown); the whole object is null until probed (delivery review D-29) |
| `artifacts` | list | 6.4; empty unless `status` is `done`; absent in channel events (7) |

Failed link job (download refused), as the phone sees it:

```json
{
  "id": "20261010-093000-7c2a", "revision": "a1b2c3d4e5f60718", "status": "error", "stage": "download",
  "progress": {"percent": 1, "segment": null, "segments": null, "download_percent": null},
  "separation": null, "queue_position": null, "title": null,
  "source": {"kind": "link", "url": "https://x.com/someone/status/1234567890", "site": "x", "file_name": null},
  "options": {"audio_only": false, "separate_dialogue": true},
  "device_id": "dev_example1", "own": true,
  "client_request_id": "00000000-0000-4000-8000-000000000002", "rerun_of": null,
  "created": "2026-10-10T09:30:00+00:00", "started": "2026-10-10T09:30:01+00:00",
  "finished": "2026-10-10T09:30:03+00:00",
  "error": {"code": "download_forbidden",
            "message": "unable to download video data: HTTP Error 403: Forbidden",
            "downloader_version": "2026.8.19"},
  "summary": null, "analysis": null, "media": null, "artifacts": []
}
```

### 6.4 Artifacts and renditions (S-11, S-12)

#### 6.4.1 Artifact names

| `name` | `role` | Tier | Content | `content_type` | Listed when |
|---|---|---|---|---|---|
| `analysis` | `clip_data` | core | `analysis.json` exactly as stored | `application/json` | job done |
| `audio_mix` | `audio_mix` | core | `audio.wav`, 16 kHz mono PCM16 | `audio/wav` | job done |
| `audio_dialogue` | `audio_dialogue` | core | the separated dialogue WAV the analysis read | `audio/wav` | job done and separation applied |
| `original` | `video` | core | the original file as stored | from the server's closed `MEDIA_TYPES` table | job done and the original's suffix is a video suffix; `unavailable` when the file is gone |
| `analysis_compact` | `clip_data` | complete | gzip of the same JSON without indentation (`separators=(",", ":")`) | `application/gzip` (never `Content-Encoding`) | job done |
| `audio_mix_opus` | `audio_mix` | complete | Ogg Opus, mono, 24 kbit/s, from `audio.wav`: the same zero and duration | `audio/ogg` | job done |
| `audio_dialogue_opus` | `audio_dialogue` | complete | the same from the dialogue WAV | `audio/ogg` | when `audio_dialogue` is listed |
| `poster` | `poster` | complete | JPEG, longest side 720 px, the frame at the first phrase's start + 0.5 s (10 % of the duration when there is no phrase) | `image/jpeg` | `media.kind` is `video` |
| `video_phone` | `video` | complete | H.264 (libopenh264, Constrained Baseline) in MP4 with `+faststart`; short side ≤ 720 px; rotation applied to the pixels (no rotation flag); **no audio track**; starting at the analysed timeline's zero (so its offset is 0) | `video/mp4` | only after it was requested (6.4.4) |

Only the names of this table exist; any other name is `404 unknown_artifact`. `report.html`, `review.json`,
intermediate files and logs are never served to phones (spec 7.6).

#### 6.4.2 Artifact entry

```json
{
  "name": "audio_mix",
  "role": "audio_mix",
  "status": "ready",
  "bytes": 8336600,
  "sha256": "e3b0c442…64 hex…",
  "revision": "e3b0c44298fc1c14",
  "content_type": "audio/wav",
  "url": "/api/phone/jobs/20261010-091503-4be1/artifacts/audio_mix",
  "error": null
}
```
- `status`: `ready` (file present, checksum known), `preparing` (being computed: a checksum after a restart or a
  finished job, which takes seconds; a rendition being made), `failed` (renditions only; `error` holds the clean
  message), `unavailable` (the original is gone; a rendition that cannot be made, for example a phone video of a
  missing original). `bytes`, `sha256`, `revision` and `url` are null unless `ready`.
- `revision` is the first 16 hex digits of `sha256` (content-addressed; the phone names its partial file with it).
- `video_phone` entries also carry `"video": {"codec": "h264", "width": 720, "height": 1280}` when ready.
- Checksums are computed by a background thread (`phone/artifacts.py`) at start, after each finished job and after
  each rendition, and cached in `<job>/phone/checksums.json` keyed by `(name, size, mtime_ns)`; the listing never
  hashes inline (a 65 MB original must not delay it), so an uncached file reads `preparing` for a few seconds.

#### 6.4.3 Media facts of the original

`ffprobe -v error -print_format json -show_streams -show_format <original>` (the system ffmpeg's `ffprobe`; the
runner is injectable), cached in `<job>/phone/media.json`:
- `kind`: `video` when a video stream exists (attached pictures excluded), `audio` when only audio, `none` for imported
  jobs or an unreadable file; `codec`, `width`, `height` of the first video stream; `rotation` from the display matrix
  side data or the `rotate` tag, normalized to 0/90/180/270.
- `start_offset_ms` = round((audio stream `start_time` − video stream `start_time`) × 1000): the time in the video's
  own timeline that matches analysed time 0, because `audio.wav` starts at the first audio sample. Positive: the video
  has content before the analysed zero (the phone clips it); negative: the video starts later (the phone's follower
  mode, architecture 11.1). 0 when either value is missing.
- ffprobe missing or failing: `media` keeps `kind` from the file suffix; `codec`, `width`, `height` and `duration_s`
  are null and `rotation` and `start_offset_ms` are 0; the phone then treats the codec as unknown (architecture 7.4).

#### 6.4.4 Routes

**`GET /api/phone/jobs/{id}/artifacts/{name}`** (auth)
- `200` whole file or `206` for `Range: bytes=<start>-` (Starlette's `FileResponse`), with `Accept-Ranges: bytes`,
  `Content-Length`, `Content-Type` from 6.4.1 and `ETag: "<sha256>"` when the cached checksum matches the file's
  current size and modification time. `If-Range` with that ETag is honoured: a mismatch returns the whole file with
  `200`, which tells the phone to restart (architecture 7.4).
- Never `Content-Encoding`; unsatisfiable range `416` (for example `bytes=<size>-`, which a phone that already holds
  the whole part may send after a crash; the phone checks its part instead, architecture 7.4).
- Errors: `404 unknown_job`, `404 unknown_artifact`, `409 artifact_not_ready` (`preparing`, `failed`, `unavailable`).
- Any paired phone may download any job's artifacts: the library is shared (spec 7.6).

**`POST /api/phone/jobs/{id}/renditions/video_phone`** (auth; complete; capability `phone_video`)
- No body. `202 {"status": "preparing"}` when queued now or already being made; `200 {"status": "ready"}` when it
  exists; `200 {"status": "unavailable"}` when the original is missing (never a stuck "preparing").
- Errors: `404 unknown_job`, `409 not_ready` (job not done), `409 not_video` (`media.kind` is not `video`).
- Idempotent. On a v6 server the route does not exist (`404`); the phone checks the capability first.

#### 6.4.5 How renditions are produced (complete, S-12)

- A rendition worker (`phone/renditions.py`), one daemon thread, one task at a time: after a job finishes (store
  listener), at start for every done job missing a rendition (back-fill), and on a phone-video request. Order: the
  compact clip data (in process, milliseconds), the Opus audio, the poster, then requested phone videos.
- ffmpeg runs as `nice -n 19` subprocesses; no rendition task starts while an analysis is running or waiting
  (renditions never delay a waiting analysis); a running task finishes.
- Each output is written under a temporary name in `<job>/phone/` and renamed when complete; `renditions.json` keeps
  each rendition's status and clean error. A failure marks that rendition `failed` and never touches the study pack:
  the phone falls back to `analysis`, the WAVs, the poster fallback, the original or audio only (spec 14.4).
- Commands (the runner is injectable; tests assert the argument lists):
  - Opus: `ffmpeg -nostdin -hide_banner -loglevel error -y -i <wav> -c:a libopus -b:a 24k -ac 1 -f ogg <tmp>`
  - Poster: `ffmpeg … -ss <t> -i <original> -frames:v 1 -vf "scale='if(gt(iw,ih),720,-2)':'if(gt(iw,ih),-2,720)'" -q:v 4 <tmp>.jpg`
  - Phone video: `ffmpeg … -ss <start_offset_s> -i <original> -an -c:v libopenh264 -b:v 1500k -vf "scale=<short side 720>" -movflags +faststart <tmp>.mp4`
    (`-ss` before `-i` for a positive offset, so the output starts at the analysed zero; ffmpeg's default
    auto-rotation bakes the rotation into the pixels).

### 6.5 Links (S-07, S-09)

**`POST /api/phone/jobs/link`** (auth)

```json
{"client_request_id": "00000000-0000-4000-8000-000000000001", "url": "https://youtu.be/abc123def45",
 "audio_only": false, "separate_dialogue": true}
```
- `url`: the phone sends the normalized link (architecture 8.2); the server checks it (4.5) and stores it trimmed.
- `audio_only` default false; `separate_dialogue` default the server's default (true). Every other analysis option is
  the server's default (spec D-08).
- `201 {"job": PhoneJob, "created": true}`; for a known key `200 {"job": PhoneJob, "created": false}`.
- Errors: `400 invalid_url`, `400 refused_address`, `503 cannot_check_link`, `409 key_conflict` (the key created
  something else), `400 invalid_request`.
- The job records `device_id` and `client_request_id`, is marked **phone-origin** (its download runs confined, 9) and
  joins the end of the line (spec 7.7: one worker, arrival order).

### 6.6 Uploads (S-09; S-10 in complete)

**`POST /api/phone/uploads`** (auth) — declare a file

```json
{"client_request_id": "00000000-0000-4000-8000-000000000003", "file_name": "VID_0001.mp4", "bytes": 312475648,
 "sha256": "4f1d…64 hex…", "separate_dialogue": true}
```
- `file_name`: a base name (no `/` or `\`), at most 200 characters, control characters removed, suffix in the
  server's media suffixes (`.webm .mp4 .mkv .mov .avi .m4v .mp3 .wav .m4a .flac .ogg .opus .aac`, case-insensitive).
  An audio-only extraction made on the phone arrives as `.m4a`, `.ogg` or `.webm` (architecture 8.3).
- `bytes`: 1 ≤ bytes ≤ `max_upload_bytes`. `sha256`: 64 hex.

Answers:
- `201 {"state": "open", "upload": {"client_request_id": "…", "offset": 0, "bytes": 312475648,
  "expires_at": "2026-10-12T09:41:00+00:00", "max_chunk_bytes": 8388608, "resumable": false}}` — a new session.
- `200` with the same body for an existing session of that key. In core the offset is always 0 (an interrupted part
  was discarded); in complete it is the number of bytes received and `resumable` is true.
- `200 {"state": "done", "job": PhoneJob}` — the key already created a job (a lost response after the upload).
- A new session **reserves** its `bytes` against free space until it ends (4.7).
- Errors: `400 unsupported_type` (detail lists the accepted suffixes), `413 too_large` (detail names the limit),
  `507 no_space` (free space minus open reservations would drop below 2 GiB), `409 key_conflict` (the key declared
  another file), `409 upload_busy` (the device already has 4 open sessions), `400 invalid_request`. These refuse
  early, before any byte is sent (spec 7.7).

**`PUT /api/phone/uploads/{client_request_id}`** (auth) — the whole file (core)
- Headers: `Content-Type: application/octet-stream`, `Content-Length` equal to the declared `bytes` (never chunked).
- The body is streamed to `.phone-uploads/<key>.part` (writes off the event loop), hashed while written. At the end the
  size and SHA-256 MUST equal the declaration; then the part is renamed into `<workspace>/<new job id>/media/<file_name>`
  and the job is queued with `device_id` and `client_request_id`.
- `201 {"job": PhoneJob}`; `200 {"job": PhoneJob}` if the key already has a job (the body is read and discarded).
- Errors: `404 unknown_upload` (no session: declare first), `411 length_required`, `400 length_mismatch`,
  `422 checksum_mismatch` (the part is deleted; the phone sends again), `507 no_space`, `409 upload_busy` (another
  body for this device is still receiving bytes, 4.7).
- A body aborted after 30 s without a byte answers `400 length_mismatch` with `Connection: close` (if the client
  still reads); a body replaced by a newer one ("newest wins", below) answers `409 upload_busy`; an answer sent before
  the body was read carries `Connection: close`.
- An interrupted body (connection lost) deletes the part in core; the session stays, so the phone restarts with the
  same key (spec 7.7 v1-core).
- **A body can never wedge its key** (delivery review D-08): when Wi-Fi drops mid-`PUT`, the server's request may
  never see the connection close (it only reads, and nothing times out a silent TCP peer). Therefore (a) a body that
  delivers no byte for 30 s is aborted, its part deleted and its session released; and (b) a new `PUT` for the same
  key while an older one has received no byte for 10 s cancels the older one ("newest wins") and proceeds. A phone
  that comes back after a Wi-Fi drop thus restarts within 10 s, never loops on `upload_busy`.

**`GET /api/phone/uploads/{client_request_id}`** (auth; complete) — `200` with the bodies of `POST` (`open` with the
current `offset`, or `done` with the job); `404 unknown_upload`.

**`PATCH /api/phone/uploads/{client_request_id}`** (auth; complete; capability `resumable_uploads`)
- Headers: `Content-Type: application/offset+octet-stream`, `Upload-Offset: <n>` (the bytes the server has),
  `Content-Length: <m>` with 1 ≤ m ≤ `max_chunk_bytes`; the body holds bytes `[n, n + m)`.
- `204` with `Upload-Offset: <n + m>` while bytes remain; when `n + m` equals `bytes`, the server checks the SHA-256 of
  the whole part and answers `201 {"job": PhoneJob}` (or `200` if the key already had a job).
- Errors: `409 {"detail": "…", "code": "offset_mismatch", "offset": <current>}`, `413 chunk_too_large`,
  `422 checksum_mismatch` (the session restarts at 0), `404 unknown_upload`, `409 upload_busy`, `507 no_space`.
- An interrupted chunk keeps the bytes that reached the disk; `GET` reports them. The 30 s idle abort and "newest
  wins" apply to `PATCH` as to `PUT` (the bytes already on disk are kept).

**`DELETE /api/phone/uploads/{client_request_id}`** (auth) — abandon ("Remove" while sending): `204`, always
(idempotent); deletes the session and its part. Exists in core too.

Expiry: a janitor, at start and then hourly, deletes sessions and parts untouched for 48 h (S-10).

### 6.7 Cancel, delete, re-run (S-06, S-22)

Ownership: these act only on jobs the calling device submitted (`own`; `403 not_own_job` otherwise; browser and CLI
jobs are never the phone's).

**`POST /api/phone/jobs/{id}/cancel`** (auth, own) — no body
- `200 {"job": PhoneJob}`: a queued or running job is flagged for cancellation (the listing or the channel shows
  `cancelled` shortly after); a job already `error` or `cancelled` is returned unchanged (idempotent). A phone's
  cancel of a **queued** job makes it `cancelled` at once (it may otherwise wait many minutes behind a running
  analysis); the browser's cancel is unchanged (the worker sees it on entry).
- `409 {"detail": "…", "code": "already_finished", "job": PhoneJob}`: the job is `done`; the phone keeps the clip and
  shows "Too late to cancel…" (spec F-20-AC5).
- `403 not_own_job`, `404 unknown_job`.

**`DELETE /api/phone/jobs/{id}`** (auth, own; status `error` or `cancelled`) — the phone's `REMOVE_JOB`
- `204`; the job and its directory are deleted (a `deleted` channel event follows).
- `409 not_deletable` for `queued`, `running` or `done` ("Delete it on your computer." — finished analyses are never
  deleted from a phone, D-07); `403 not_own_job`; `404 unknown_job` (the phone treats it as done).

**`POST /api/phone/jobs/{id}/rerun`** (auth, own; status `error` or `cancelled`) — "Try again", "Try audio only", the
automatic re-run after `interrupted`

```json
{"client_request_id": "00000000-0000-4000-8000-000000000004", "audio_only": true}
```
- `audio_only`: null or absent = as before; `true`/`false` only for link jobs (`400 invalid_option` for an upload).
- The key is looked up first: a repeated key returns `200 {"job": <the job it created>, "replaced": "<old id>"}` even
  though the old job no longer exists.
- Otherwise: a link job's stored URL is checked again (4.5: `400 refused_address`, `503 cannot_check_link`), then the
  job is re-created from it (`create_from_url`, phone-origin, so its download runs confined, 9; the downloader reuses
  an existing download); an upload job is re-created from its stored file, **moved** (not copied) from the old job's
  `media/` into the new job. The new job records `device_id`, the new `client_request_id` and `rerun_of = <old id>`, joins the end of
  the line, and then the old job is deleted (its `deleted` event and the new job's event arrive together; the phone
  keeps one row, architecture 8.6). `201 {"job": PhoneJob, "replaced": "<old id>"}`.
- Errors: `410 source_gone` (the uploaded file or a path job's original is missing; the old job is kept), `409
  not_rerunnable` (`queued`, `running`, `done`), `403 not_own_job`, `404 unknown_job`, `409 key_conflict`,
  `400 refused_address`, `503 cannot_check_link`.
- Order: the server processes re-runs in arrival order, so the phone's acceptance-ordered operations keep the original
  order (QT-5, QT-12).

### 6.8 Reference (S-17)

**`GET /api/phone/reference`** (auth): byte-for-byte the body of `GET /api/reference` (one builder,
`build_reference()`), with `ETag: "api-<API_VERSION>"`. The phone fetches it when the listing's `api_version` differs
from its cached one (architecture 7.1 step 5).

### 6.9 Record yourself for phones (S-16, complete; capability `record_compare`)

**`GET /api/phone/jobs/{id}/contour?start=<s>&end=<s>`** and **`POST /api/phone/jobs/{id}/compare`** (auth): the same
request, response, limits and error codes as `GET /api/jobs/{id}/contour` and `POST /api/jobs/{id}/compare` (multipart
`file`, `start`, `end`; `Content-Length` required; at most 8 MiB + 64 KiB; 30 s; two measurements at a time; takes are
never kept), implemented by the same functions. Codes in 8.1.

### 6.10 Endpoint summary (the phone's `ComputerApi` has one method per row, architecture 5.4)

| Method and path | Auth | Tier | Success | Main errors |
|---|---|---|---|---|
| `GET /api/phone/hello` | — | core | 200 | 429 rate_limited (challenges) |
| `POST /api/phone/pair` | — | core | 201 | 403 code_invalid/code_expired/code_locked, 429, 426, 403 phone_access_off |
| `GET /api/phone/health` | yes | core | 200 | 401 |
| `DELETE /api/phone/devices/self` | yes | core | 204 | 401 |
| `GET /api/phone/library` | yes | core | 200 | 401 |
| `GET /api/phone/jobs/{id}` | yes | core | 200 | 404 unknown_job |
| `GET /api/phone/requests/{key}` | yes | core | 200 | 404 unknown_request |
| `GET /api/phone/jobs/{id}/artifacts/{name}` | yes | core | 200/206 | 404, 409 artifact_not_ready, 416 |
| `POST /api/phone/jobs/link` | yes | core | 201/200 | 400 invalid_url/refused_address, 503 cannot_check_link, 409 key_conflict |
| `POST /api/phone/uploads` | yes | core | 201/200 | 400 unsupported_type, 413, 507, 409 key_conflict/upload_busy |
| `PUT /api/phone/uploads/{key}` | yes | core | 201/200 | 404, 411, 400 length_mismatch, 422 checksum_mismatch, 507, 409 |
| `DELETE /api/phone/uploads/{key}` | yes | core | 204 | — |
| `GET /api/phone/uploads/{key}` | yes | complete | 200 | 404 |
| `PATCH /api/phone/uploads/{key}` | yes | complete | 204/201/200 | 409 offset_mismatch, 413, 422, 507 |
| `POST /api/phone/jobs/{id}/cancel` | yes | core | 200 | 409 already_finished, 403, 404 |
| `DELETE /api/phone/jobs/{id}` | yes | core | 204 | 409 not_deletable, 403, 404 |
| `POST /api/phone/jobs/{id}/rerun` | yes | core | 201/200 | 410 source_gone, 409, 403, 404, 400 (including refused_address), 503 cannot_check_link |
| `GET /api/phone/reference` | yes | core | 200 | — |
| `POST /api/phone/jobs/{id}/renditions/video_phone` | yes | complete | 202/200 | 409 not_ready/not_video, 404 |
| `GET /api/phone/jobs/{id}/contour` | yes | complete | 200 | existing record-yourself codes |
| `POST /api/phone/jobs/{id}/compare` | yes | complete | 200 | existing record-yourself codes |
| `WS /api/phone/events` | yes | core | upgrade | refused upgrade |

Every authenticated row can also answer `401 credential_rejected`, `401 clock_skew`, `403 phone_access_off`,
`403 lan_forbidden`, `400 bad_host` and `5xx` (transient for the phone).

---------------------------------------------------------------------------------------------------------------

## 7. Job channel for phones (S-14)

`ws://<IPv4>:<port>/api/phone/events`, with a signed `Authorization: PT1 …` header (4.3) on the upgrade request.

- The signature and Host are checked before `accept()`; a failure closes the socket before accept (the client sees
  the handshake refused with HTTP 403). The phone treats any refused or lost channel the same way: the channel's proof
  of liveness ends and a probe decides (architecture 5.5, 6.2). No `Origin` header is needed or checked.
- At most 8 phone channels at once and 2 per device; an upgrade beyond either limit is refused.
- Server → phone, JSON text frames, in this order:
  1. `{"type": "hello", "server_id": "srv_…", "library_id": "lib_…", "api_version": 6}`
  2. `{"type": "snapshot", "jobs": [<lean PhoneJob>, …]}` — every job, ordered by id.
  3. Then, as the store changes: `{"type": "job", "job": <lean PhoneJob>}` and `{"type": "deleted", "id": "…"}`.
- **Lean** PhoneJob = the PhoneJob of 6.3 without `artifacts` (no progress log either), so S-14's "lean events" ship in
  core at no extra cost (the phone channel is new). When a `job` event carries a `revision` different from the one the
  phone stored, the phone runs a sync pass to fetch artifacts (architecture 7.6).
- Each event re-reads the job at send time (as `/ws/jobs` does), so applying events is idempotent and never goes
  backwards. `job` events are coalesced to at most one per job every 500 ms (the latest state).
- Phone → server: nothing is expected; frames are read only to notice a close. WebSocket protocol pings from the
  phone (every 7 s) are answered by the server's WebSocket stack.
- `/ws/jobs` (the browser channel) is unchanged and stays loopback-only when phone access is on.

---------------------------------------------------------------------------------------------------------------

## 8. Error codes

### 8.1 Request-level codes (HTTP answers of phone routes)

"Phone class" is the retry class of architecture 8.5: **transient** (retry on schedule), **blocking** (pause the
queue or the feature), **permanent** (the request needs the learner), **defect** (an app bug; logged, treated as
transient), **done** (the operation already happened).

| Code | Status | Meaning | Phone class |
|---|---|---|---|
| `credential_rejected` | 401 | missing or malformed signature, unknown or revoked device, wrong MAC, replayed nonce (4.3) | blocking ("Connect again") |
| `clock_skew` | 401 | the signature's time is more than 300 s from the computer's (body has `server_time`) | transient (re-sync the clock offset, retry once) |
| `phone_access_off` | 403 | phone access is off on this computer | blocking ("phone access is off there") |
| `lan_forbidden` | 403 | a non-phone route from the LAN, or a LAN request on an address that is not approved (2.1) or from a peer outside the approved network (4.4) | defect (on a probe: "not reachable") |
| `unknown_route` | 404 | no such phone route (4.2) | defect |
| `bad_host` | 400 | Host header refused | defect |
| `invalid_request` | 400 | malformed body or parameters | defect |
| `too_large` | 413 | JSON body over 16 KiB, or file over `max_upload_bytes` | permanent (files: "Too large for your computer's limit") |
| `length_required` | 411 | no `Content-Length` | defect |
| `length_mismatch` | 400 | upload body length differs from the declaration | transient (restart the upload) |
| `checksum_mismatch` | 422 | upload SHA-256 differs | transient (restart; after 3, needs attention as "damaged") |
| `unsupported_type` | 400 | file suffix not accepted | permanent |
| `no_space` | 507 | the computer would drop below 2 GiB free | blocking for uploads (links continue) |
| `invalid_url` | 400 | not an http(s) URL, too long, user-info | permanent |
| `refused_address` | 400 | link resolves to a private or local address (S-07), at creation or re-run | permanent |
| `cannot_check_link` | 503 | the computer could not resolve the link's host, at creation or re-run | transient |
| `key_conflict` | 409 | idempotency key reused for another request | defect |
| `code_invalid`, `code_expired`, `code_locked` | 403 | pairing (2.3) | shown on Connect |
| `rate_limited` | 429 | pairing attempts or identity challenges too fast (`Retry-After`) | shown on Connect; on a probe, transient |
| `app_too_old` | 426 | the app is older than `min_app_version` | blocking ("Update this app") |
| `unknown_job`, `unknown_request`, `unknown_upload`, `unknown_artifact` | 404 | no such object | done (delete), defect (others) |
| `artifact_not_ready` | 409 | artifact not `ready` | transient (re-list) |
| `not_own_job` | 403 | not this phone's job | defect |
| `already_finished` | 409 | cancel of a done job | done (keep the clip, notice) |
| `not_deletable` | 409 | delete of a queued, running or done job | defect |
| `not_rerunnable` | 409 | re-run of a queued, running or done job | defect |
| `source_gone` | 410 | the stored file for a re-run is missing | permanent ("Your computer no longer has this file") |
| `invalid_option` | 400 | `audio_only` for an upload re-run | defect |
| `offset_mismatch` | 409 | resumable upload offset differs (body has `offset`) | transient (continue from `offset`) |
| `chunk_too_large` | 413 | PATCH body over `max_chunk_bytes` | defect |
| `upload_busy` | 409 | another upload body of this device is still receiving bytes, or 4 sessions are open (4.7) | transient |
| `not_ready`, `not_video` | 409 | phone video request on a job not done or not a video | defect |
| Record yourself (unchanged): `length_required` 411, `too_large` 413, `missing_file` 400, `bad_span` 400, `unsupported_type` 415, `undecodable` 422, `too_long` 413, `too_short` 422, `no_voice` 422, `empty` 422, `ffmpeg_missing` 500 | | as `/api/jobs/{id}/compare` | mapped to the F-13 copy |
| (none) | 5xx | server fault | transient |

### 8.2 Job failure codes (S-13, S-15): `PhoneJob.error.code`

The store keeps writing its error text exactly as today (`"<ExceptionClass>: <message>"`); the phone shape derives a
stable code from that text with `state.classify_error()`, first match wins, case-insensitive, after removing ANSI
codes. Old jobs get codes too, because only the text is needed.

| Order | Code | Rule on the error text (or status) | Spec 8.6 |
|---|---|---|---|
| 1 | `cancelled` | status `cancelled` (the store writes "Cancelled before starting." or "Cancelled during the analysis."; the rule is on the status, never on the text) | Cancelled |
| 2 | `interrupted` | starts with `Interrupted: the server stopped` (written by `jobs._load_job` for queued and running jobs at a restart) | S-15 |
| 3 | `refused_address` | starts with `LinkRefused:` (the check before a phone-origin download, 4.5, or a connect the confined download refused, 9) | This link can't be analyzed |
| 4 | `live_stream` | starts with `LiveStreamRefused:` (9) | Live streams can't be analyzed |
| 5 | `too_long` | starts with `TooLongRefused:` (longer than `max_link_duration_s` or larger than `max_upload_bytes`, 9) | This video is too long for your computer (the body names `max_link_duration_s` from `capabilities`, 3.4) |
| 6 | `playlist` | `that is a playlist` | That's a playlist |
| 7 | `download_forbidden` | `HTTP Error 403` | The download failed |
| 8 | `download_unavailable` | `Private video`, `Video unavailable`, `This video is (private\|unavailable)`, `Sign in to confirm`, `age-restricted`, `members-only`, `requires authentication`, `login required`, `has been removed`, `HTTP Error 404`, `HTTP Error 410`, `No video could be found` | This video can't be downloaded |
| 9 | `unsupported_site` | `Unsupported URL`, or no site-specific extractor accepted the link (the generic extractor is disabled for phone-origin jobs, 9) | This website isn't supported |
| 10 | `network` | `DownloadError` with `Unable to download webpage`, `Failed to resolve`, `Name or service not known`, `Temporary failure in name resolution`, `timed out`, `Connection (refused\|reset)`, `Network is unreachable`, `HTTP Error 5\d\d`, `IncompleteRead` | Your computer couldn't reach the website |
| 11 | `no_space` | `No space left on device`, `[Errno 28]`, starts with `NoSpaceRefused:` (the free-space check before a phone-origin download, 9) | Your computer is low on space |
| 12 | `tool_missing` | `No such file or directory: 'ffmpeg'`, `ffmpeg: not found`, `yt-dlp is missing` | missing a tool |
| 13 | `not_media` | `AudioExtractionError` with `does not contain any stream`, `Invalid data found when processing input`, `matches no streams`, `moov atom not found`; or `the uploaded file arrived empty` | couldn't read this file |
| 14 | `extract_failed` | any other `AudioExtractionError` | couldn't read this file |
| 15 | `processing_failed` | anything else | The analysis failed |

`error.downloader_version` is added for codes 4 to 10 (the installed yt-dlp version, as 3.4 returns it; brief update on
403s). The exception classes `LinkRefused`, `LiveStreamRefused`, `TooLongRefused` and `NoSpaceRefused` are raised by
the phone-origin download wrapper (9) with fixed English messages, so the rules never depend on yt-dlp's wording.

### 8.3 Clean messages

`state.clean_message(text)`: remove ANSI escape sequences; drop the leading `<ExceptionClass>: ` and a leading
`ERROR: `; replace every absolute POSIX path with its base name; replace the home directory and the user name if they
still occur; collapse whitespace; at most 500 characters. The phone shows it under "Show details" (spec F-20) and never
needs to clean it again.

---------------------------------------------------------------------------------------------------------------

## 9. Downloader hardening (S-23)

Changes in `phonotrainer/download.py` (shared by the browser client and phones):
1. **Format preference** (video): `bv*[height<=720][vcodec^=avc]+ba/bv*[height<=720][vcodec~='^vp0?9']+ba/bv*[height<=720]+ba/b[height<=720]/b`
   — H.264 at 720p or less, then VP9, then any codec, then a single file. `merge_output_format: mp4` stays. Audio only
   stays `ba/b`. The existing test that looks for `height<=720` keeps passing.
2. **JavaScript runtime**: `js_runtimes` is set to the runtimes found on `PATH` among `deno` and `node`
   (`{"deno": {}, "node": {}}` filtered with `shutil.which`; the web build already requires Node). When none is found
   the option is left out (yt-dlp's default). The chosen runtime is reported in `capabilities.downloader.js_runtime`.
3. **Challenge solver**: whether yt-dlp's JavaScript challenge components are installed
   (`find_spec("yt_dlp_ejs")`) is reported as `capabilities.downloader.challenge_solver`. Fetching them at runtime
   (`remote_components`, which downloads code from the internet) is never enabled by default; the operator may set
   `PHONOTRAINER_YTDLP_REMOTE_COMPONENTS=ejs:github` (comma-separated), documented in the README with that warning.
4. **Not pinned**: `yt-dlp` stays unpinned (`>=`); the README's first remedy for a failed download stays "update
   yt-dlp" (the note already added), now with `uv pip install --python .venv/bin/python -U "yt-dlp[default]"`, which
   also brings the challenge components. If WP-01 makes `yt-dlp[default]` the documented install, it records any new
   package in `THIRD-PARTY-NOTICES.md` section 3 after checking its licence.
5. **Error mapping**: section 8.2; the version is in `health` and in download failures.
6. **Progress text unchanged** (1.3).
7. **Phone-origin downloads are confined** (security review H-2). A job created by a phone (6.5, 6.7) carries
   `origin = "phone"` in `job.json`; `JobStore` passes `phone_origin=True` to its `download_fn`, and `download()`
   then wraps yt-dlp as follows (browser and CLI downloads keep today's options, S-21):
   - `allowed_extractors = ["default", "-generic", "-mediasite.*"]` (verified in yt-dlp 2026.8.19: `YoutubeDL.py` reads
     the option and resolves `-name` exclusions; no extractor whose links may name any host remains, and a test scans
     the remaining ones). Every site-specific extractor still works, so "Other website" links keep meaning
     something (spec 8.2), but no arbitrary page is fetched and no embedded URL is followed, and a `t.co` link can
     only hand over to a site-specific extractor. A link no extractor accepts fails as `unsupported_site`.
   - `ssrf.confined_connections()` checks every socket connect of the downloading thread and the threads it starts
     (global unicast only, at the address actually reached, after every redirect and DNS answer) and refuses outside
     programs whose arguments carry a network URL; yt-dlp keeps only its Urllib, Requests and Websockets handlers,
     with `external_downloader: native`, `concurrent_fragment_downloads: 1` and `proxy: ""`. A refused connect fails
     the job as `LinkRefused` (`refused_address`, 8.2).
   - Two phases: `extract_info(url, download=False)` first; then the wrapper refuses, before any media byte is
     fetched, a live, upcoming or post-live stream (`is_live`, or `live_status` in `is_live`, `is_upcoming`,
     `post_live`) with `LiveStreamRefused`, a `duration` above `max_link_duration_s` (3.2) or a known
     `filesize`/`filesize_approx` above `max_upload_bytes` with `TooLongRefused`, and less free space than
     `MIN_FREE_BYTES` plus the expected size with `NoSpaceRefused`; then it downloads the resolved info
     (`process_ie_result(info, download=True)`) with `max_filesize = max_upload_bytes` as a second bound for sizes
     not known in advance. A file that still ends up larger is deleted and the job fails with `TooLongRefused`. When
     the site gives no size, free space is checked against `max_upload_bytes` (twice when formats are merged); a video
     whose site gives no duration is measured with ffprobe after the download and refused as `too_long`.
   - yt-dlp also gets a `logger` that notices its `max_filesize` skip (which yt-dlp 2026.8.19 reports only as a screen
     line, returning no file) and raises `TooLongRefused`; a refused download deletes the partial files it wrote (only
     inside the download directory).
   - Immediately before the first phase it calls `ssrf.check_link()` again (4.5; `LinkRefused` on a refused address).
   - `PHONOTRAINER_YTDLP_REMOTE_COMPONENTS` (point 3) applies to phone-origin downloads too.

Tests use the existing `FakeYDL` seam and a fake `which`; nothing reaches a real site (spec 8.7). `FakeYDL` asserts the
confined options for phone-origin jobs and today's options for browser jobs, and feeds info dicts for a live stream,
an over-long video, an unknown-size video that grows past the limit, and a refused re-resolution.

---------------------------------------------------------------------------------------------------------------

## 10. Python module layout

### 10.1 New package `phonotrainer/phone/`

| Module | Responsibility | Main names |
|---|---|---|
| `__init__.py` | product constants | `DISCOVERY_PORT = 45317`, `MIN_APP_VERSION = 1`, `PAIRING_CODE_TTL_S = 300`, `PAIRING_MAX_WRONG = 5`, `MAX_UPLOAD_BYTES_DEFAULT = 2 GiB`, `MIN_FREE_BYTES = 2 GiB`, `LOW_SPACE_BYTES = 4 GiB`, `MAX_CHUNK_BYTES = 8 MiB`, `UPLOAD_EXPIRY_S = 48 h`, `MAX_CHANNELS = 8`, `MAX_CHANNELS_PER_DEVICE = 2`, `MAX_SESSIONS_PER_DEVICE = 4`, `UPLOAD_IDLE_ABORT_S = 30`, `UPLOAD_TAKEOVER_IDLE_S = 10`, `SIGNATURE_WINDOW_S = 300`, `NONCE_TTL_S = 600`, `MAX_LINK_DURATION_S_DEFAULT = 3600`, `MAX_JSON_BYTES = 16 KiB` |
| `settings.py` | every injectable seam, passed to `create_app(phone=…)`; a public, stable entry point (the host-side fake computer of 12 builds it); new fields only at the end, with defaults: `port` (the listening port, set by `serve`), `discovery_host` (the responder's bind address, default `0.0.0.0`; tests use `127.0.0.1`), `host` (the HTTP bind address, set by `serve`; a loopback one means no responder, 5.1) | `PhoneSettings(config_dir, clock, resolver, media_probe, disk_usage, netinfo, which, runner, discovery_port, port, discovery_host, host)` |
| `config.py` | configuration directory, permission checks at every open (3.1), atomic JSON files, the inter-process lock | `config_dir()`, `JsonFile`, `locked()` |
| `identity.py` | server id, name, phone-access flag, approved networks and the active approved addresses (2.1), library id, capabilities | `ServerIdentity`, `ApprovedNetworks`, `library_id(workspace)`, `Capabilities` |
| `registry.py` | devices, keys, pairing codes, attempts, re-activation proofs, identity proofs, rate limits | `DeviceRegistry`, `Device`, `PairingError`, `hello_proof()` |
| `access.py` | pure ASGI middlewares (`http` and `websocket` scopes): loopback or LAN, approved arrival address, route allowlist on the decoded path, Host validation, JSON body limits, phone-access state; the always-installed loopback Host rule | `PhoneAccessMiddleware`, `LoopbackHostGuard`, `is_loopback()` |
| `auth.py` | request signatures (4.3) to device, for HTTP routes (dependency) and the channel; nonce cache | `current_device()`, `authenticate_ws()`, `verify_signature()` |
| `ssrf.py` | link checks (4.5), including NAT64, 6to4 and Teredo; the connect guard of a confined download (9) | `check_link(url, resolver) -> str`, `LinkRefused`, `confined_connections()` |
| `state.py` | stage, progress details, queue position, title, site, error classification, clean messages | `stage_of()`, `classify_error()`, `clean_message()`, `site_of()`, `clean_title()` |
| `shape.py` | the PhoneJob serializer, the lean variant, the revision | `phone_job(job, caller, store, artifacts)`, `revision_of()` |
| `artifacts.py` | artifact table, checksum cache, media facts, background warmer | `ArtifactIndex`, `ARTIFACTS` |
| `routes.py` | every `/api/phone` HTTP route | `router` (an `APIRouter`) |
| `uploads.py` | sessions, single PUT, resumable PATCH (complete), janitor | `UploadSessions` |
| `events.py` | `/api/phone/events` | `phone_events()` |
| `discovery.py` | UDP responder | `DiscoveryResponder`, `parse_probe()`, `make_reply()` |
| `connections.py` | the connection limits of 4.7 for LAN peers (uvicorn's HTTP protocols with head and body deadlines and the per-peer and total caps), the open-files limit, the filter that drops uvicorn's WebSocket lines (2.1) | `GuardedHttpToolsProtocol`, `GuardedH11Protocol`, `protocol_path()`, `raise_open_file_limit()`, `ClientAddressLogFilter` |
| `netinfo.py` | local IPv4 interfaces with address, prefix and default-gateway MAC for the banner, `phone pair` and the approved networks (`socket.if_nameindex()`, `SIOCGIFADDR`/`SIOCGIFNETMASK` ioctls, `/proc/net/route`, `/proc/net/arp`; Linux, as the project); `Interface` gains `virtual: bool = False`, the kernel's answer (`/sys/class/net/<name>` under `/sys/devices/virtual/`, or a hardware type other than Ethernet) | `interfaces() -> list[Interface]` |
| `cli.py` | `phonotrainer phone …` and the `ui` banner | `phone` (click group), `banner()` |
| `renditions.py` | complete: compact data, Opus, poster, phone video, low-priority worker | `RenditionWorker` |

### 10.2 Changes to existing modules (kept small)

- `phonotrainer/server.py`: `API_VERSION = 6` (7 in complete) with comment lines; `create_app(..., phone:
  PhoneSettings | None = None)` — when given, installs `PhoneAccessMiddleware` (outermost), includes `phone.routes.router`
  and the events route **before** the SPA catch-all, and starts the discovery responder, the artifact warmer, the
  upload janitor (and the rendition worker in complete) in the lifespan; `GET /api/phone/hello` is registered in every
  case, and without phone settings a catch-all for every other `/api/phone/*` path and method answers
  `403 phone_access_off` (new paths only: nothing the browser client uses changes); `reference()` becomes a call to a new `build_reference()` shared with 6.8; a validation-error handler re-wraps
  errors only under `/api/phone/`; the record-yourself handlers are factored into functions the phone routes reuse
  (complete); `LoopbackHostGuard` is installed in every case (1.3); `serve(..., phone=None)` binds `0.0.0.0` when
  phone access is on (or suspended) and no host was given, passes `proxy_headers=False` in every mode, and
  `access_log=False` when phone access is on (or suspended), opens the browser at `127.0.0.1`, writes and removes
  `runtime.json`; it passes its port and host into the phone settings, and to the `--reload` factory through
  `PHONOTRAINER_PORT` and `PHONOTRAINER_HOST`. With phone access it runs uvicorn with
  `http="phonotrainer.phone.connections:Guarded…Protocol"` (4.7; the httptools variant when httptools is installed,
  else the h11 one) and first raises the soft `RLIMIT_NOFILE` to the hard limit (at most 65,536).
- `phonotrainer/jobs.py`: `Job` gains `device_id`, `client_request_id`, `rerun_of`, `origin` (persisted in
  `job.json`, read with defaults, absent from `to_public()`) and a runtime `enqueue_seq`; the worker passes
  `phone_origin=True` to `download_fn` for phone-origin link jobs (9); `JobStore` gains `find_by_request(device_id, key)`,
  keyword arguments `device_id`, `client_request_id`, `rerun_of` on `create_from_url` and `adopt_upload`,
  `adopt_file(path, file_name, options, …)` (moves a finished upload in), `rerun(job_id, device_id, key, audio_only)`
  (6.7) and `queue_position(job_id)`. Existing behaviour and signatures stay compatible (new arguments are keyword-only
  with defaults).
- `phonotrainer/cli.py`: `ui` gains `--phone-access/--no-phone-access`; the `phone` group is registered.
- `phonotrainer/download.py`: section 9 (the confined wrapper is a keyword argument `phone_origin` plus a `limits`
  object, both with defaults that keep today's behaviour).
- `pyproject.toml`: `[tool.setuptools] packages` adds `"phonotrainer.phone"` (the explicit list would otherwise drop
  the subpackage from a regular install).
- No other module of the analysis pipeline changes.

### 10.3 Dependencies and documentation

- No new Python dependency: everything uses the standard library (`socket`, `fcntl`, `hmac`, `hashlib`, `secrets`,
  `ipaddress`, `gzip`, `shutil`, `subprocess`) plus FastAPI and uvicorn, already present. `ffprobe` and `ffmpeg` come
  from the system ffmpeg the pipeline already requires (`THIRD-PARTY-NOTICES.md` section 5). Section 9 point 4 says
  when section 3 of the notices changes.
- `README.md` gains a section "Phone access (Android client)": what it is, `phonotrainer ui --phone-access`,
  `phonotrainer phone pair|list|revoke|off`, where the configuration directory is, the plain-HTTP risk and its
  mitigations (4.8), the residual SSRF risk (4.5), the firewall note (TCP port 8000 and UDP 45317 must be allowed on
  the home network; nothing is changed automatically), approved networks and "suspended" (2.1), renaming the
  computer (3.2), `max_upload_bytes` and `max_link_duration_s`. The "Material" section gains the downloader notes of
  section 9.

---------------------------------------------------------------------------------------------------------------

## 11. Test plan (pytest)

### 11.1 Rules

- Run from the repository root with the repository's virtual environment: `.venv/bin/python -m pytest -q` (in a
  worktree without its own `.venv`, the main checkout's interpreter run from the worktree root imports the worktree's
  package). The existing suite MUST stay green unchanged.
- No network: no real DNS (the SSRF resolver is a fake), no real site (the downloader is `FakeYDL` or
  `_fake_download`), no real phone. UDP and HTTP tests bind only to `127.0.0.1` on ephemeral ports.
- Synthetic media only: `conftest.write_silent_wav`, `mk_analysis`, bytes written by the test; the rendition tests
  that need a real ffmpeg generate their input with `ffmpeg -f lavfi` (`testsrc`, `sine`) and are skipped when ffmpeg
  is absent, as the recording tests already do.
- Time: a fake clock (`PhoneSettings.clock`) drives code expiry, rate limits, last-seen throttling and upload expiry;
  no `sleep` beyond `conftest.wait_until`.
- Isolation: an autouse fixture sets `PHONOTRAINER_CONFIG_DIR` to `tmp_path` for every test; LAN clients are simulated
  with `TestClient(app, client=("192.0.2.20", 50000), base_url="http://192.0.2.10:8000")`, which sets both the ASGI
  `client` and `server` tuples (verified in Starlette 1.3.1's test client), against a `fake_netinfo` whose approved
  network contains 192.0.2.10; loopback with `client=("127.0.0.1", 50000)`.

### 11.2 Fixtures (in `tests/conftest.py` and `tests/phone_fixtures.py`)

| Fixture | Gives |
|---|---|
| `isolated_config` (autouse) | `PHONOTRAINER_CONFIG_DIR` → a temporary directory |
| `fake_clock` | a settable clock used by every phone component |
| `fake_resolver` | a name → addresses table: public names map to global unicast test vectors (marked `hygiene: test-vector`; 203.0.113.0/24 is a documentation range the guard refuses, 4.5 rule 3), hostile names to loopback, private, link-local, CGNAT, multicast, IPv6 unique-local, NAT64 (`64:ff9b::` with an embedded private IPv4), 6to4 and Teredo addresses; a name whose answer changes between two calls (re-resolution); unknown names raise (resolution failure). The private test vectors in `tests/` carry the marker comment `hygiene: test-vector` that the secret scan honours (work-plan WP-06) |
| `fake_netinfo` | interfaces with documentation-range addresses (`wlan0` 192.0.2.10/24 with a gateway MAC from the documentation range, a `docker0` bridge, loopback), switchable per test (another network, no network) |
| `fake_probe` | media facts per file name (codec, size, rotation, start times) |
| `phone_settings` | `PhoneSettings` wired with the fakes above |
| `phone_app` | `create_app(store=…, phone=phone_settings, web_dist=…, allowed_roots=[tmp_path])` with the store using `fake_analyze` and `_fake_download` |
| `lan_client`, `loopback_client` | `TestClient`s as in 11.1 |
| `paired` | creates a code with the registry, pairs through `POST /api/phone/pair`, returns `(signing_client, device_id, key)`; `signing_client` adds a fresh `Authorization: PT1 …` to every request (a 20-line helper in `phone_fixtures.py`, the reference implementation the phone's interceptor is checked against) |
| `blocking_analyze` | a `fake_analyze` that waits on a `threading.Event` (worker busy) |
| `stalled_body` | an upload body generator that sends some bytes and then blocks on an event (a phone whose Wi-Fi dropped) |

### 11.3 Test modules

| Module | Proves | S-nn / ACs |
|---|---|---|
| `test_phone_config.py` | directory resolution (env, XDG, default), `0700`/`0600` modes, permissions re-checked and tightened at every open, another owner refused, atomic writes, lock between two registries on one directory, server id stable across restarts and workspaces, default name "PhonoTrainer computer" (never the host name), library id per workspace, approved networks recorded by `phone on` (virtual interfaces excluded unless named), suspended start on an unapproved network and activation within 30 s, `create_app()` without phone settings touches no configuration directory | S-01, S-02, D-34 |
| `test_phone_registry.py` | code format, single use, 300 s expiry, lock after 5 wrong attempts (6th refused with the right code), `code_invalid` with no code, rate limits per source and in total, token never stored (only its key), revoke one and all, re-activation with a valid `previous_token_proof` keeps the id and kills the old key, a wrong or missing proof creates a new device instead, `hello_proof` vector (fixed key, server id, device id and challenge → fixed hex), last-seen throttling, `Cc`/`Cf`/`Zl`/`Zp` stripped from names | S-04, F-02-AC3 |
| `test_phone_access.py` | the route matrix of 4.2 in every mode (browse, path create, import, review PUT, delete, corpus, `/api/health`, `/ws/jobs`, SPA from the LAN → 403; the same from loopback as before; a LAN request on an unapproved address → 403); signatures: missing, malformed, wrong MAC, unknown and revoked device → `credential_rejected`, a replayed nonce refused, a timestamp 301 s off → `clock_skew` with `server_time`, the reference vector of 4.3 accepted; a CLI revoke applied at the next request; Host validation (LAN: names refused, wrong IP refused; loopback: names accepted, `Host: attacker.example` against `/api/browse` → `bad_host` **with phone access off too**); `/api/phone/../browse`, `/api/phone/%2e%2e/health` and an unknown `/api/phone/x` (JSON 404, never the SPA), over HTTP and WebSocket; proxy headers ignored; 16 KiB limit; no path, home directory or user name in any response of a full session or in any served JSON artifact | S-05, S-06, S-08, F-02-AC8, QT-8 |
| `test_phone_api.py` | `hello` in both states, with and without a challenge (proof for a known, a revoked and an unknown device; `server_time` always), challenge rate limit; `pair` success and every error, the "Paired: … (from …)" hand-off through `pairing.json`; `health` shape, capabilities (downloader version string as installed), and an answer within 1 s while `blocking_analyze` holds the worker; `DELETE devices/self`; listing shape and order, own vs other (`device_id` null on other phones' jobs), queue positions, stage and progress mapping, title cleaning, site labels, unknown media facts as 0, revision stable under progress and changed by status; reference identical to `/api/reference` | S-02, S-11, S-13, S-17 |
| `test_phone_artifacts.py` | every artifact name and role, `preparing` until checksummed then `ready`, ETag = SHA-256, `Range` 206, `If-Range` match and mismatch, 416, `artifact_not_ready`, `unknown_artifact`, `original` unavailable when the file is gone, media facts and `start_offset_ms` signs from `fake_probe` | S-11, QT-9 (server side) |
| `test_phone_jobs.py` | link creation; the same key twice → one job (sequential and concurrent); `key_conflict`; SSRF (loopback, private, link-local, CGNAT, multicast, IPv6 ULA, IPv4-mapped, NAT64, 6to4, Teredo, literal IPs, user-info, `ftp:`, 2,049 characters, resolution failure → 503); the check repeated at re-run and before the download (a name that re-resolves to a private address fails the job with `refused_address`); cancel (queued, running, done → `already_finished`, error → no-op); delete rules; re-run of a link with `audio_only`, of an upload (file moved, not copied: same inode), repeated key after success, `source_gone`, not own; restart of the store with queued and running jobs → `interrupted` codes, re-runs in arrival order | S-06, S-07, S-09, S-15, S-22, QT-3, QT-5, QT-12, F-20-AC3, F-20-AC4, F-20-AC5 |
| `test_phone_uploads.py` | declare: unsupported type, too large, no space (fake disk usage), reservations (two declarations that fit alone but not together → the second gets `no_space`), a fifth open session → `upload_busy`, existing job → `done`; PUT: missing length, wrong length, checksum mismatch deletes the part, success creates the job with device and key and moves the file (no second copy), repeated PUT after success → 200; interrupted body deletes the part (core); `stalled_body`: aborted after 30 s (fake clock) and, before that, a second `PUT` for the same key after 10 s idle replaces it and succeeds; a second device's upload is not blocked; `DELETE`; janitor removes sessions older than 48 h; complete: `GET` offsets, `PATCH` sequence, `offset_mismatch`, `chunk_too_large`, resume after an interruption, the same idle rules | S-09, S-10, QT-4 (server side) |
| `test_phone_events.py` | upgrade refused without a signature, with a wrong one and with a bad Host; `hello` then `snapshot` lean (no artifacts, no progress log, no paths); `job` events for a lifecycle; `deleted` after a re-run and a delete; coalescing; the ninth channel and a device's third channel refused | S-14, S-05 |
| `test_phone_discovery.py` | `parse_probe` accepts the exact datagram and drops malformed, oversized and wrong-type ones; the reply holds only the four fields and the nonce; replies only to private sources inside an approved network (a source on the `docker0` network gets none); rate limits; a real responder on `127.0.0.1` with an ephemeral port answers a real UDP probe; not started with phone access off or suspended; port busy → `discovery: false` | S-03 |
| `test_phone_state.py` | the stage table equals `PREFIXES` of `web/src/jobs/stages.ts` (read-only regex parse: a drift guard); every rule of 8.2 on realistic texts (the ANSI-coloured 403, private video, playlist, unsupported URL, network, ENOSPC, missing ffmpeg, interrupted, the four `…Refused:` classes); `cancelled` decided by status for both store messages; `clean_message` removes ANSI, paths, the home directory and the user name | S-13, S-15 |
| `test_phone_cli.py` | click `CliRunner`: `ui --phone-access` remembered and `serve` called with phone settings and `access_log=False` (serve stubbed), `--no-phone-access`, `--phone-interface`; `phone on/off/status/pair --no-wait/list/revoke/name` with a fake running server (`runtime.json` + live pid); `phone on` with no phone-access server prints the restart line; `phone pair` with phone access off exits 1 without a code, prints wrong attempts and exits 4 when locked; outputs never contain a token, key or hash; `pair` without a server exits 1 | S-01, S-04 |
| `test_download.py` (additions) | the format string order, `js_runtimes` from a fake `which` (node only, deno and node, none), remote components only from the environment variable, the version reported; for phone-origin jobs `allowed_extractors = ["default", "-generic"]`, `max_filesize`, the two-phase refusals (live, upcoming, post-live, over-long, over-large, low space) before any media byte, the re-check; for browser jobs today's options exactly | S-23, S-07 |
| `test_phone_compat.py` | `REQUIRED_API_VERSION` read from `web/src/api.ts` ≤ `API_VERSION`; with phone access off, the key set of `/api/jobs` items, `/api/health` and `/ws/jobs` messages equal today's and `Host: 127.0.0.1:8000` / `localhost:5173` still pass; download progress texts unchanged; `pyproject.toml` lists `phonotrainer.phone` | S-21 |
| `test_phone_renditions.py` (complete) | compact gzip decodes to the same JSON as `analysis.json`; Opus, poster and phone-video argument lists (fake runner); statuses `preparing` → `ready`/`failed`; a missing original → `unavailable`; no task starts while an analysis runs; back-fill at start; real-ffmpeg tests on synthetic `lavfi` media (skipped without ffmpeg) check codec, size, rotation and that the phone video starts at offset 0 | S-12 |
| `test_phone_record.py` (complete) | `/api/phone/jobs/{id}/contour` and `/compare` give the same results and codes as the existing routes (reusing `test_recording_api.py`'s synthetic takes), signed and from the LAN | S-16 |
| `test_host_fake_computer.py` | the host-side fake computer of 12 starts on throwaway directories, refuses every download, writes nothing under the working directory it was started from, and serves a full pair → list → download → upload → cancel → re-run → cancel → delete → unpair session | S-21 (no side effects), 12 |

The two drift guards that read `web/` files (`test_phone_state.py`, `test_phone_compat.py`) stay; when a change by the
web agent breaks them, the integrator of the current wave owns the fix (work-plan 2.1), never by editing `web/`.

### 11.4 Definition of done for server work (architecture 23 item 8)

1. `.venv/bin/python -m pytest -q` from the repository root: all green, existing and new.
2. `cd web && npx vitest run`: green, and `git status --porcelain -- web/` is empty (nothing under `web/` changed).
3. Every S-nn of the tier has at least one test above (X-AC9).
4. A scripted manual smoke on loopback with a temporary workspace, configuration directory **and working directory**
   (so `data/` and `downloads/` land there, never in the repository): `phonotrainer ui` with phone access off serves
   the SPA as before; with `--phone-access`, `curl` with the `phone_fixtures.py` signer reaches `hello`, `pair` (code
   from `phonotrainer phone pair --no-wait`), `health` and `library`. No real phone and no real link are involved.
5. README updated; English only; no address, token, code or personal path in any committed file.

### 11.5 Traceability: S-nn → tests

S-01 config, cli · S-02 config, api · S-03 discovery · S-04 registry, cli · S-05 access, events · S-06 access, jobs ·
S-07 jobs, download · S-08 access · S-09 jobs, uploads · S-10 uploads (complete) · S-11 api, artifacts · S-12 renditions
(complete) · S-13 api, state · S-14 events · S-15 state, jobs · S-16 record (complete) · S-17 api · S-21 compat ·
S-22 jobs · S-23 download.

---------------------------------------------------------------------------------------------------------------

## 12. The Android fake computer and the host-side fake computer

- `FakeComputer` (`:core:testing-jvm`, architecture 21.3, work-plan WP-05a) implements sections 4.3 and 5 to 8 of this
  document, including request signatures and identity proofs and the complete-tier routes behind capability flags,
  with the same status codes, bodies, idempotency rules, Range and If-Range behaviour, ownership rules and channel
  messages. Its own JVM tests are written from this document, one per route and per error code that the phone
  handles.
- Drift between the fake and the server is caught by a **smoke test against the real server code** (work-plan WP-19)
  on the API 36 emulator. It does not use `phonotrainer ui`: that command would run the real pipeline (1.8 GB of
  models) on anything uploaded, cancel only at the next progress message, and write `data/phonotrainer.db` and
  `downloads/` relative to its working directory, which from the repository root is the owner's corpus (delivery
  review D-09). Instead, `android/scripts/device-qa/host_computer.py` (WP-06) starts a **host-side fake computer**:
  - it imports the worktree's `phonotrainer` (it sets `PYTHONPATH` to the repository root it lives in and runs with
    the repository's virtual environment) and builds `create_app(store=JobStore(<tmp>/workspace,
    analyze_fn=<synthetic analysis writer>, download_fn=<always refuses>, corpus=None), phone=PhoneSettings(
    config_dir=<tmp>/config, …), web_dist=None, allowed_roots=[<tmp>])`, served by uvicorn on `127.0.0.1` (or, for
    the test phone in WP-25, on the host's approved Wi-Fi address with `--lan`), with `<tmp>` a fresh temporary
    directory that is also its working directory and is deleted at exit;
  - the synthetic analysis writer produces an `analysis.json` shaped like `tests/conftest.py`'s `mk_analysis` and a
    silent `audio.wav` (the uploaded file stays the job's original); it takes about a second and emits the pipeline's
    progress strings, so cancellation is prompt;
  - the download function raises `DownloadError("downloads are disabled on the fake computer")`, so no link can ever
    reach a site (spec D-36), whatever a test does;
  - it seeds one finished synthetic clip (imported through the store, not through HTTP) and prints a pairing code to
    stdout for the test harness, which passes it to the instrumented test as a run argument; it is never written to
    a file;
  - `create_app(phone=…)`, `PhoneSettings` and the `JobStore` seams are a stable entry point that WP-01 keeps
    importable (10.1); `test_host_fake_computer.py` (11.3) guards it.
- The smoke test pairs through the manual address `10.0.2.2:<port>` (the emulator's alias of the host's loopback;
  discovery does not cross the emulator's network), probes, lists, downloads and verifies the seeded clip's study
  pack, uploads a synthetic two-second WAV, cancels the job it created and waits for `cancelled`, re-runs it, cancels
  the re-run and waits for `cancelled`, deletes it (a `cancelled` job is deletable, 6.7), and unpairs. It never sends a
  link. Every [emulator] step of device QA (work-plan WP-25) uses the same host-side fake computer.
- One optional run with the real pipeline (`phonotrainer ui --phone-access` on a throwaway workspace, configuration
  directory and working directory, local files only) is part of WP-25's real-computer pass, never of CI.
