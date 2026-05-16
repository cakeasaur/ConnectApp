package com.example.connectapp.data.settings

import org.json.JSONArray
import org.json.JSONObject

/**
 * Кастомная быстрая команда — пользовательский чип над полем ввода в BT-экране.
 *
 * Раньше набор был захардкожен под PIC24FJ128GB106 в strings.xml (cmd_one,
 * cmd_help и т.д.). Теперь пользователь сам редактирует список под свою
 * прошивку: добавляет/удаляет/переименовывает, помечает HEX-режим
 * персонально на команду (а не глобально через настройку HEX-mode).
 *
 * @param label что показывать на чипе ("monitor", "RESET")
 * @param payload что отправлять на устройство ("1", "AA 55 FF" в HEX)
 * @param hex если true — payload парсится как HEX-байты и шлётся бинарно
 *   без терминатора. Если false — text + терминатор из настроек.
 */
data class QuickCommand(
    val label: String,
    val payload: String,
    val hex: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("l", label); put("p", payload); put("h", hex)
    }

    companion object {
        fun fromJson(o: JSONObject) = QuickCommand(
            label = o.optString("l"),
            payload = o.optString("p"),
            hex = o.optBoolean("h", false)
        )

        /**
         * Дефолтный набор под прошивку PIC24FJ128GB106 Sensor Monitor.
         * Используется при первом запуске и при сбросе через кнопку "По умолчанию".
         */
        val DEFAULT: List<QuickCommand> = listOf(
            QuickCommand("monitor", "1"),
            QuickCommand("calib", "2"),
            QuickCommand("quick test", "3"),
            QuickCommand("help", "help"),
            QuickCommand("echo", "echo"),
            QuickCommand("clear", "clear"),
            QuickCommand("freq", "freq"),
            QuickCommand("test", "test")
        )

        fun encodeList(list: List<QuickCommand>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun decodeList(s: String?): List<QuickCommand> {
            if (s.isNullOrBlank()) return DEFAULT
            return runCatching {
                val arr = JSONArray(s)
                List(arr.length()) { fromJson(arr.getJSONObject(it)) }
                    .filter { it.label.isNotBlank() && it.payload.isNotBlank() }
            }.getOrDefault(DEFAULT)
        }
    }
}
