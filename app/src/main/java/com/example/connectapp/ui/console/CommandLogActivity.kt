package com.example.connectapp.ui.console

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.connectapp.data.models.CommandLog
import com.example.connectapp.ui.theme.AppThemeWithSettings
import com.example.connectapp.ui.wifi.LogView
import com.example.connectapp.utils.stripAnsi

/**
 * Отдельный экран «Лог команд» — только текстовые ответы платы (help, меню,
 * статусы), без потока цифр телеметрии. Источник — синглтон [CommandLog],
 * который заполняется из parseChunk всех транспортов.
 */
class CommandLogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppThemeWithSettings {
                CommandLogScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommandLogScreen(onBack: () -> Unit) {
    val text by CommandLog.text.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Лог команд") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { CommandLog.clear() }) {
                        Icon(Icons.Filled.ClearAll, contentDescription = "Очистить")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (text.isEmpty()) {
                Text(
                    "Здесь появятся текстовые ответы платы (help, меню, статусы). " +
                        "Телеметрия (цифры) сюда не попадает.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LogView(log = stripAnsi(text), modifier = Modifier.fillMaxSize(), autoScroll = true)
            }
        }
    }
}
