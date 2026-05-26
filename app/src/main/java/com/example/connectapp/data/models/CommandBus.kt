package com.example.connectapp.data.models

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Application-wide command bus.
 *
 * GraphActivity (и MqttBridge) публикуют команды; ViewModel'и подписаны и
 * отправляют команду на свою плату, если соединение активно.
 *
 * Раньше шина передавала только строку команды. Когда одновременно были
 * подключены два транспорта (BT + USB через foreground service), команда
 * из GraphActivity уходила на ОБА устройства — каждый VM проверял только
 * собственный ConnectionState и не различал «адресовано мне или нет».
 *
 * Теперь у команды есть необязательный [Command.target] — ключ транспорта
 * (`"bt"` / `"usb"` / `"wifi"` / `"test"`), совпадающий с FG_OWNER в VM.
 * Если `target == null` — команда broadcast'ится (MQTT-сценарий, шлём на
 * все активные). Если задан — отвечает только тот VM, чей ключ совпадает.
 */
object CommandBus {

    /**
     * @property payload текст команды (с учётом line-ending добавляется в VM).
     * @property target ключ транспорта-получателя или null = всем активным.
     */
    data class Command(val payload: String, val target: String?)

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 8)
    val commands: SharedFlow<Command> = _commands.asSharedFlow()

    /**
     * Отправить команду конкретному транспорту. Используется GraphActivity,
     * которая знает источник из Intent extra.
     */
    fun send(payload: String, target: String) {
        _commands.tryEmit(Command(payload, target))
    }

    /**
     * Broadcast — команда уходит всем подписчикам, каждый VM сам решает по
     * собственному ConnectionState. Используется MqttBridge: команда из
     * MQTT не привязана к транспорту, должна попасть на любую активную плату.
     */
    fun broadcast(payload: String) {
        _commands.tryEmit(Command(payload, target = null))
    }
}
