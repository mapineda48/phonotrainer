# Security notes for the Android client

This file belongs to the integrator (work-plan 3.2). Its first full version (the residual task-hijacking risk on
Android 10 and the transport risk of spec 11.6, architecture 20.1) comes with WP-19's first week after B1. Until then
it holds the list that the repository hygiene check compares with the build.

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
