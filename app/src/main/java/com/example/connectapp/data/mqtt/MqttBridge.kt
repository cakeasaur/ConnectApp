package com.example.connectapp.data.mqtt

import com.example.connectapp.data.models.CommandBus
import com.example.connectapp.data.models.SensorDataBus
import com.example.connectapp.data.settings.SettingsRepository
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * MQTT-мост. Подключается к брокеру, публикует сенсоры из [SensorDataBus],
 * подписывается на `{prefix}/cmd` и форвардит команды через [CommandBus] на
 * активный транспорт (BT/Wi-Fi/USB).
 *
 * Singleton — один MQTT-клиент на приложение, переживает смену Activity.
 * Конфиг тянется из [SettingsRepository.mqttConfig]; на каждое изменение
 * relevant-полей делаем reconnect.
 *
 * Топики:
 *   {prefix}/status   — retained JSON {"state":"online"/"offline","ts":...}
 *   {prefix}/temp1    — JSON {"t":<ms>,"v":<float>}
 *   {prefix}/temp2    — JSON {"t":<ms>,"v":<float>}
 *   {prefix}/accel1   — JSON {"t":<ms>,"x":<f>,"y":<f>,"z":<f>}
 *   {prefix}/accel2   — JSON {"t":<ms>,"x":<f>,"y":<f>,"z":<f>}
 *   {prefix}/cmd      — IN, body = команда (raw text) → CommandBus
 */
object MqttBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<MqttState>(MqttState.Disabled)
    val state: StateFlow<MqttState> = _state.asStateFlow()

    private val clientRef = AtomicReference<Mqtt5AsyncClient?>(null)
    private val cfgRef = AtomicReference<MqttConfig?>(null)

    // Throttle: last publish ms per topic-suffix. Защищён mutexом — readers
    // и writers могут быть на разных потоках корутинного пула.
    private val throttleMutex = Mutex()
    private val lastPubMs = mutableMapOf<String, Long>()

    private var watchJob: Job? = null
    private val publishJobs = mutableListOf<Job>()
    private val initLock = Any()
    @Volatile private var inited = false

    /**
     * Однократная инициализация: запускает наблюдение за [SettingsRepository.mqttConfig]
     * и подписчиков на потоки сенсоров. Вызывать из Application.onCreate().
     */
    fun init(settings: SettingsRepository) {
        synchronized(initLock) {
            if (inited) return
            inited = true
        }
        watchJob = scope.launch {
            settings.mqttConfig.collect { cfg -> handleConfigChange(cfg) }
        }
        startSensorPublishers()
    }

    // ============================================================
    // Configuration handling
    // ============================================================

    private suspend fun handleConfigChange(newCfg: MqttConfig) {
        val prev = cfgRef.getAndSet(newCfg)

        if (!newCfg.enabled) {
            disconnect()
            _state.value = MqttState.Disabled
            return
        }

        // Реконнект нужен только при изменении connection-relevant полей.
        // Throttle/QoS/retained меняются «горячо» без переподключения.
        val needsReconnect = prev == null ||
            prev.host != newCfg.host || prev.port != newCfg.port ||
            prev.useTls != newCfg.useTls || prev.trustAllCerts != newCfg.trustAllCerts ||
            prev.username != newCfg.username || prev.password != newCfg.password ||
            prev.clientId != newCfg.clientId ||
            prev.topicPrefix != newCfg.topicPrefix ||
            prev.subscribeCommands != newCfg.subscribeCommands ||
            (!prev.enabled && newCfg.enabled)

        if (needsReconnect) {
            disconnect()
            connect(newCfg)
        }
    }

    private suspend fun connect(c: MqttConfig) {
        if (c.host.isBlank()) {
            _state.value = MqttState.Error("host пустой")
            return
        }
        _state.value = MqttState.Connecting
        try {
            val cid = c.clientId.ifBlank {
                "connectapp-${UUID.randomUUID().toString().take(8)}"
            }
            val builder = Mqtt5Client.builder()
                .identifier(cid)
                .serverHost(c.host)
                .serverPort(c.port)
                .automaticReconnect()
                    .initialDelay(1, TimeUnit.SECONDS)
                    .maxDelay(30, TimeUnit.SECONDS)
                    .applyAutomaticReconnect()
                // Will: брокер шлёт offline-статус если мы внезапно отвалились.
                // Подписчики на {prefix}/status сразу видят disconnect.
                .willPublish()
                    .topic("${c.topicPrefix}/${MqttConfig.STATUS_SUFFIX}")
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .retain(true)
                    .payload("""{"state":"offline"}""".toByteArray())
                    .applyWillPublish()

            if (c.useTls) {
                if (c.trustAllCerts) {
                    // Self-signed CA: используем Netty's InsecureTrustManagerFactory,
                    // он принимает ЛЮБОЙ серверный сертификат. Только для доверенных
                    // брокеров в локальной сети — иначе MITM становится тривиальным.
                    builder.sslConfig()
                        .trustManagerFactory(InsecureTrustManagerFactory.INSTANCE)
                        .applySslConfig()
                } else {
                    builder.sslWithDefaultConfig()
                }
            }

            val cli = builder.buildAsync()

            // connectWith с условным simpleAuth — пробрасываем username/password
            // только если хотя бы username непуст. Иначе anonymous.
            val connectChain = cli.connectWith()
            if (c.username.isNotBlank()) {
                connectChain.simpleAuth()
                    .username(c.username)
                    .password(c.password.toByteArray())
                    .applySimpleAuth()
            }
            connectChain.send().await()

            clientRef.set(cli)
            _state.value = MqttState.Connected

            // Online-status сразу после connect, retained.
            publishRaw(
                "${c.topicPrefix}/${MqttConfig.STATUS_SUFFIX}",
                """{"state":"online","ts":${System.currentTimeMillis()}}""",
                retained = true
            )

            if (c.subscribeCommands) {
                cli.subscribeWith()
                    .topicFilter("${c.topicPrefix}/${MqttConfig.CMD_SUFFIX}")
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .callback { pub ->
                        val cmd = String(pub.payloadAsBytes).trim()
                        // broadcast — команда из MQTT не привязана к транспорту,
                        // должна попасть на любую активную плату (BT/USB/Wi-Fi).
                        if (cmd.isNotEmpty()) CommandBus.broadcast(cmd)
                    }
                    .send().await()
            }
        } catch (t: Throwable) {
            clientRef.set(null)
            _state.value = MqttState.Error(t.message ?: "connect failed")
        }
    }

    private suspend fun disconnect() {
        val cli = clientRef.getAndSet(null) ?: return
        runCatching { cli.disconnect().await() }
        if (_state.value !is MqttState.Disabled) _state.value = MqttState.Idle
    }

    /** Ручной reconnect (например, кнопка в UI). */
    fun reconnectNow() {
        scope.launch {
            val c = cfgRef.get() ?: return@launch
            disconnect()
            if (c.enabled) connect(c)
        }
    }

    // ============================================================
    // Sensor publishing
    // ============================================================

    private fun startSensorPublishers() {
        // drop(1) — пропускаем initial emptyList, чтобы не публиковать null'ы.
        publishJobs += scope.launch {
            SensorDataBus.temp1.drop(1).collect { list ->
                list.lastOrNull()?.let { p ->
                    publishSensor(MqttConfig.TEMP1_SUFFIX, """{"t":${p.t},"v":${p.value}}""")
                }
            }
        }
        publishJobs += scope.launch {
            SensorDataBus.temp2.drop(1).collect { list ->
                list.lastOrNull()?.let { p ->
                    publishSensor(MqttConfig.TEMP2_SUFFIX, """{"t":${p.t},"v":${p.value}}""")
                }
            }
        }
        // Для акселерометров берём x как trigger, y/z подтягиваем по StateFlow.value
        // — они обновляются в том же addAccel() call'е до триггера x.
        publishJobs += scope.launch {
            SensorDataBus.accel1X.drop(1).collect { listX ->
                val px = listX.lastOrNull() ?: return@collect
                val y = SensorDataBus.accel1Y.value.lastOrNull()?.value ?: 0f
                val z = SensorDataBus.accel1Z.value.lastOrNull()?.value ?: 0f
                publishSensor(
                    MqttConfig.ACCEL1_SUFFIX,
                    """{"t":${px.t},"x":${px.value},"y":$y,"z":$z}"""
                )
            }
        }
        publishJobs += scope.launch {
            SensorDataBus.accel2X.drop(1).collect { listX ->
                val px = listX.lastOrNull() ?: return@collect
                val y = SensorDataBus.accel2Y.value.lastOrNull()?.value ?: 0f
                val z = SensorDataBus.accel2Z.value.lastOrNull()?.value ?: 0f
                publishSensor(
                    MqttConfig.ACCEL2_SUFFIX,
                    """{"t":${px.t},"x":${px.value},"y":$y,"z":$z}"""
                )
            }
        }
    }

    private suspend fun publishSensor(suffix: String, json: String) {
        val c = cfgRef.get() ?: return
        if (!c.enabled || _state.value !is MqttState.Connected) return
        if (shouldThrottle(suffix, c.throttleMs)) return
        publishRaw("${c.topicPrefix}/$suffix", json, retained = c.publishRetained)
    }

    private suspend fun shouldThrottle(suffix: String, throttleMs: Int): Boolean {
        if (throttleMs <= 0) return false
        val now = System.currentTimeMillis()
        return throttleMutex.withLock {
            val last = lastPubMs[suffix] ?: 0L
            if (now - last < throttleMs) {
                true
            } else {
                lastPubMs[suffix] = now
                false
            }
        }
    }

    private fun publishRaw(topic: String, payload: String, retained: Boolean) {
        val cli = clientRef.get() ?: return
        val c = cfgRef.get() ?: return
        val qos = qosOf(c.publishQos)
        // exceptionally — глотаем ошибки publish'а, иначе один сбой повалит
        // CompletableFuture-цепочку. Auto-reconnect клиента сам разрулит обрыв.
        cli.publishWith()
            .topic(topic)
            .qos(qos)
            .payload(payload.toByteArray())
            .retain(retained)
            .send()
            .exceptionally { null }
    }

    private fun qosOf(level: Int): MqttQos = when (level) {
        1 -> MqttQos.AT_LEAST_ONCE
        2 -> MqttQos.EXACTLY_ONCE
        else -> MqttQos.AT_MOST_ONCE
    }
}
