package com.example.connectapp.data.mqtt

/**
 * Конфиг MQTT-моста. Хранится в DataStore через SettingsRepository.
 *
 * Дефолты подобраны под локальный Mosquitto / HA Mosquitto add-on:
 * 1883 без TLS, anonymous. Под HiveMQ Cloud или EMQX Cloud нужно
 * выставить useTls=true, порт 8883, и логин/пароль.
 */
data class MqttConfig(
    /** Включён ли мост. Если false — клиент даже не пытается соединяться. */
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 1883,
    /** Использовать MQTTS (TLS) — порт обычно 8883. */
    val useTls: Boolean = false,
    /**
     * Доверять любому серверному сертификату (для self-signed CA на своём
     * Mosquitto). Под капотом — Netty's [InsecureTrustManagerFactory], принимает
     * всё. ВКЛЮЧАТЬ ТОЛЬКО для брокеров в локальной сети, которым доверяешь —
     * иначе MITM становится тривиальным.
     */
    val trustAllCerts: Boolean = false,
    val username: String = "",
    val password: String = "",
    /**
     * Client-id для брокера. Если пусто — генерируется автоматически из
     * device-id при первом подключении. Уникальность критична, иначе
     * брокер кикает старую сессию каждый раз.
     */
    val clientId: String = "",
    /** Префикс топиков: "{prefix}/temp1", "{prefix}/accel1", "{prefix}/cmd" и т.д. */
    val topicPrefix: String = "connect",
    /** QoS для публикаций сенсоров. 0 — fire-and-forget (оптимально для high-rate). */
    val publishQos: Int = 0,
    /** Retained = брокер хранит последнее значение, новые подписчики видят сразу. */
    val publishRetained: Boolean = false,
    /**
     * Минимальный интервал между публикациями одного канала, мс. 10 Hz × 8 каналов
     * = 80 msg/sec — некоторые брокеры (особенно free cloud-tier) могут начать рейт-лимитить.
     * 100 мс = 10 Hz на канал — компромисс между плотностью и нагрузкой.
     */
    val throttleMs: Int = 100,
    /**
     * Подписаться на `{prefix}/cmd` и форвардить тело на активный транспорт.
     * Default = false из соображений безопасности: на public-брокере любой,
     * кто опубликует в этот топик, удалённо управляет твоей платой.
     * Включай только если ACL/auth настроены или брокер изолирован.
     */
    val subscribeCommands: Boolean = false,
) {
    companion object {
        val DEFAULT = MqttConfig()
        const val CMD_SUFFIX = "cmd"
        const val STATUS_SUFFIX = "status"
        const val TEMP1_SUFFIX = "temp1"
        const val TEMP2_SUFFIX = "temp2"
        const val ACCEL1_SUFFIX = "accel1"
        const val ACCEL2_SUFFIX = "accel2"
    }
}

/** Состояние MQTT-клиента для UI. */
sealed class MqttState {
    object Disabled : MqttState()
    object Idle : MqttState()
    object Connecting : MqttState()
    object Connected : MqttState()
    data class Error(val message: String) : MqttState()
}
