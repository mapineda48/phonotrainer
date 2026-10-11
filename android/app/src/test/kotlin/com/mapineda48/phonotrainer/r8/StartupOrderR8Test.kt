package com.mapineda48.phonotrainer.r8

import com.android.tools.r8.ClassFileConsumer
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.JdkClassFileProvider
import com.android.tools.r8.R8
import com.android.tools.r8.R8Command
import com.android.tools.r8.origin.Origin
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.spi.StartupOrder
import com.mapineda48.phonotrainer.core.domain.spi.StartupTask
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `@StartupOrder` survives R8 (architecture 4.6, work-plan 4.2; decision I-15). `StartupCheck` (WP-08) reads it by
 * reflection from each `StartupTask`'s class, and the tasks are reached only through Hilt's generated code. In R8's
 * full mode (AGP's default) an attribute such as `RuntimeVisibleAnnotations` stays only on classes a keep rule
 * matches, so without a rule the order would vanish from release and `qa` builds alone.
 *
 * The test runs the R8 that AGP builds the release with (it comes with AGP's `builder` artifact), in full mode, with
 * the R8 configuration files of the release build type (AGP's `proguard-android-optimize.txt` and the app's
 * `proguard-rules.pro`, passed in by the build), over the fixture of `StartupOrderFixture.kt` and the real
 * `StartupOrder` and `StartupTask` classes; it then loads the shrunk classes and asks the probe for the orders. The
 * second test is the control: without the app's own rules the same run loses them.
 */
class StartupOrderR8Test {
  @get:Rule
  val tmp = TemporaryFolder()

  private val releaseRules: List<Path> = System.getProperty(RULES_PROPERTY).orEmpty()
    .split(File.pathSeparator).filter { it.isNotBlank() }.map { File(it).toPath() }

  @Test
  fun theReleaseRulesKeepEveryTasksOrder() {
    assertThat(releaseRules.map { it.fileName.toString() }).contains("proguard-rules.pro")
    releaseRules.forEach { assertThat(it.toFile().isFile).isTrue() }

    assertThat(shrinkAndProbe(releaseRules)).isEqualTo("20,10")
  }

  @Test
  fun withoutTheAppsRulesFullModeDropsTheOrder() {
    val defaultsOnly = releaseRules.filterNot { it.fileName.toString() == "proguard-rules.pro" }
    assertThat(defaultsOnly).isNotEmpty()

    assertThat(shrinkAndProbe(defaultsOnly)).isEqualTo("null,null")
  }

  /** Shrinks the fixture with [rules] plus the one entry point, then runs the shrunk probe. */
  private fun shrinkAndProbe(rules: List<Path>): String {
    val out = tmp.newFolder().toPath().resolve("shrunk.jar")
    val command = R8Command.builder()
      .apply { PROGRAM.forEach { addClassProgramData(bytesOf(it), Origin.unknown()) } }
      .addLibraryResourceProvider(JdkClassFileProvider.fromSystemJdk())
      .addLibraryFiles(jarOf(Unit::class.java), jarOf(org.jetbrains.annotations.NotNull::class.java))
      .addProguardConfigurationFiles(rules)
      .addProguardConfiguration(listOf("-keep class ${StartupOrderProbe::class.java.name} { public static *** orders(); }"), Origin.unknown())
      .setProguardCompatibility(false)
      .setMode(CompilationMode.RELEASE)
      .setProgramConsumer(ClassFileConsumer.ArchiveConsumer(out))
      .build()
    R8.run(command)

    URLClassLoader(arrayOf(out.toUri().toURL()), WithoutTheApp(javaClass.classLoader!!)).use { shrunk ->
      val probe = shrunk.loadClass(StartupOrderProbe::class.java.name)
      check(probe.classLoader === shrunk) { "the probe must come from the shrunk program" }
      return probe.getMethod("orders").invoke(null) as String
    }
  }

  private fun bytesOf(type: Class<*>): ByteArray =
    checkNotNull(type.classLoader.getResourceAsStream(type.name.replace('.', '/') + ".class")) { type.name }.use { it.readBytes() }

  private fun jarOf(type: Class<*>): Path = File(type.protectionDomain.codeSource.location.toURI()).toPath()

  /** Hides the unshrunk app classes, so the shrunk program's own are loaded. */
  private class WithoutTheApp(parent: ClassLoader) : ClassLoader(parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
      if (name.startsWith("com.mapineda48.")) throw ClassNotFoundException(name)
      return super.loadClass(name, resolve)
    }
  }

  private companion object {
    const val RULES_PROPERTY = "phonotrainer.r8.releaseRules"
    val PROGRAM = listOf(
      StartupOrderProbe::class.java,
      LateFixtureTask::class.java,
      EarlyFixtureTask::class.java,
      StartupOrder::class.java,
      StartupTask::class.java,
    )
  }
}
