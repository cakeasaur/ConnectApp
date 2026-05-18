package com.example.connectapp.data.models

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application-wide статус-шина соединений. Каждый transport-VM (BT/Wi-Fi/USB)
 * на изменении [ConnectionState] обновляет свой slot, MainActivity подписан
 * и показывает persistent-banner ("BT: Connected • XX:XX:XX:XX") даже если
 * юзер сейчас на главном экране.
 *
 * Раньше статус был доступен только внутри активного транспорт-Activity:
 * например, подключился по BT → перешёл на главный экран → не видно
 * подключён ли он ещё. Теперь видно всегда.
 *
 * Несколько одновременных Connected (BT и USB вместе) — берём первый
 * по приоритету в UI; шина хранит всё.
 */
object GlobalConnectionStatus {

    data class Snapshot(
        val state: ConnectionState,
        /** Label для отображения: «AA:BB:CC:DD:EE:FF» или «192.168.1.5:8080». */
        val label: String,
    )

    private val byTransport = mutableMapOf<String, Snapshot>()
    private val lock = Any()

    private val _flow = MutableStateFlow<Map<String, Snapshot>>(emptyMap())
    val flow: StateFlow<Map<String, Snapshot>> = _flow.asStateFlow()

    /** Записать состояние транспорта. snapshot=null — снять (VM умер). */
    fun set(transport: String, snapshot: Snapshot?) {
        synchronized(lock) {
            if (snapshot == null) byTransport.remove(transport)
            else byTransport[transport] = snapshot
            _flow.value = byTransport.toMap()
        }
    }

    /**
     * Самый «релевантный» снимок: приоритет Connected → Reconnecting →
     * Connecting → Error → Idle. Для UI-banner'а.
     */
    fun mostRelevant(map: Map<String, Snapshot>): Pair<String, Snapshot>? {
        if (map.isEmpty()) return null
        val priority: (ConnectionState) -> Int = { s ->
            when (s) {
                is ConnectionState.Connected -> 5
                is ConnectionState.Reconnecting -> 4
                is ConnectionState.Connecting -> 3
                is ConnectionState.Error -> 2
                is ConnectionState.Disconnected -> 1
                else -> 0
            }
        }
        return map.entries.maxByOrNull { priority(it.value.state) }?.toPair()
    }
}
