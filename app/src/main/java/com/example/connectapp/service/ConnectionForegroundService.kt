package com.example.connectapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.connectapp.R

/**
 * Foreground service для долгих BT/Wi-Fi сессий.
 *
 * Зачем: Android агрессивно усыпляет процессы приложений в фоне. При
 * сворачивании ConnectApp с активным BT-соединением — через ~2-3 минуты
 * процесс прибивается, socket закрывается, запись теряется.
 *
 * Решение — лёгкий foreground service, который только показывает
 * notification "Соединение активно". Сам сервис НИЧЕГО не делает с BT/Wi-Fi
 * (это всё в Repository/ViewModel) — он просто говорит Android'у "не убивай".
 * VM управляет жизненным циклом: [start] при Connected, [stop] при Idle/Error.
 *
 * Не Bound — Activity не привязывается. Service работает независимо.
 */
class ConnectionForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE)
            ?: getString(R.string.fg_default_title)
        ensureChannel()
        val notification = buildNotification(title)
        // ServiceCompat хелпер вместо прямого startForeground, потому что
        // на API 29-33 третий параметр (foregroundServiceType) — int, а на
        // 34+ обязателен FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            else 0
        )
        // START_NOT_STICKY: если система убивает сервис из-за нехватки памяти,
        // НЕ перезапускать автоматически. У нас нет своего реконнект-сценария
        // на уровне сервиса — это делает Repository в VM.
        return START_NOT_STICKY
    }

    private fun buildNotification(text: String): Notification {
        // PendingIntent → вернуть пользователя в MainActivity при тапе.
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)                                     // нельзя смахнуть
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)         // без звука/вибрации
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.fg_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.fg_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        mgr.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        // На API 24+ ServiceCompat.stopForeground(true) — снимает notification.
        // Без этого иногда зависает после stopService().
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "connection_fg"
        private const val NOTIFICATION_ID = 42
        private const val EXTRA_TITLE = "title"

        // Owner-set вместо чистого счётчика. Раньше двойной start() от одного
        // VM (например, при быстром reconnect) увеличивал refCount, а
        // соответствующий stop() уменьшал его только на 1 — счётчик оставался
        // > 0, сервис не умирал, висел "вечный" notification.
        // Теперь каждый VM передаёт уникальный owner-key; повторный start()
        // от того же владельца — no-op, повторный stop() — тоже.
        private val owners = HashSet<String>()
        private val ownersLock = Any()

        /**
         * Запустить сервис (или обновить notification, если уже работает).
         * [owner] — уникальный ключ источника (например, "bt", "wifi", "usb").
         * Повторный вызов с тем же owner'ом не увеличит внутренний счётчик.
         */
        fun start(context: Context, owner: String, title: String) {
            val firstOwner = synchronized(ownersLock) {
                val wasEmpty = owners.isEmpty()
                owners.add(owner)
                wasEmpty
            }
            val intent = Intent(context, ConnectionForegroundService::class.java)
                .putExtra(EXTRA_TITLE, title)
            runCatching {
                // На Android 12+ запуск fg-service из background может кинуть
                // ForegroundServiceStartNotAllowedException. У нас старт идёт
                // только из активной VM (живой пока Activity видна), так что ок.
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                // Откатим, если этот вызов добавил нового владельца.
                if (firstOwner) synchronized(ownersLock) { owners.remove(owner) }
            }
        }

        /** Снять владельца. Сервис реально умирает, когда последний снят. */
        fun stop(context: Context, owner: String) {
            val shouldStop = synchronized(ownersLock) {
                owners.remove(owner)
                owners.isEmpty()
            }
            if (shouldStop) {
                context.stopService(Intent(context, ConnectionForegroundService::class.java))
            }
        }
    }
}
