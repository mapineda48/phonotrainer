# Security notes for the Android client

The security posture of PhonoTrainer for Android (`android/`), the risks it accepts and the list of what its release
build exposes. It belongs to the integrator (work-plan 3.2); the decisions behind it are in
[`docs/architecture.md`](docs/architecture.md) section 20 and [`docs/product-spec.md`](docs/product-spec.md) section 11.

## Reporting a vulnerability

Please **do not open a public issue**. Report it privately through GitHub's private vulnerability reporting ("Report a
vulnerability" in the repository's **Security** tab), with the affected commit, the steps to reproduce and the impact
you expect. Only the latest commit of the default branch is supported. Never paste a pairing code, a device token or
an address of your network into a report; the app's "Copy diagnostics" leaves the credential, the addresses and the ids
out for this reason.

## What the app talks to

- Only **your own computer** running PhonoTrainer, on your home Wi-Fi, after you turn phone access on there and pair
  the phone with a code shown in the computer's terminal. There are no accounts, no cloud, and no analytics,
  crash-reporting or advertising SDK (spec X-AC1); a shared link is sent to your computer, never opened by the app.
- Requests made while the computer is out of reach wait on the phone (the device queue) and are sent when it is
  reachable again.

## Transport risk (accepted for version 1)

Plain HTTP on the home network (the server has no TLS): someone on the same Wi-Fi could read clips, links and requests
in transit. A phone's credential could be captured only by someone recording the pairing exchange itself, the one
moment it travels (the code that starts it is shown only in the computer's terminal, single use, valid 5 minutes).
After pairing the credential never travels again: **every request is signed with it, so a recorded request reveals
no reusable secret and cannot be replayed** (spec 11.6, D-38). Mitigations: opt-in phone access on approved networks
only, per-device revocable credentials, signed requests, the computer's proof of identity before the phone sends
anything, and routes restricted to the phone API for devices on the network.

**How cleartext is confined.** An Android network security configuration can only name hosts known at build time,
and the computer's address is known only at run time and must never be committed. So:
- the network security configuration (`app/src/main/res/xml/network_security_config.xml`) permits cleartext in its
  base configuration and names no address: **the platform setting alone would allow cleartext to any host**;
- the app enforces the restriction in code: its single shared HTTP client (also used by media playback and image
  loading) refuses any cleartext request whose host is not the paired computer's current address, or, before
  pairing, an address in a private or link-local range being checked or paired; every other cleartext request fails
  before any byte is sent (architecture 5.2, X-AC6).

**Turning it off.** On the computer, `phonotrainer phone off` (or starting with `--no-phone-access`) closes the phone
API and `phonotrainer phone status` shows the state and the paired phones (root README, "Phone access (Android
client)"); revoking one phone, which stops its credential at its next request, is `phonotrainer phone revoke` in the
server contract (`docs/server-contract.md` section 2). On the phone: Settings, Your computer, "Forget this computer…".

## Version 2: encrypted and authenticated transport (design, not implemented)

The owner asked for a concrete design that removes the transport risk in a later version (spec Q-1, S-L3). It keeps
the learner's pairing gesture (a 6-digit code typed on the phone) and adds no account or cloud service:

1. **The computer's certificate.** When phone access is first turned on, the computer creates a self-signed
   certificate for an ECDSA P-256 key in its configuration directory (private to the user, like the phone keys today)
   and serves the phone API over TLS 1.3 only; the loopback interface keeps plain HTTP for the browser. No host name is
   checked: the computer's identity is the SHA-256 of the certificate's public key (its SPKI pin).
2. **Pairing binds the pin to the code.** Six digits are too few to protect anything by hashing: whoever sees a hash
   or a MAC of the code tries the million values offline. So the code is the password of a balanced PAKE (CPace or
   SPAKE2) run inside the first TLS connection, where an attacker in the middle gets one online guess per code (and
   the code still locks after 5 wrong attempts). Each side's key-confirmation MAC covers the SPKI pin it sees: a relay
   that terminates TLS with its own certificate makes the two pins differ, so confirmation fails on both sides without
   revealing the code. On success the computer issues the device token inside that channel and the phone stores the
   pin with the pairing (encrypted with its Keystore key, like the token).
3. **After pairing.** The phone's one HTTP client trusts exactly the pinned key for that computer (a trust manager
   that accepts only that SPKI, never the system CAs, never a fallback to plain HTTP). Request signatures and the
   identity proof of version 1 stay, as defence in depth. Discovery stays a hint: an address it finds is used only
   after the TLS handshake proves the pin.
4. **Keys change only by pairing again.** A new certificate (a lost configuration directory, a reinstall) is a new
   computer: the phone shows "Connect again", as for a changed server id today. Revoking a phone is unchanged.
5. **No downgrade.** The computer advertises TLS in `hello`; a version-2 phone never sends a credential over plain HTTP
   to a computer that advertised it, and treats a computer that stops advertising it as a different computer.
   Version-1 phones keep working over plain HTTP until the owner turns plain phone access off.
6. **What it costs.** On the computer: certificate generation (the `cryptography` package, a new Python dependency for
   `THIRD-PARTY-NOTICES.md` section 3), the TLS options of the server, a PAKE implementation, and a server-contract
   revision. On the phone: the pinned trust manager and a PAKE implementation from a reviewed library or a small port
   checked against shared test vectors, as the signing vectors are shared today.

