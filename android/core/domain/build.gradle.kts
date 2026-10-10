// The contract every work package codes against (architecture 2.2, 3.4): domain types, interfaces and core-to-core
// SPIs. Pure JVM; frozen at B1 and changed only through the integrator.
plugins {
  id("phonotrainer.jvm.library")
}

dependencies {
  api(libs.kotlinx.coroutines.core)
  // Qualifiers and @Inject constructors without an Android dependency.
  api(libs.javax.inject)
}
