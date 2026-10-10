package com.mapineda48.phonotrainer.core.notifications

import android.app.PendingIntent
import android.content.Context
import com.mapineda48.phonotrainer.core.navigation.InternalEntry
import com.mapineda48.phonotrainer.core.navigation.NavTarget
import com.mapineda48.phonotrainer.core.navigation.PhonoNavSerialization

/**
 * The tap of every notification (architecture 13, 16.4): an immutable activity `PendingIntent` to the non-exported
 * `.InternalEntry` alias with the serialized [NavTarget]. `PendingIntent` identity ignores extras, so each target has
 * its own request code (a stable hash of the serialized target) and its own `data` URI: two notifications for two
 * clips open two clips. `MainActivity` applies the target once, then clears it, so a recreate does not navigate again.
 */
object NotificationTargets {
  fun contentIntent(context: Context, target: NavTarget): PendingIntent = PendingIntent.getActivity(
    context,
    PhonoNavSerialization.requestCode(target),
    InternalEntry.intent(context, target),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
  )
}