## On the phone

- **Credential storage** (architecture 20.3): the pairing token is encrypted with an AES-256-GCM key that never leaves
  the Android Keystore and is stored only as ciphertext in a file that is not backed up; the plaintext exists in memory
  only while signing. If it cannot be decrypted (restored to another phone), the pairing counts as revoked.
- **Backup** (20.1): an allowlist of the learning database and the settings file; never the pairing, the library or
  the media. Cloud backup is skipped on devices that cannot encrypt it.
- **Logging** (20.4): one logging facade; release builds keep no debug or info logs and log warnings and errors as event
  codes only, never addresses, links, file names or titles. The HTTP logger exists only in debug builds, at header
  level with `Authorization` redacted.
- **Exported components** (20.1): only `MainActivity` (the launcher) and `ShareActivity` (the share target) are
  exported; internal targets reach `MainActivity` only through a non-exported alias. The full merged list is the table
  at the end of this file, checked against every release build.
- **Task hijacking on Android 10.** `ShareActivity` runs with an empty task affinity, so a share never joins another
  app's task. `MainActivity` keeps the default affinity, because an empty affinity on a `singleTask` launcher activity
  would change how the launcher finds its task. Android 10 (API 29, the oldest version this app supports, spec NFR-01)
  lacks the platform fix for StrandHogg-style task hijacking that Android 11 (API 30) brought: on an Android 10 device a
  malicious app already installed could place its own activity in PhonoTrainer's task. This residual risk is accepted
  (security review L-11); it does not exist on Android 11 and later.
- **Release builds** are shrunk and optimized with R8, are not debuggable, and are signed with one key kept outside
  the repository (architecture 20.5; see `README.md`, "Release signing"). Debug and `qa` builds carry the per-machine
  debug key and are installed only on emulators.

## Repository practices

- The repository is public: no address, serial, token, key, pairing code, host name, home path or personal data in any
  committed file. `scripts/hygiene/check_repo.py` checks it (with a local denylist built at run time and never
  stored), as a pre-commit and pre-push hook and in CI; keystores, `keystore.properties` and the files device debugging
  produces are ignored (`android/.gitignore`).
- Dependencies come only from Google Maven, Maven Central and the Gradle Plugin Portal (plugins only), with content
  filters, through one version catalog; every runtime library and its licence is listed and checked
  (`THIRD-PARTY-NOTICES.md` section 7). The Gradle wrapper is pinned by checksum and validated in CI; CI runs with
  read-only permissions and actions pinned to commit SHAs.

## Merged release manifest

Every permission the merged release manifest requests and every component it exports (`android:exported="true"`),
fully qualified, and nothing else. From `android/`, after `./gradlew assembleRelease`,
`python3 scripts/hygiene/check_repo.py --rules manifest --require-manifest` fails when this table and the manifest
differ (architecture 20.1 and 20.7); CI runs the same check. A change that adds or removes a permission or an exported
component updates this table in the same merge.

| Kind | Name |
|---|---|
| permission | `android.permission.INTERNET` |
| permission | `android.permission.ACCESS_NETWORK_STATE` |
| permission | `android.permission.WAKE_LOCK` |
| permission | `android.permission.RECEIVE_BOOT_COMPLETED` |
| permission | `android.permission.FOREGROUND_SERVICE` |
| permission | `com.mapineda48.phonotrainer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` |
| activity | `com.mapineda48.phonotrainer.MainActivity` |
| activity | `com.mapineda48.phonotrainer.feature.share.ShareActivity` |
| service | `androidx.work.impl.background.systemjob.SystemJobService` |
| receiver | `androidx.work.impl.diagnostics.DiagnosticsReceiver` |
| receiver | `androidx.profileinstaller.ProfileInstallReceiver` |

Where each entry comes from:

- `INTERNET` and `ACCESS_NETWORK_STATE` are declared by `:app` (architecture 20.1); WorkManager also requests
  `ACCESS_NETWORK_STATE`.
- `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` and `FOREGROUND_SERVICE` are merged from WorkManager (`work-runtime`), as
  architecture 20.1 expects for the first two; `FOREGROUND_SERVICE` belongs to WorkManager's own foreground service,
  which is not exported. The other permissions of 20.1 join this table when the modules that use them declare them:
  `POST_NOTIFICATIONS` (`:core:notifications`) and `FOREGROUND_SERVICE_DATA_SYNC` (`:core:sync`).
- `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` is the signature-level permission that AndroidX Core declares and
  requests for the app itself, so a receiver registered as not exported stays private on versions before API 33.
- `MainActivity` is the launcher activity (`singleTask`, default affinity). `ShareActivity` (`:feature:share`) is the
  share target: exported with exactly the three `SEND` / `SEND_MULTIPLE` filters of architecture 12,
  `taskAffinity=""` and `excludeFromRecents`. The `.InternalEntry` alias is not exported, so no other app can hand
  `MainActivity` an internal target (architecture 16.4).
- `SystemJobService` is exported by WorkManager and guarded by `android.permission.BIND_JOB_SERVICE`, so only the
  system's job scheduler can bind to it.
- `DiagnosticsReceiver` (WorkManager) and `ProfileInstallReceiver` (profile installer) are exported by their
  libraries and guarded by `android.permission.DUMP`, so only the shell and the system can send to them.
