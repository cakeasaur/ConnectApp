package com.example.connectapp.data.models

import com.example.connectapp.utils.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Application-wide singleton that holds sensor time-series data for the charts.
 * BluetoothViewModel writes here; GraphActivity reads from here.
 */
object SensorDataBus {

    private val _tempValues = MutableStateFlow<List<Float>>(emptyList())
    val tempValues: StateFlow<List<Float>> = _tempValues.asStateFlow()

    private val _accelX = MutableStateFlow<List<Float>>(emptyList())
    val accelX: StateFlow<List<Float>> = _accelX.asStateFlow()

    private val _accelY = MutableStateFlow<List<Float>>(emptyList())
    val accelY: StateFlow<List<Float>> = _accelY.asStateFlow()

    private val _accelZ = MutableStateFlow<List<Float>>(emptyList())
    val accelZ: StateFlow<List<Float>> = _accelZ.asStateFlow()

    fun addTemperature(value: Float) {
        _tempValues.update { (it + value).takeLast(Constants.GRAPH_MAX_POINTS) }
    }

    fun addAccel(x: Float, y: Float, z: Float) {
        _accelX.update { (it + x).takeLast(Constants.GRAPH_MAX_POINTS) }
        _accelY.update { (it + y).takeLast(Constants.GRAPH_MAX_POINTS) }
        _accelZ.update { (it + z).takeLast(Constants.GRAPH_MAX_POINTS) }
    }

    fun clear() {
        _tempValues.value = emptyList()
        _accelX.value = emptyList()
        _accelY.value = emptyList()
        _accelZ.value = emptyList()
    }
}
