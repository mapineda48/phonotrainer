# StartupCheck (architecture 4.6) orders the StartupTask set by the @StartupOrder annotation on each task's class,
# read by reflection. The tasks are reached only through Hilt's generated code, so R8 full mode would drop the
# annotation from their classes (wave-1 review finding 8): keep the annotation type and the attribute on every class
# that carries it (names may still be obfuscated, unused classes still removed).
-keepattributes RuntimeVisibleAnnotations
-keep @interface com.mapineda48.phonotrainer.core.domain.spi.StartupOrder
-keep,allowobfuscation,allowshrinking @com.mapineda48.phonotrainer.core.domain.spi.StartupOrder class *
