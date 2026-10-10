package com.mapineda48.phonotrainer.core.navigation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The non-exported activity alias that delivers internal targets (architecture 16.4, 13): notification
 * `PendingIntent`s and the share sheet's "Open PhonoTrainer" / "See requests" build their intents here, so the alias
 * name and the extra are defined once. `MainActivity` honours [EXTRA_TARGET] only on an intent whose component is
 * this alias.
 */
object InternalEntry {
  /** The alias declared by :app's manifest (`.InternalEntry` of the application namespace). */
  const val ALIAS_CLASS: String = "com.mapineda48.phonotrainer.InternalEntry"

  /** The serialized [NavTarget] (`PhonoNavSerialization.encode`). */
  const val EXTRA_TARGET: String = "com.mapineda48.phonotrainer.extra.TARGET"

  /** The scheme of the `data` URI that only makes each target's intent distinct; no intent filter matches it. */
  const val URI_SCHEME: String = "phonotrainer-internal"

  /** An intent that opens [target] in the app. */
  fun intent(context: Context, target: NavTarget): Intent =
    Intent(Intent.ACTION_VIEW)
      .setComponent(ComponentName(context.packageName, ALIAS_CLASS))
      .setData(Uri.fromParts(URI_SCHEME, PhonoNavSerialization.requestCode(target).toString(), null))
      .putExtra(EXTRA_TARGET, PhonoNavSerialization.encode(target))

  /** Whether [intent] came through the alias (only then may its target be honoured). */
  fun isInternal(intent: Intent?): Boolean = intent?.component?.className == ALIAS_CLASS

  /** The target [intent] carries, if it came through the alias and the extra decodes. */
  fun targetOf(intent: Intent?): NavTarget? =
    if (isInternal(intent)) PhonoNavSerialization.decode(intent?.getStringExtra(EXTRA_TARGET)) else null
}
