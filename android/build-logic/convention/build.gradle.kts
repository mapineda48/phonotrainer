import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  `kotlin-dsl`
}

group = "com.mapineda48.phonotrainer.buildlogic"

// The plugins compile for Java 17, the oldest JDK a Gradle daemon of this build runs on.
java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
  compilerOptions {
    jvmTarget = JvmTarget.JVM_17
  }
}

dependencies {
  // compileOnly: the root build puts these plugins on the classpath once (android/build.gradle.kts), so the
  // convention plugins and the modules share one copy of each.
  compileOnly(libs.android.gradlePlugin)
  compileOnly(libs.kotlin.gradlePlugin)
  compileOnly(libs.room.gradlePlugin)
  compileOnly(libs.aboutlibraries.gradlePlugin)

  // The licence check's own tests (architecture 20.6): every planted fault of work-plan WP-19's proof fails it.
  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

tasks {
  test {
    useJUnit()
    maxHeapSize = "512m"
  }
  validatePlugins {
    enableStricterValidation = true
    failOnWarning = true
  }
}

// Architecture 2.3: the ten convention plugins. Module build files apply one or two of them and list their
// module dependencies; no build file outside build-logic configures Android, Kotlin or a version.
gradlePlugin {
  plugins {
    register("androidApplication") {
      id = "phonotrainer.android.application"
      implementationClass = "AndroidApplicationConventionPlugin"
    }
    register("androidLibrary") {
      id = "phonotrainer.android.library"
      implementationClass = "AndroidLibraryConventionPlugin"
    }
    register("androidLibraryInstrumented") {
      id = "phonotrainer.android.library.instrumented"
      implementationClass = "AndroidLibraryInstrumentedConventionPlugin"
    }
    register("androidTest") {
      id = "phonotrainer.android.test"
      implementationClass = "AndroidTestConventionPlugin"
    }
    register("androidCompose") {
      id = "phonotrainer.android.compose"
      implementationClass = "AndroidComposeConventionPlugin"
    }
    register("androidFeature") {
      id = "phonotrainer.android.feature"
      implementationClass = "AndroidFeatureConventionPlugin"
    }
    register("androidHilt") {
      id = "phonotrainer.android.hilt"
      implementationClass = "AndroidHiltConventionPlugin"
    }
    register("androidRoom") {
      id = "phonotrainer.android.room"
      implementationClass = "AndroidRoomConventionPlugin"
    }
    register("jvmLibrary") {
      id = "phonotrainer.jvm.library"
      implementationClass = "JvmLibraryConventionPlugin"
    }
    register("kotlinSerialization") {
      id = "phonotrainer.kotlin.serialization"
      implementationClass = "KotlinSerializationConventionPlugin"
    }
  }
}
