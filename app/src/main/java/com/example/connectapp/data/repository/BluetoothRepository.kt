package com.example.connectapp.data.repository

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.example.connectapp.data.models.BluetoothDeviceItem
import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.network.BluetoothClient
import com.example.connectapp.utils.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BluetoothRepository(
    private val appContext: Context,
    private val client: BluetoothClient = BluetoothClient()
) {

    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    private val _devices = MutableStateFlow<List<BluetoothDeviceItem>>(emptyList())
    val devices: StateFlow<List<BluetoothDeviceItem>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var receiver: BroadcastReceiver? = null
    private var readerJob: Job? = null
    private var discoveryTimeoutJob: Job? = null
    private val internalScope = MainScope()

    fun isAvailable(): Boolean = adapter != null
    fun isEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDeviceItem> = adapter
        ?.bondedDevices
        ?.map { it.toItem(bonded = true) }
        ?: emptyList()

    /** Publishes the current bonded device list without starting discovery. */
    fun refreshBonded() {
        _devices.value = bondedDevices()
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val a = adapter ?: return
        if (_scanning.value) return

        _devices.value = bondedDevices()
        registerReceiver()
        if (a.isDiscovering) a.cancelDiscovery()
        if (a.startDiscovery()) {
            _scanning.value = true
            // Auto-stop after timeout as a safety net.
            discoveryTimeoutJob?.cancel()
            discoveryTimeoutJob = internalScope.launch {
                delay(Constants.DISCOVERY_TIMEOUT_MS)
                stopDiscovery()
            }
        } else {
            unregisterReceiver()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        adapter?.takeIf { it.isDiscovering }?.cancelDiscovery()
        unregisterReceiver()
        _scanning.value = false
    }

    suspend fun connect(address: String, scope: CoroutineScope) {
        val a = adapter ?: run {
            _state.value = ConnectionState.Error("Bluetooth not available")
            return
        }
        if (_state.value is ConnectionState.Connected) return

        stopDiscovery()
        _state.value = ConnectionState.Connecting
        try {
            client.connect(a, address)
            _state.value = ConnectionState.Connected
            readerJob?.cancel()
            readerJob = scope.launch {
                try {
                    client.incoming().collect { _incoming.emit(it) }
                    if (_state.value is ConnectionState.Connected) {
                        _state.value = ConnectionState.Disconnected
                    }
                } catch (e: CancellationException) {
                    // Manual disconnect — propagate cancellation, no error state.
                    throw e
                } catch (t: Throwable) {
                    _state.value = ConnectionState.Error(t.message ?: "Read error")
                } finally {
                    // NonCancellable guarantees socket close even on cancellation.
                    withContext(NonCancellable) {
                        runCatching { client.close() }
                    }
                }
            }
        } catch (t: Throwable) {
            _state.value = ConnectionState.Error(t.message ?: "Connect failed")
            runCatching { client.close() }
        }
    }

    suspend fun send(payload: String) {
        runCatching { client.send(payload) }
    }

    suspend fun disconnect() {
        readerJob?.cancel()
        readerJob = null
        client.close()
        _state.value = ConnectionState.Idle
    }

    fun release() {
        stopDiscovery()
        internalScope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun registerReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE,
                                    BluetoothDevice::class.java
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }
                        device?.let { d ->
                            val item = d.toItem(bonded = false)
                            _devices.update { current ->
                                if (current.any { it.address == item.address }) current
                                else current + item
                            }
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        _scanning.value = false
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        appContext.registerReceiver(r, filter)
        receiver = r
    }

    private fun unregisterReceiver() {
        receiver?.let {
            runCatching { appContext.unregisterReceiver(it) }
        }
        receiver = null
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toItem(bonded: Boolean) = BluetoothDeviceItem(
        name = (name ?: "Unknown"),
        address = address,
        bonded = bonded
    )
}
