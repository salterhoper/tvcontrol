# kotlinx.serialization: keep generated serializers for our model classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.codesky.tvcontrol.remote.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.codesky.tvcontrol.remote.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.codesky.tvcontrol.remote.data.**$$serializer { *; }

# Ktor / its optional engines emit reflective hints; silence warnings.
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn kotlinx.coroutines.**
