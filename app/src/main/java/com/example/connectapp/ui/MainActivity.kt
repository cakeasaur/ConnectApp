package com.example.connectapp.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.connectapp.databinding.ActivityMainBinding
import com.example.connectapp.ui.bluetooth.BluetoothActivity
import com.example.connectapp.ui.wifi.WifiActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnWifi.setOnClickListener {
            startActivity(Intent(this, WifiActivity::class.java))
        }
        binding.btnBluetooth.setOnClickListener {
            startActivity(Intent(this, BluetoothActivity::class.java))
        }
    }
}
