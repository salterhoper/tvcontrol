# Ktor / kotlinx.serialization keep working through R8 thanks to the shipped
# consumer rules, but the reflective bits of kotlinx.serialization need this.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.codesky.tvcontrol.** {
    *** Companion;
}
-keepclasseswithmembers class com.codesky.tvcontrol.** {
    kotlinx.serialization.KSerializer serializer(...);
}
