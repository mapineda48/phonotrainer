# R8 rules that apps using this library need (the phonotrainer.android.library convention plugin wires this file).
#
# Retrofit and kotlinx.serialization ship their own rules (the service interface, its generic signatures and the
# generated serializers). These two make the client's own needs explicit (architecture 5.4):
# the Retrofit service whose method signatures name the DTOs the converter decodes,
-keep,allowobfuscation interface com.mapineda48.phonotrainer.core.network.service.ComputerApi
# and the generated serializer of every DTO, which the converter looks up from the type.
-keepclassmembers @kotlinx.serialization.Serializable class com.mapineda48.phonotrainer.core.network.dto.** {
    static ** Companion;
}
-keepclassmembers class com.mapineda48.phonotrainer.core.network.dto.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
