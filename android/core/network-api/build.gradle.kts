// The frozen API of the computer client (architecture 2.2, work-plan 4.3), in its own JVM module so that
// :core:testing-jvm implements it without depending on :core:network (delivery review D-18).
plugins {
  id("phonotrainer.jvm.library")
}

dependencies {
  api(project(":core:domain"))
}
