package com.example.connectapp.ui.onboarding

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.connectapp.data.settings.LineEnding
import com.example.connectapp.data.settings.SettingsRepository
import com.example.connectapp.ui.theme.AppThemeWithSettings
import com.example.connectapp.ui.theme.SuccessGreen
import kotlinx.coroutines.launch

/**
 * Стартовый визард первого запуска. Помогает подготовить приложение под
 * конкретную плату: терминатор команд, частоту опроса, чувствительность
 * акселерометра — и объясняет, что после подключения надо отправить `help`,
 * чтобы приложение само распознало команды и каналы.
 *
 * Показывается, пока [com.example.connectapp.data.settings.AppSettings.onboardingDone]
 * == false. По завершении (или «Пропустить») флаг ставится в true.
 */
class OnboardingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppThemeWithSettings {
                OnboardingScreen(onDone = { finish() })
            }
        }
    }
}

private const val STEP_COUNT = 4

@Composable
private fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx.applicationContext) }
    val scope = rememberCoroutineScope()

    var step by remember { mutableIntStateOf(0) }
    var lineEnding by remember { mutableStateOf(LineEnding.CRLF) }
    var sampleRate by remember { mutableFloatStateOf(10f) }
    var accelSens by remember { mutableFloatStateOf(1000f) }

    fun finish(apply: Boolean) {
        scope.launch {
            if (apply) {
                repo.setLineEnding(lineEnding)
                repo.setSampleRateHz(sampleRate)
                repo.setAccelSensitivity(accelSens)
            }
            repo.setOnboardingDone(true)
            onDone()
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            LinearProgressIndicator(
                progress = { (step + 1f) / STEP_COUNT },
                modifier = Modifier.fillMaxWidth()
            )

            when (step) {
                0 -> StepWelcome()
                1 -> StepLineEnding(selected = lineEnding, onSelect = { lineEnding = it })
                2 -> StepSampleRate(selected = sampleRate, onSelect = { sampleRate = it })
                3 -> StepAccelSens(selected = accelSens, onSelect = { accelSens = it })
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (step > 0) {
                    TextButton(onClick = { step-- }) { Text("Назад") }
                }
                Spacer(Modifier.weight(1f))
                if (step < STEP_COUNT - 1) {
                    TextButton(onClick = { finish(apply = false) }) { Text("Пропустить") }
                    Button(onClick = { step++ }) { Text("Далее") }
                } else {
                    Button(onClick = { finish(apply = true) }) { Text("Готово") }
                }
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StepWelcome() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(
            Icons.Filled.Tune,
            contentDescription = null,
            modifier = Modifier.height(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        StepTitle(
            "Настроим под вашу плату",
            "Несколько шагов, чтобы приложение говорило с устройством на его языке. " +
                "Всё можно изменить позже в настройках подключения."
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepLineEnding(selected: LineEnding, onSelect: (LineEnding) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepTitle(
            "Терминатор команд",
            "Чем плата завершает принимаемые команды. PIC/Microchip обычно CR, " +
                "Arduino/Linux — LF, многие SCPI-устройства — CRLF. Не уверены — оставьте CRLF."
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val items = listOf(
                LineEnding.CRLF to "CRLF (\\r\\n)",
                LineEnding.LF to "LF (\\n)",
                LineEnding.CR to "CR (\\r)",
                LineEnding.NONE to "Без терминатора",
            )
            items.forEach { (le, label) ->
                FilterChip(selected = selected == le, onClick = { onSelect(le) }, label = { Text(label) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepSampleRate(selected: Float, onSelect: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepTitle(
            "Частота опроса",
            "С какой частотой плата шлёт отсчёты (Гц). Влияет на ось спектра (FFT), " +
                "период детекта и лаги. Для PIC24-платы по умолчанию 10 Гц."
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1f, 5f, 10f, 20f, 50f).forEach { hz ->
                FilterChip(
                    selected = selected == hz,
                    onClick = { onSelect(hz) },
                    label = { Text("${hz.toInt()} Гц") }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepAccelSens(selected: Float, onSelect: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepTitle(
            "Чувствительность акселерометра",
            "Сколько LSB приходится на 1g — делитель для перевода сырых значений в g. " +
                "Покоящаяся ось под гравитацией должна давать ~это число. По умолчанию 1000."
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(256f, 1000f, 2048f, 4096f).forEach { v ->
                FilterChip(
                    selected = selected == v,
                    onClick = { onSelect(v) },
                    label = { Text("${v.toInt()} LSB/g") }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = SuccessGreen, modifier = Modifier.height(20.dp))
            Text(
                "После подключения отправьте плате «help» — приложение само распознает " +
                    "её команды и дополнительные каналы.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
