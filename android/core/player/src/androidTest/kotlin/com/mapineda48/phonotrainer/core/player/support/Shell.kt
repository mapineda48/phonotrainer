package com.mapineda48.phonotrainer.core.player.support

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/** Shell commands on the managed device (never a physical phone: these tests run only on the GMD). */
object Shell {
  fun run(command: String): String {
    val pfd: ParcelFileDescriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
  }
}

/**
 * "Earphones unplugged": the system's `AUDIO_BECOMING_NOISY` broadcast. It is a protected broadcast, so it is sent
 * as root on the emulator image (its `su`); the shell user is tried first.
 */
object Noisy {
  private const val ACTION = "android.media.AUDIO_BECOMING_NOISY"

  fun send() {
    val asShell = Shell.run("am broadcast -a $ACTION")
    if ("Broadcast completed" in asShell && "Permission Denial" !in asShell && "Security" !in asShell) return
    Shell.run("su 0 am broadcast -a $ACTION")
  }
}

/** A measurement line in the device log, under the tag the QA notes grep for (`PhonoPlayerQA`). */
fun qa(line: String) = println("PhonoPlayerQA: $line")
