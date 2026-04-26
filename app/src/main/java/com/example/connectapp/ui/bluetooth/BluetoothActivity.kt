package com.example.connectapp.ui.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
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
import com.example.connectapp.databinding.ActivityBluetoothBinding
import com.example.connectapp.utils.PermissionHelper
import kotlinx.coroutines.launch

class BluetoothActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBluetoothBinding
    private val viewModel: BluetoothViewModel by viewModels()
    private lateinit var adapter: BluetoothDeviceAdapter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
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
            // Only start discovery if BT is already enabled.
            // If not — request enable; user will tap Scan again after.
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
        // ACTION_REQUEST_ENABLE requires BLUETOOTH_CONNECT on API 31+.
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
                launch {
                    viewModel.state.collect { renderState(it) }
                }
                launch {
                    viewModel.devices.collect { adapter.submitList(it) }
                }
                launch {
                    viewModel.scanning.collect { scanning ->
                        binding.btnScan.text = if (scanning) "Сканирование…" else "Поиск"
                        binding.btnScan.isEnabled = !scanning
                    }
                }
                launch {
                    viewModel.log.collect { text ->
                        binding.tvLog.text = text
                        binding.scrollLog.post {
                            binding.scrollLog.fullScroll(android.view.View.FOCUS_DOWN)
                        }
                    }
                }
            }
        }
    }

    private fun renderState(state: ConnectionState) {
        when (state) {
            is ConnectionState.Idle -> setStatus("Не подключено", busy = false)
            is ConnectionState.Connecting -> setStatus("Подключение…", busy = true)
            is ConnectionState.Connected -> setStatus("Подключено", busy = true)
            is ConnectionState.Disconnected -> {
                setStatus("Соединение потеряно", busy = false)
                Toast.makeText(this, "Соединение потеряно", Toast.LENGTH_SHORT).show()
                finish()
            }
            is ConnectionState.Error -> {
                setStatus("Ошибка: ${state.message}", busy = false)
                Toast.makeText(this, "Ошибка: ${state.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun setStatus(text: String, busy: Boolean) {
        binding.tvStatus.text = text
        binding.btnDisconnect.isEnabled = busy
        binding.btnSend.isEnabled = busy
    }

    // Cleanup is handled by BluetoothViewModel.onCleared → repo.release()
    // (which stops discovery, unregisters receiver, cancels internal scope)
    // and readerJob's finally block (wrapped in NonCancellable) closes the socket.
}
