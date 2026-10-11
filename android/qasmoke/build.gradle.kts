// The black-box smoke of the release-like build (architecture 21.6, delivery review D-19): UiAutomator drives the
// unmodified R8 `qa` APK of :app on the managed device. Run with `./gradlew :qasmoke:pixel9Api36QaAndroidTest`.
plugins {
  id("phonotrainer.android.test")
}

android {
  namespace = "com.mapineda48.phonotrainer.qasmoke"
}
