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

# --- HiveMQ MQTT client + Netty (опциональные интеграции) ---
# Netty подтягивает опциональные классы (slf4j, Jetty NPN, BlockHound),
# которые мы не используем — R8 ругается "Missing class". Гасим warnings,
# реальный код в этих ветках мёртвый.
-dontwarn org.slf4j.**
-dontwarn org.eclipse.jetty.**
-dontwarn org.osgi.**
-dontwarn reactor.blockhound.**
# Netty: WebSocket / proxy / epoll-native code paths мы не используем
# (MQTT over plain TCP). HiveMQ ссылается на них опционально.
-dontwarn io.netty.handler.codec.http.**
-dontwarn io.netty.handler.proxy.**
-dontwarn io.netty.channel.epoll.**
-dontwarn io.netty.channel.kqueue.**
-dontwarn io.netty.channel.unix.**
-dontwarn com.oracle.svm.core.annotate.**
# androidx.security.crypto тянет Google Tink, который опционально
# ссылается на javax.annotation.* (JSR-305). На Android их нет — гасим.
-dontwarn javax.annotation.**
-dontwarn io.netty.internal.tcnative.**
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.google.protobuf.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.lz4.**
-dontwarn net.jpountz.xxhash.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.jboss.marshalling.**
-dontwarn sun.security.**
# Netty использует reflection для AOT-инициализации native libs.
-keep class io.netty.** { *; }
-keep class com.hivemq.client.** { *; }
