package com.example.connectapp.utils

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Лёгкий ЛОКАЛЬНЫЙ crash-репортинг для приватной (APK) дистрибуции — без
 * Firebase/Sentry/аккаунтов и сети.
 *
 * Ставит [Thread.setDefaultUncaughtExceptionHandler], который дописывает
 * stacktrace + контекст (версия, устройство, поток) в файл `crash_log.txt`
 * в filesDir, затем передаёт управление ПРЕДЫДУЩЕМу хендлеру — приложение
 * падает штатно (системный диалог показывается). Файл переживает рестарт;
 * MainActivity показывает баннер «отправить отчёт» (share через FileProvider).
 *
 * Если позже понадобится облачный сбор — заменить тело [append] на вызов
 * Crashlytics/Sentry (им нужен проект/DSN, которого у приватной сборки нет).
 */
object CrashReporter {

    private const val FILE_NAME = "crash_log.txt"
    private const val MAX_BYTES = 64 * 1024   // держим хвост, не растём бесконечно

    /** Вызывать ПЕРВЫМ в Application.onCreate, чтобы ловить и ранние падения. */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { append(appContext, thread, throwable) }
            // Цепочка к системному хендлеру — приложение падает как обычно.
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)
    fun hasReport(context: Context): Boolean = file(context).let { it.exists() && it.length() > 0 }
    fun read(context: Context): String? =
        file(context).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
    fun clear(context: Context) { runCatching { file(context).delete() } }

    private fun append(context: Context, thread: Thread, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        val ver = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val header = buildString {
            append("=== CRASH $ts ===\n")
            append("app: ${context.packageName} v$ver\n")
            append("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("thread: ${thread.name}\n")
        }
        val f = file(context)
        val existing = if (f.exists()) runCatching { f.readText() }.getOrDefault("") else ""
        val combined = (existing + header + sw + "\n").let {
            if (it.length > MAX_BYTES) it.substring(it.length - MAX_BYTES) else it
        }
        runCatching { f.writeText(combined) }
    }
}
