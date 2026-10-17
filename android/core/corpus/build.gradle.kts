// Clip indexer, parsed-clip cache, media resolution, leases, and the shorts, occurrence and feed queries (architecture
// 9.5; work-plan WP-10). The derivations themselves are :core:analysis's pure functions; the rows live in
// :core:database (the DAOs ShortDao and OccurrenceDao belong to WP-10). The reference comes through the
// ReferenceRepository interface of :core:domain, so :core:content is not needed.
plugins {
  id("phonotrainer.android.library")
  id("phonotrainer.android.hilt")
}

dependencies {
  implementation(project(":core:domain"))
  implementation(project(":core:common"))
  implementation(project(":core:analysis"))
  implementation(project(":core:database"))

  testImplementation(project(":core:testing-jvm"))
  // The tests build an in-memory LibraryDatabase and write analyses with AnalysisFixtures' JSON builders.
  testImplementation(libs.androidx.room.runtime)
  testImplementation(libs.kotlinx.serialization.json)
}
