// :feature:requests: Requests (F-20), the request detail and "Add a clip" (F-19) (architecture 2.2, 12; design 10.2
// P-03 to P-05).
plugins {
  id("phonotrainer.android.feature")
}

dependencies {
  // rememberLauncherForActivityResult: the system file picker and the notification permission prompt (architecture 12).
  implementation(libs.androidx.activity.compose)
}
