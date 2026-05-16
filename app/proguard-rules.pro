# Сохраняем атрибуты, нужные для рефлексии Kotlin/JVM и читаемых стек-трейсов.
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Kotlin coroutines: погасить некритичные предупреждения ---
-dontwarn kotlinx.coroutines.flow.**
-dontwarn kotlinx.coroutines.debug.**

# --- Vico (Compose chart) — использует рефлексию для Marker/AxisItemPlacer/
#     AxisValuesOverrider. Без keep R8 минификация выпиливает нужные члены
#     и графики в release-сборке падают/пустые.
-keep class com.patrykandpatrick.vico.** { *; }
-keepclassmembers class com.patrykandpatrick.vico.** { *; }
-dontwarn com.patrykandpatrick.vico.**

# FileProvider keep НЕ нужен: класс ссылается из манифеста, R8 сам сохраняет
# manifest-referenced классы.
