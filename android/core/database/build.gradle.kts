// The two Room databases of architecture 4.1 to 4.4: LibraryDatabase (library.db, WAL, not backed up) and
// LearningDatabase (learning.db, TRUNCATE, backed up). Entities, the @Database classes, exported schemas and
// DatabaseModule are frozen at B1 (integrator); each DAO file belongs to the work package of its area.
plugins {
  id("phonotrainer.android.library")
  id("phonotrainer.android.room")
  id("phonotrainer.android.hilt")
}

dependencies {
  implementation(project(":core:domain"))

  testImplementation(project(":core:testing-jvm"))
}
