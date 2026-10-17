// :feature:share: ShareActivity and the share confirmation sheet (architecture 2.2, 12; spec 8; design 9.22).
plugins {
  id("phonotrainer.android.feature")
}

dependencies {
  implementation(libs.androidx.activity.compose)
  // IntentCompat: the typed reads of EXTRA_STREAM on every API level (architecture 12).
  implementation(libs.androidx.core.ktx)
}
