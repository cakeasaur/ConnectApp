# Сохраняем атрибуты, нужные для рефлексии Kotlin/JVM и читаемых стек-трейсов.
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Kotlin coroutines: погасить некритичные предупреждения ---
-dontwarn kotlinx.coroutines.flow.**
-dontwarn kotlinx.coroutines.debug.**

# --- Vico (Compose chart) — обычно не нуждается в keep, но на всякий случай ---
-dontwarn com.patrykandpatrick.vico.**

# --- AndroidX FileProvider (используется в GraphActivity для CSV) ---
-keep class androidx.core.content.FileProvider { *; }
