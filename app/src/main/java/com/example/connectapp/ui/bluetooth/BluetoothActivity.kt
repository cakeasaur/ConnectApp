package com.example.connectapp.ui.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.data.models.SensorData
import com.example.connectapp.ui.graph.GraphActivity
import com.example.connectapp.databinding.ActivityBluetoothBinding
import kotlinx.coroutines.flow.debounce
import com.example.connectapp.utils.PermissionHelper
import kotlinx.coroutines.launch

class BluetoothActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBluetoothBinding
    private val viewModel: BluetoothViewModel by viewModels()
    private lateinit var adapter: BluetoothDeviceAdapter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // Пустая карта (system race) трактуется как отказ, а не успех.
        val granted = result.isNotEmpty() && result.values.all { it }
        if (granted) {
            ensureBluetoothEnabled()
        } else {
            Toast.makeText(this, "Нужны разрешения для работы Bluetooth", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (viewModel.isEnabled()) {
            viewModel.loadBonded()
        } else {
            Toast.makeText(this, "Bluetooth выключен", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBluetoothBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!viewModel.isAvailable()) {
            Toast.makeText(this, "Bluetooth недоступен на этом устройстве", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.tvLog.movementMethod = ScrollingMovementMethod()

        adapter = BluetoothDeviceAdapter { device -> viewModel.connect(device.address) }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = adapter

        binding.btnScan.setOnClickListener {
            if (!PermissionHelper.hasAll(this, PermissionHelper.bluetoothPermissions())) {
                permissionLauncher.launch(PermissionHelper.bluetoothPermissions())
                return@setOnClickListener
            }
            if (viewModel.isEnabled()) {
                viewModel.startDiscovery()
            } else {
                ensureBluetoothEnabled()
            }
        }

        binding.btnDisconnect.setOnClickListener { viewModel.disconnect() }

        binding.btnSend.setOnClickListener {
            val text = binding.etPayload.text.toString()
            if (text.isNotEmpty()) {
                viewModel.send(text)
                binding.etPayload.text?.clear()
            }
        }

        binding.btnClear.setOnClickListener { viewModel.clearLog() }

        binding.btnGraphs.setOnClickListener {
            startActivity(Intent(this, GraphActivity::class.java))
        }

        // Quick command buttons — each sends a predefined command to the device.
        binding.btnCmdHelp.setOnClickListener { viewModel.send("help") }
        binding.btnCmdStatus.setOnClickListener { viewModel.send("status") }
        binding.btnCmdTemp.setOnClickListener { viewModel.send("temp") }
        binding.btnCmdAccel.setOnClickListener { viewModel.send("accel") }
        binding.btnCmdTime.setOnClickListener { viewModel.send("time") }
        binding.btnCmdVersion.setOnClickListener { viewModel.send("version") }
        binding.btnCmdRelay.setOnClickListener { viewModel.send("relay") }
        binding.btnCmdMonitor.setOnClickListener { viewModel.send("monitor") }

        observeState()
        ensurePermissionsThenLoad()
    }

    private fun ensurePermissionsThenLoad() {
        val needed = PermissionHelper.missing(this, PermissionHelper.bluetoothPermissions())
        if (needed.isEmpty()) {
            ensureBluetoothEnabled()
        } else {
            permissionLauncher.launch(needed)
        }
    }

    private fun ensureBluetoothEnabled() {
        if (viewModel.isEnabled()) {
            viewModel.loadBonded()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Нужно разрешение BLUETOOTH_CONNECT", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { renderState(it) } }

                launch { viewModel.devices.collect { adapter.submitList(it) } }

                launch {
                    viewModel.scanning.collect { scanning ->
                        binding.btnScan.text = if (scanning) "Сканирование…" else "Поиск"
                        binding.btnScan.isEnabled = !scanning
                    }
                }

                launch {
                    viewModel.log
                        .debounce(80) // cap UI refresh to ~12 fps during monitor mode
                        .collect { text ->
                            binding.tvLog.text = text
                            binding.scrollLog.post {
                                binding.scrollLog.fullScroll(View.FOCUS_DOWN)
                            }
                        }
                }

                launch { viewModel.sensorData.collect { renderSensorData(it) } }
            }
        }
    }

    private fun renderState(state: ConnectionState) {
        when (state) {
            is ConnectionState.Idle ->
                setStatus("Не подключено", sendEnabled = false, disconnectEnabled = false)

            is ConnectionState.Connecting ->
                setStatus("Подключение…", sendEnabled = false, disconnectEnabled = false)

            is ConnectionState.Connected ->
                setStatus("Подключено ✓", sendEnabled = true, disconnectEnabled = true)

            is ConnectionState.Disconnected ->
                // Not expected with auto-reconnect, but handled gracefully.
                setStatus("Соединение потеряно", sendEnabled = false, disconnectEnabled = false)

            is ConnectionState.Reconnecting -> {
                setStatus(
                    "Переподключение… (попытка ${state.attempt})",
                    sendEnabled = false,
                    disconnectEnabled = true   // allow user to cancel reconnect
                )
            }

            is ConnectionState.Error -> {
                setStatus("Ошибка: ${state.message}", sendEnabled = false, disconnectEnabled = false)
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                // Don't finish() — user can pick another device from the list.
            }
        }
    }

    private fun renderSensorData(data: SensorData) {
        val parts = buildList {
            data.temperature?.let { add("🌡 %.1f°C".format(it)) }
            if (data.accelX != null || data.accelY != null || data.accelZ != null) {
                add(
                    "📐 X:%.2f  Y:%.2f  Z:%.2f".format(
                        data.accelX ?: 0f,
                        data.accelY ?: 0f,
                        data.accelZ ?: 0f
                    )
                )
            }
        }
        if (parts.isEmpty()) {
            binding.tvSensorData.visibility = View.GONE
        } else {
            binding.tvSensorData.text = parts.joinToString("   |   ")
            binding.tvSensorData.visibility = View.VISIBLE
        }
    }

    private fun setStatus(text: String, sendEnabled: Boolean, disconnectEnabled: Boolean) {
        binding.tvStatus.text = text
        binding.btnSend.isEnabled = sendEnabled
        binding.btnDisconnect.isEnabled = disconnectEnabled
        // Command buttons follow the same enable state as Send.
        binding.btnCmdHelp.isEnabled = sendEnabled
        binding.btnCmdStatus.isEnabled = sendEnabled
        binding.btnCmdTemp.isEnabled = sendEnabled
        binding.btnCmdAccel.isEnabled = sendEnabled
        binding.btnCmdTime.isEnabled = sendEnabled
        binding.btnCmdVersion.isEnabled = sendEnabled
        binding.btnCmdRelay.isEnabled = sendEnabled
        binding.btnCmdMonitor.isEnabled = sendEnabled
    }

    // Cleanup is delegated to BluetoothViewModel.onCleared() → repo.release().
}
