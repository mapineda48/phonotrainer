// Puts every plugin the convention plugins apply on the build classpath once, at the versions of the catalog
// (architecture 2.3 and 2.4); modules apply the convention plugins of build-logic, never these directly.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.android.lint) apply false
  alias(libs.plugins.android.test) apply false
  alias(libs.plugins.baselineprofile) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.hilt) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.room) apply false
}
