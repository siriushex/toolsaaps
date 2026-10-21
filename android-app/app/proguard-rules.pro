# Prune unused Material icons while preserving every other class and member.
# Keep names, attributes and behavior for reflection, persistence and clinical code.
-dontoptimize
-dontobfuscate
-keepattributes *
-keep class !androidx.compose.material.icons.**,** { *; }
# Error Prone's compile-time annotation references a JDK compiler enum absent on Android.
-dontwarn javax.lang.model.element.Modifier
