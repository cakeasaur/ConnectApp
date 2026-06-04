package com.example.connectapp.data.settings

import org.json.JSONArray
import org.json.JSONObject

/**
 * Профиль платы — именованный набор настроек под конкретное устройство.
 *
 * Универсальность под разные платы: вместо ручной перенастройки при смене
 * устройства пользователь сохраняет настройку как профиль и переключается
 * выбором. Профиль хранит то, что обычно различается между платами:
 * терминатор команд, частоту опроса, чувствительность акселерометра и набор
 * быстрых команд.
 */
data class BoardProfile(
    val name: String,
    val lineEnding: LineEnding,
    val sampleRateHz: Float,
    val accelSensitivityLsbPerG: Float,
    val quickCommands: List<QuickCommand>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("n", name)
        put("le", lineEnding.name)
        put("sr", sampleRateHz.toDouble())
        put("sens", accelSensitivityLsbPerG.toDouble())
        put("qc", JSONArray().apply { quickCommands.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject) = BoardProfile(
            name = o.optString("n"),
            lineEnding = runCatching { LineEnding.valueOf(o.optString("le")) }
                .getOrDefault(LineEnding.CRLF),
            sampleRateHz = o.optDouble("sr", 10.0).toFloat(),
            accelSensitivityLsbPerG = o.optDouble("sens", 1000.0).toFloat(),
            quickCommands = o.optJSONArray("qc")?.let { arr ->
                List(arr.length()) { QuickCommand.fromJson(arr.getJSONObject(it)) }
                    .filter { it.label.isNotBlank() }
            } ?: emptyList(),
        )

        fun encodeList(list: List<BoardProfile>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun decodeList(s: String?): List<BoardProfile> {
            if (s.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(s)
                List(arr.length()) { fromJson(arr.getJSONObject(it)) }
                    .filter { it.name.isNotBlank() }
            }.getOrDefault(emptyList())
        }
    }
}
