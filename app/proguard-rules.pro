# Сохраняем атрибуты, нужные для рефлексии Kotlin/JVM и читаемых стек-трейсов.
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Kotlin coroutines: погасить некритичные предупреждения ---
-dontwarn kotlinx.coroutines.flow.**
-dontwarn kotlinx.coroutines.debug.**

# Vico keep удалён вместе с самой зависимостью — все графики теперь
# на кастомном NeonChart через Canvas, рефлексии не используется.

# FileProvider keep НЕ нужен: класс ссылается из манифеста, R8 сам сохраняет
# manifest-referenced классы.
