package com.example.connectapp

import android.app.Application
import com.example.connectapp.data.mqtt.MqttBridge
import com.example.connectapp.data.settings.SettingsRepository
import com.example.connectapp.utils.AlertEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application — однократная инициализация process-wide компонентов.
 * Сейчас единственный обитатель — [MqttBridge], которому нужно
 * подсунуть SettingsRepository и стартовать раньше любого VM.
 *
 * Без custom Application bridge пришлось бы инициализировать из
 * MainActivity, но тогда первый launch активити без сети дал бы
 * расхождение между UI-состоянием и реальным состоянием клиента.
 */
class ConnectApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val repo = SettingsRepository(applicationContext)
        // Первый запуск — генерируем уникальный topic-prefix, иначе несколько
        // пользователей на public-брокере получили бы одинаковый "connect/#"
        // и читали бы данные друг друга. Идемпотентно: на следующих запусках
        // существующий prefix не перетирается.
        appScope.launch { repo.ensureMqttDefaults() }
        MqttBridge.init(repo)
        AlertEngine.start(this)
    }
}
