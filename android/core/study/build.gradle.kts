// Sets, questions (the quiz port), practice stats, saved items, seen state and positions (architecture 10); WP-14.
// At the contract freeze: working stubs (nothing seen, nothing saved). WP-14 adds :core:analysis when it exists.
plugins {
  id("phonotrainer.android.library")
  id("phonotrainer.android.hilt")
}

dependencies {
  implementation(project(":core:domain"))
  implementation(project(":core:common"))
  implementation(project(":core:corpus"))
  implementation(project(":core:database"))
  implementation(project(":core:datastore"))

  testImplementation(project(":core:testing-jvm"))
}
