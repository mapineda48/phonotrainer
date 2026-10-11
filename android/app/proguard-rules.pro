# Application R8 rules (architecture 21.6). Libraries ship their own rules in consumer-rules.pro.

# StartupOrder (:core:domain, spi) is read by reflection from each StartupTask's class by StartupCheck (WP-08,
# architecture 4.6). The tasks are reached only through Hilt's generated code, and in R8's full mode an attribute
# survives only on classes a keep rule matches, so the order would vanish from release and qa builds alone. Keep the
# annotation on every annotated class that survives shrinking (the class may still be removed when unused, and
# renamed), and keep the annotation type with its value (decisions I-15; :app's StartupOrderR8Test).
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep,allowobfuscation,allowshrinking @com.mapineda48.phonotrainer.core.domain.spi.StartupOrder class *
-keep @interface com.mapineda48.phonotrainer.core.domain.spi.StartupOrder { *; }
