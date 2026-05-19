package com.example.connectapp.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.connectapp.data.models.SensorDataBus
import com.example.connectapp.data.models.TimedPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Запись о срабатывании порогового алерта. Накапливается в [AlertEngine.events].
 */
data class AlertEvent(
    val timestamp: Long,
    val channelKey: String,   // "t1" / "ax1" / ...
    val channelLabel: String, // "T1 °C" / "ax1 LSB"
    val value: Float,
    val threshold: Float,
)

/**
 * Singleton мониторинга пороговых значений сенсоров.
 *
 * Следит за каждым каналом SensorDataBus. Когда последнее значение
 * превышает настроенный порог — срабатывает уведомление + вибрация.
 *
 * Cooldown: 5 с на канал (нотификации) + 2 с глобально (вибрация),
 * чтобы не спамить при долгом превышении.
 *
 * Вызови [start] один раз из Application.onCreate().
 * Пороги меняй через [setThreshold] / [clearAll] в любой момент.
 */
object AlertEngine {

    const val NOTIF_CHANNEL_ID = "sensor_alerts"
    private const val NOTIF_CHANNEL_NAME = "Sensor Alerts"
    private const val COOLDOWN_MS = 5_000L
    private const val VIBRATE_COOLDOWN_MS = 2_000L
    private const val MAX_EVENTS = 500
    private const val NOTIF_ID_BASE = 2000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // StateFlow для thresholds — UI подписывается через collectAsStateWithLifecycle
    // и реагирует на изменение порогов без необходимости recompose по другому
    // триггеру (например когда поток данных приостановлен).
    private val _thresholds = MutableStateFlow<Map<String, Float>>(emptyMap())
    val thresholds: StateFlow<Map<String, Float>> = _thresholds.asStateFlow()

    private val lastNotifTime = ConcurrentHashMap<String, Long>()

    // Журнал аномалий — последние [MAX_EVENTS] срабатываний.
    // FIFO: новые в начале. Под synchronized чтобы не было гонок между
    // recordEvent (Dispatchers.Default из flow.collect) и clearEvents (Main).
    private val eventsLock = Any()
    private val eventsBuffer = ArrayDeque<AlertEvent>(MAX_EVENTS)
    private val _events = MutableStateFlow<List<AlertEvent>>(emptyList())
    val events: StateFlow<List<AlertEvent>> = _events.asStateFlow()
    private val lastVibrateTime = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var started = false

    // ---- Public API --------------------------------------------------------

    fun setThreshold(key: String, maxValue: Float?) {
        _thresholds.update { current ->
            if (maxValue == null) current - key
            else current + (key to maxValue)
        }
    }

    fun getThreshold(key: String): Float? = _thresholds.value[key]

    fun clearAll() { _thresholds.value = emptyMap() }

    fun clearEvents() {
        lastNotifTime.clear()
        lastVibrateTime.set(0L)
        synchronized(eventsLock) {
            eventsBuffer.clear()
            _events.value = emptyList()
        }
    }

    /**
     * Запускает фоновые корутины мониторинга. Идемпотентно: повторный
     * вызов игнорируется. Нужен Context для нотификаций и вибрации.
     */
    fun start(context: Context) {
        if (started) return
        started = true
        val appCtx = context.applicationContext
        ensureChannel(appCtx)

        fun watch(key: String, label: String, flow: StateFlow<List<TimedPoint>>) {
            scope.launch {
                flow.collect { points ->
                    val v = points.lastOrNull()?.value ?: return@collect
                    check(appCtx, key, label, v)
                }
            }
        }

        watch("t1",  "T1 °C",   SensorDataBus.temp1)
        watch("t2",  "T2 °C",   SensorDataBus.temp2)
        watch("ax1", "ax1 LSB", SensorDataBus.accel1X)
        watch("ay1", "ay1 LSB", SensorDataBus.accel1Y)
        watch("az1", "az1 LSB", SensorDataBus.accel1Z)
        watch("ax2", "ax2 LSB", SensorDataBus.accel2X)
        watch("ay2", "ay2 LSB", SensorDataBus.accel2Y)
        watch("az2", "az2 LSB", SensorDataBus.accel2Z)
    }

    // ---- Internal ----------------------------------------------------------

    private fun check(context: Context, key: String, label: String, value: Float) {
        val max = _thresholds.value[key] ?: return
        if (value <= max) return
        val now = System.currentTimeMillis()
        if (now - (lastNotifTime[key] ?: 0L) < COOLDOWN_MS) return
        lastNotifTime[key] = now
        fire(context, key, label, value, max)
    }

    private fun fire(
        context: Context,
        key: String,
        label: String,
        value: Float,
        threshold: Float,
    ) {
        // Запись в журнал — ДО нотификации, чтобы UI журнала отражал событие,
        // даже если пользователь не дал POST_NOTIFICATIONS на API 33+.
        recordEvent(key, label, value, threshold)
        vibrate(context)
        // POST_NOTIFICATIONS runtime permission обязательна на API 33+.
        // Без неё notify() бросает SecurityException.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠ $label превышен порог")
            .setContentText("%.2f > %.2f (порог)".format(value, threshold))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID_BASE + key.hashCode(), notif)
    }

    private fun vibrate(context: Context) {
        val now = System.currentTimeMillis()
        val last = lastVibrateTime.get()
        if (now - last < VIBRATE_COOLDOWN_MS) return
        if (!lastVibrateTime.compareAndSet(last, now)) return
        // Двойной импульс: 200мс + пауза 100мс + 300мс — узнаваемо отличается
        // от обычного уведомления однократного жужжания.
        val pattern = longArrayOf(0, 200, 100, 300)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NOTIF_CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            NOTIF_CHANNEL_ID,
            NOTIF_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Срабатывает когда значение сенсора превышает настроенный порог."
            enableVibration(false) // вибрируем сами через Vibrator
        }
        nm.createNotificationChannel(ch)
    }

    private fun recordEvent(key: String, label: String, value: Float, threshold: Float) {
        val event = AlertEvent(
            timestamp = System.currentTimeMillis(),
            channelKey = key,
            channelLabel = label,
            value = value,
            threshold = threshold,
        )
        // ArrayDeque: addFirst O(1), removeLast O(1).
        // Раньше было `(listOf(e) + current).take(N)` — O(N) копия на каждое
        // срабатывание = ~1000 аллокаций на 500 событий.
        synchronized(eventsLock) {
            eventsBuffer.addFirst(event)
            while (eventsBuffer.size > MAX_EVENTS) eventsBuffer.removeLast()
            _events.value = eventsBuffer.toList()   // immutable snapshot для StateFlow
        }
    }
}
