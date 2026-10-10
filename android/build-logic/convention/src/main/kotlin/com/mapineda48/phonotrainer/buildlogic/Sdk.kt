package com.mapineda48.phonotrainer.buildlogic

import org.gradle.api.JavaVersion

/** SDK levels, toolchain and test device of the whole build (architecture 2.4, 2.5, 21.1, 21.2). */
object Sdk {
  const val COMPILE = 37
  const val MIN = 29

  /** Spec D-18: on Android 17 an app targeting 36 keeps implicit local-network access. */
  const val TARGET = 36

  const val JAVA = 17
  val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_17

  /** Robolectric 4.17 needs Java 21 for sdk 36, and the toolchain is 17 (architecture 21.2). */
  const val ROBOLECTRIC = 35

  /** The one Gradle Managed Device (architecture 21.1): `./gradlew pixel9Api36DebugAndroidTest`. */
  const val MANAGED_DEVICE = "pixel9Api36"
  const val MANAGED_DEVICE_API = 36

  const val NAMESPACE_ROOT = "com.mapineda48.phonotrainer"
}
