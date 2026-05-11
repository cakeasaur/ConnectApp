package com.example.connectapp.data.models

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Application-wide command bus.
 * GraphActivity posts commands here; BluetoothViewModel picks them up and sends.
 */
object CommandBus {
    private val _commands = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val commands: SharedFlow<String> = _commands.asSharedFlow()

    fun send(command: String) {
        _commands.tryEmit(command)
    }
}
