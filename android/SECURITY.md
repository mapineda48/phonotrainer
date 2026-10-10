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
| permission | `com.mapineda48.phonotrainer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` |
| activity | `com.mapineda48.phonotrainer.MainActivity` |
| receiver | `androidx.profileinstaller.ProfileInstallReceiver` |

Where each entry comes from:

- `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` is the signature-level permission that AndroidX Core declares and
  requests for the app itself, so a receiver registered as not exported stays private on versions before API 33.
- `MainActivity` is the launcher activity. `ShareActivity` (the share target, `taskAffinity=""`) and the permissions
  of architecture 20.1 join this table when they enter the manifest.
- `ProfileInstallReceiver` is exported by the profile installer library and guarded by `android.permission.DUMP`, so
  only the shell and the system can send to it.
