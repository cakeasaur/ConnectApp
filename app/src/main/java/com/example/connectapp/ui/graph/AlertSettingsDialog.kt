package com.example.connectapp.ui.graph

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.connectapp.utils.AlertEngine

private val CHANNELS = listOf(
    "t1"  to "T1 °C",
    "t2"  to "T2 °C",
    "ax1" to "ax1 (LSB)",
    "ay1" to "ay1 (LSB)",
    "az1" to "az1 (LSB)",
    "ax2" to "ax2 (LSB)",
    "ay2" to "ay2 (LSB)",
    "az2" to "az2 (LSB)",
)

/**
 * Диалог настройки пороговых алертов.
 *
 * Каждый канал: пустое поле = алерт отключён, число = максимально
 * допустимое значение. Сохранение применяет пороги в [AlertEngine]
 * немедленно — без перезапуска приложения.
 */
@Composable
fun AlertSettingsDialog(onDismiss: () -> Unit) {
    val texts = remember {
        CHANNELS.associate { (key, _) ->
            key to mutableStateOf(
                AlertEngine.getThreshold(key)?.let { "%.2f".format(it) } ?: ""
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⚠ Алерты по порогу") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Уведомление + вибрация когда значение превышает порог.\nОставь поле пустым — алерт отключён.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                CHANNELS.forEach { (key, label) ->
                    val state = texts[key]!!
                    OutlinedTextField(
                        value = state.value,
                        onValueChange = { state.value = it },
                        label = { Text(label) },
                        suffix = { Text("max") },
                        placeholder = { Text("отключён") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = state.value.isNotEmpty() && state.value.toFloatOrNull() == null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                CHANNELS.forEach { (key, _) ->
                    AlertEngine.setThreshold(key, texts[key]!!.value.trim().toFloatOrNull())
                }
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
