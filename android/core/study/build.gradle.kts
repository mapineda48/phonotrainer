// Sets, questions (the quiz port), practice stats, saved items, seen state and positions (architecture 10; work-plan
// WP-14). It reads the corpus, the library and the settings only through :core:domain interfaces (work-plan 6;
// architecture review finding 11), and its rows through WP-14's learning DAOs in :core:database.
plugins {
  id("phonotrainer.android.library")
  id("phonotrainer.android.hilt")
  id("phonotrainer.kotlin.serialization")
}

dependencies {
  implementation(project(":core:domain"))
  implementation(project(":core:common"))
  implementation(project(":core:database"))

  testImplementation(project(":core:testing-jvm"))
  // The repository tests build an in-memory LearningDatabase.
  testImplementation(libs.androidx.room.runtime)
}
