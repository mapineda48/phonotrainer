// :feature:share: ShareActivity and the confirmation sheet (architecture 2.2, 12). At the contract freeze the activity
// is a placeholder whose manifest entry is final.
plugins {
  id("phonotrainer.android.feature")
}

dependencies {
  implementation(libs.androidx.activity.compose)
}
