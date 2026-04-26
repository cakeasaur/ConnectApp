package com.example.connectapp.ui.wifi

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.databinding.ActivityWifiBinding
import kotlinx.coroutines.launch

class WifiActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWifiBinding
    private val viewModel: WifiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWifiBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvLog.movementMethod = ScrollingMovementMethod()
        binding.etHost.setText(viewModel.host)
        binding.etPort.setText(viewModel.port)

        binding.btnConnect.setOnClickListener { onConnectClicked() }
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
    }

    private fun onConnectClicked() {
        val host = binding.etHost.text.toString().trim()
        val portStr = binding.etPort.text.toString().trim()
        val port = portStr.toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) {
            Toast.makeText(this, "Введите валидный IP и порт (1..65535)", Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.connect(host, port)
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        renderState(state)
                    }
                }
                launch {
                    viewModel.log.collect { text ->
                        binding.tvLog.text = text
                        // Auto-scroll to the bottom.
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
            is ConnectionState.Idle -> setStatus("Не подключено", connectEnabled = true)
            is ConnectionState.Connecting -> setStatus("Подключение…", connectEnabled = false)
            is ConnectionState.Connected -> setStatus("Подключено", connectEnabled = false)
            is ConnectionState.Disconnected -> {
                setStatus("Соединение потеряно", connectEnabled = true)
                Toast.makeText(this, "Соединение потеряно", Toast.LENGTH_SHORT).show()
                finish()
            }
            is ConnectionState.Error -> {
                setStatus("Ошибка: ${state.message}", connectEnabled = true)
                Toast.makeText(this, "Ошибка: ${state.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun setStatus(text: String, connectEnabled: Boolean) {
        binding.tvStatus.text = text
        binding.btnConnect.isEnabled = connectEnabled
        binding.btnDisconnect.isEnabled = !connectEnabled
        binding.btnSend.isEnabled = !connectEnabled
    }

    // Socket cleanup is handled by ViewModel.onCleared → readerJob's finally block
    // (wrapped in NonCancellable). No need to call disconnect() here.
}
