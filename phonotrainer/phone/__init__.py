"""Phone access: the API the Android client talks to (`/api/phone/*`).

Everything here is off unless the operator turns phone access on
(`phonotrainer ui --phone-access` or `phonotrainer phone on`). With it off the
server behaves as it always did for the browser client; see
`android/docs/server-contract.md` for the whole contract this package
implements.

The constants below are product constants shared with the app (the discovery
port in particular is the same number as the app's `DiscoveryProtocol.PORT`).
"""

from __future__ import annotations

KIB = 1024
MIB = 1024 * KIB
GIB = 1024 * MIB

DISCOVERY_PORT = 45317              # UDP; the app uses the same constant
MIN_APP_VERSION = 1                 # the oldest app versionCode this server accepts
PAIRING_CODE_TTL_S = 300            # a pairing code is valid for 5 minutes
PAIRING_MAX_WRONG = 5               # wrong attempts that lock the code
MAX_UPLOAD_BYTES_DEFAULT = 2 * GIB  # editable in server.json
MIN_FREE_BYTES = 2 * GIB            # free space an upload must leave behind
LOW_SPACE_BYTES = 4 * GIB           # below this, `health` says low_on_space
MAX_CHUNK_BYTES = 8 * MIB           # resumable uploads (v1-complete)
UPLOAD_EXPIRY_S = 48 * 3600         # abandoned upload sessions are deleted
MAX_CHANNELS = 8                    # open /api/phone/events sockets in total
MAX_CHANNELS_PER_DEVICE = 2
MAX_SESSIONS_PER_DEVICE = 4         # open upload sessions per phone
UPLOAD_IDLE_ABORT_S = 30            # a body silent this long is aborted
UPLOAD_TAKEOVER_IDLE_S = 10         # "newest wins" after this much silence
SIGNATURE_WINDOW_S = 300            # |ts - server time| allowed in a signature
NONCE_TTL_S = 600                   # how long a used nonce is remembered
MAX_LINK_DURATION_S_DEFAULT = 3600  # the longest phone-origin link video
MAX_JSON_BYTES = 16 * KIB           # JSON request bodies on phone routes

DEFAULT_COMPUTER_NAME = "PhonoTrainer computer"
