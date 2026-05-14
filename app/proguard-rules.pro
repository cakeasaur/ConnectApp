# Сохраняем атрибуты, нужные для рефлексии Kotlin/JVM и читаемых стек-трейсов.
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Kotlin coroutines: погасить некритичные предупреждения ---
-dontwarn kotlinx.coroutines.flow.**
-dontwarn kotlinx.coroutines.debug.**

# --- MPAndroidChart использует рефлексию для Animator/Easing/IDataSet ---
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# --- AndroidX FileProvider (используется в GraphActivity для CSV) ---
-keep class androidx.core.content.FileProvider { *; }
