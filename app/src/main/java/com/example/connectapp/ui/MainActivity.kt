package com.example.connectapp.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.connectapp.R
import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.data.models.GlobalConnectionStatus
import com.example.connectapp.data.settings.SettingsRepository
import com.example.connectapp.ui.bluetooth.BluetoothActivity
import com.example.connectapp.ui.history.HistoryActivity
import com.example.connectapp.ui.mqtt.MqttSettingsActivity
import com.example.connectapp.ui.test.TestActivity
import com.example.connectapp.ui.usb.UsbSerialActivity
import com.example.connectapp.ui.theme.AppThemeWithSettings
import com.example.connectapp.ui.theme.ErrorRed
import com.example.connectapp.ui.theme.SuccessGreen
import com.example.connectapp.ui.theme.WarningAmber
import com.example.connectapp.ui.wifi.WifiActivity
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppThemeWithSettings {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val ctx = LocalContext.current

    // GlobalConnectionStatus — persistent-banner ниже showа подключённого
    // транспорта и его адреса. Подписываемся на flow.
    val connectionsMap by GlobalConnectionStatus.flow.collectAsStateWithLifecycle()
    val relevant = remember(connectionsMap) { GlobalConnectionStatus.mostRelevant(connectionsMap) }

    // Last device — для quick-action card'а «Подключиться к последней плате».
    // Используем connectionHistory из SettingsRepository (BT-only сейчас).
    val repo = remember { SettingsRepository(ctx.applicationContext) }
    // remember на Flow — иначе `.map {...}` создаёт новый Flow на каждый
    // recompose, и collectAsStateWithLifecycle на каждый кадр запускает
    // новую корутину сбора. Leak.
    val lastBtFlow = remember(repo) { repo.connectionHistory.map { it.firstOrNull() } }
    val lastBt by lastBtFlow.collectAsStateWithLifecycle(initialValue = null)

    Scaffold { padding ->
        // verticalScroll: при раскрытой "Справке" длина контента
        // переваливает за экран — без скролла footer и часть текста
        // справки были бы обрезаны снизу.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.main_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Persistent connection status — показывается только когда есть
            // активная сессия. AnimatedVisibility красиво ужимается/раскрывается.
            AnimatedVisibility(
                visible = relevant != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                relevant?.let { (transport, snap) ->
                    ConnectionBanner(transport = transport, snapshot = snap, onClick = {
                        ctx.startActivity(activityFor(transport, ctx::class.java).let {
                            Intent(ctx, it)
                        })
                    })
                }
            }

            // Quick-action: если нет активной BT-сессии, но есть история —
            // предлагаем продолжить с последнего устройства одним тапом.
            AnimatedVisibility(
                visible = relevant == null && lastBt != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                lastBt?.let { entry ->
                    QuickResumeCard(name = entry.name, onClick = {
                        ctx.startActivity(Intent(ctx, BluetoothActivity::class.java))
                    })
                }
            }

            Spacer(Modifier.height(8.dp))

            ConnectOption(
                icon = Icons.Filled.Wifi,
                title = stringResource(R.string.btn_wifi),
                subtitle = stringResource(R.string.wifi_subtitle),
                gradient = listOf(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.tertiary
                ),
                onClick = { ctx.startActivity(Intent(ctx, WifiActivity::class.java)) }
            )

            ConnectOption(
                icon = Icons.Filled.Bluetooth,
                title = stringResource(R.string.btn_bluetooth),
                subtitle = stringResource(R.string.bluetooth_subtitle),
                gradient = listOf(
                    MaterialTheme.colorScheme.secondary,
                    MaterialTheme.colorScheme.primary
                ),
                onClick = { ctx.startActivity(Intent(ctx, BluetoothActivity::class.java)) }
            )

            ConnectOption(
                icon = Icons.Filled.Usb,
                title = stringResource(R.string.btn_usb),
                subtitle = stringResource(R.string.usb_subtitle),
                gradient = listOf(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.secondary
                ),
                onClick = { ctx.startActivity(Intent(ctx, UsbSerialActivity::class.java)) }
            )

            ConnectOption(
                icon = Icons.Filled.Science,
                title = stringResource(R.string.btn_test),
                subtitle = stringResource(R.string.test_subtitle),
                gradient = listOf(
                    MaterialTheme.colorScheme.tertiary,
                    MaterialTheme.colorScheme.secondary
                ),
                onClick = { ctx.startActivity(Intent(ctx, TestActivity::class.java)) }
            )

            ConnectOption(
                icon = Icons.Filled.History,
                title = stringResource(R.string.btn_history),
                subtitle = stringResource(R.string.history_subtitle),
                gradient = listOf(
                    MaterialTheme.colorScheme.secondary,
                    MaterialTheme.colorScheme.tertiary
                ),
                onClick = { ctx.startActivity(Intent(ctx, HistoryActivity::class.java)) }
            )

            ConnectOption(
                icon = Icons.Filled.Cloud,
                title = stringResource(R.string.btn_mqtt),
                subtitle = stringResource(R.string.mqtt_subtitle),
                gradient = listOf(
                    MaterialTheme.colorScheme.tertiary,
                    MaterialTheme.colorScheme.primary
                ),
                onClick = { ctx.startActivity(Intent(ctx, MqttSettingsActivity::class.java)) }
            )

            // Большая разворачиваемая справка — что делают все элементы UI
            // в экранах подключений и графиков. По умолчанию свёрнута, чтобы
            // не загромождать главный экран. Состояние переживает rotation.
            HelpSection()

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.main_footer),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Persistent-banner с текущим активным транспортом. Кликабельный — ведёт
 * в соответствующий Activity, чтобы быстро вернуться в активную сессию.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionBanner(
    transport: String,
    snapshot: GlobalConnectionStatus.Snapshot,
    onClick: () -> Unit
) {
    val (statusText, color) = when (val s = snapshot.state) {
        is ConnectionState.Connected -> stringResource(R.string.status_connected) to SuccessGreen
        is ConnectionState.Connecting -> stringResource(R.string.status_connecting) to WarningAmber
        is ConnectionState.Reconnecting -> stringResource(R.string.status_reconnecting, s.attempt) to WarningAmber
        is ConnectionState.Disconnected -> stringResource(R.string.status_disconnected) to ErrorRed
        is ConnectionState.Error -> stringResource(R.string.status_error, s.message) to ErrorRed
        is ConnectionState.Idle -> stringResource(R.string.status_idle) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val transportLabel = when (transport) {
        "bt" -> stringResource(R.string.btn_bluetooth)
        "wifi" -> stringResource(R.string.btn_wifi)
        "usb" -> stringResource(R.string.btn_usb)
        else -> transport.uppercase()
    }
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { role = Role.Button },
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.12f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color, CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "$transportLabel · $statusText",
                    style = MaterialTheme.typography.titleSmall,
                    color = color,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    snapshot.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickResumeCard(name: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { role = Role.Button },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.quick_resume_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/** Маппинг ключа транспорта в Activity-класс. */
private fun activityFor(transport: String, fallback: Class<*>): Class<*> = when (transport) {
    "bt" -> BluetoothActivity::class.java
    "wifi" -> WifiActivity::class.java
    "usb" -> UsbSerialActivity::class.java
    else -> fallback
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    gradient: List<androidx.compose.ui.graphics.Color>,
    onClick: () -> Unit
) {
    // Card(onClick=) даёт ripple, focus и роль Button для TalkBack.
    // Раньше pointerInput { detectTapGestures } лишал accessibility.
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 110.dp)
            .semantics { role = Role.Button },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(brush = Brush.linearGradient(gradient), shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Большая разворачиваемая справка. Объясняет назначение всех экранов,
 * графиков, кнопок и чипов. Свёрнута по умолчанию — иконка ❔ + заголовок;
 * тап раскрывает полный текст с разделами.
 *
 * Текст намеренно длинный и без формул — целевая аудитория: студент или
 * преподаватель, который видит приложение впервые и хочет понять что
 * делает каждая кнопка и зачем тот или иной анализ.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelpSection() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { role = Role.Button },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.HelpOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Справка",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "что показывает каждый график, кнопка и чип",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Свернуть" else "Развернуть",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                HelpBlock(
                    title = "О чём приложение",
                    body = "ConnectApp получает поток данных с микроконтроллерной платы — две температуры " +
                        "(T1, T2) и два трёхосевых акселерометра (ax/ay/az). Данные приходят через Bluetooth SPP, " +
                        "Wi-Fi TCP, USB Serial или MQTT-мост. На экране Графиков рисуются временные ряды и " +
                        "идёт реалтайм-анализ: статистика вибрации, наклон, тепловой поток, спектры. " +
                        "Все рассчёты делаются на устройстве — серверу ничего не отправляется."
                )

                HelpBlock(
                    title = "Главный экран — карточки подключений",
                    body = "• Wi-Fi — подключиться к плате по TCP (ESP32, Raspberry Pi).\n" +
                        "• Bluetooth — классический SPP, для HC-05 и совместимых модулей.\n" +
                        "• USB — Serial через OTG-кабель (CDC ACM, FTDI, CH34x, Prolific).\n" +
                        "• Тест — встроенный симулятор: синусоидальная температура + шумящий акселерометр. " +
                        "Полезно проверить графики без реального железа.\n" +
                        "• История — список прошлых сессий с замерами и графиками.\n" +
                        "• MQTT — мост к брокеру, плата публикует в топик, телефон подписан."
                )

                HelpBlock(
                    title = "Экран Графиков — кнопки сверху (слева направо)",
                    body = "← Назад — закрыть экран.\n" +
                        "📏 Линейка — режим курсора: тап по графику = поставить вертикаль на момент " +
                        "времени и прочитать значения. Тап на иконку — переключение single/dual " +
                        "(в dual-режиме показывается Δt между курсорами). Long-press — сброс курсоров.\n" +
                        "✕ Крестик — отдельная кнопка очистки курсоров (видна только когда они активны).\n" +
                        "📷 Камера — сделать скриншот экрана и поделиться через Share.\n" +
                        "🔔 Колокол — настройка пороговых алертов: задаёшь max для каждого канала " +
                        "(T1/T2/ax1/.../az2), при превышении — вибрация + push-уведомление + запись в журнал.\n" +
                        "📋 Список — открыть Журнал аномалий: история всех срабатываний порогов, " +
                        "можно экспортировать в CSV.\n" +
                        "⛔ ZoomOut — сбросить pinch-zoom (видна только при активном зуме).\n" +
                        "❄ Снежинка — «заморозить» графики на текущем кадре, пока поток данных идёт. " +
                        "Удобно посмотреть стат-значения не торопясь. Повторный тап — продолжить.\n" +
                        "📄 PDF — сгенерировать отчёт со всеми графиками и таблицами.\n" +
                        "⬇ Стрелка — экспорт сырых данных в CSV."
                )

                HelpBlock(
                    title = "Кнопки управления платой",
                    body = "• calib — отправляет команду калибровки (обнуление акселерометра в покое).\n" +
                        "• test — quick-тест: плата отвечает контрольным пакетом, проверка связи.\n" +
                        "• monitor / stop — запуск/остановка непрерывного потока данных."
                )

                HelpBlock(
                    title = "Zoom-bar (окно)",
                    body = "Управляет ДЛИНОЙ показываемого временного окна:\n" +
                        "• 🔍− — уменьшить масштаб (окно растёт в 1.5×, до 60 минут).\n" +
                        "• 1m / 30s / все — текущая длина окна.\n" +
                        "• 🔍+ — увеличить детализацию (окно сужается в 1.5×, минимум 5 сек).\n" +
                        "• ∞ — показать ВСЕ данные сессии без ограничения.\n" +
                        "На самих графиках работает pinch-zoom (двумя пальцами) и pan (свайп)."
                )

                HelpBlock(
                    title = "Чипы overlays — научные слои поверх графиков",
                    body = "• envelope — полупрозрачная полоса min/max в скользящем окне. Видишь диапазон " +
                        "колебаний, а не только линию среднего.\n" +
                        "• ±σ — статистическая полоса ±1 стандартное отклонение. Узкая полоса = " +
                        "стабильный сигнал, широкая = шумный.\n" +
                        "• ⚠ alert — пунктирные линии настроенных порогов. Участок сигнала " +
                        "выше порога подкрашивается красным.\n" +
                        "• phase-lock — авто-окно длиной ровно 2 периода доминирующей частоты " +
                        "(определяется FFT). Сигнал «стабилизируется» — каждый кадр показывает " +
                        "ровно 2 цикла. Работает только для периодических данных.\n" +
                        "• advanced — раскрывает расширенный математический анализ ниже (FFT, " +
                        "спектрограмма, Lissajous, Phosphor, Cross-correlation, Kalman, Velocity)."
                )

                HelpBlock(
                    title = "Графики — что они показывают",
                    body = "• Температура °C — T1 и T2 во времени. Разница ΔT идёт в расчёт " +
                        "теплового потока ниже.\n" +
                        "• Акселерометр 1/2 — три оси ax/ay/az в LSB. Чипы ax/ay/az над каждым " +
                        "графиком включают/отключают отдельные оси. az обычно ~1000 (1g " +
                        "гравитации) — поэтому она нарисована на ОТДЕЛЬНОЙ правой Y-оси, иначе " +
                        "ax/ay в ±50 сплющивались бы в плоскую линию.\n" +
                        "• 3D облако — точки (ax, ay, az) в пространстве за всё окно. A1 красный, " +
                        "A2 синий. Перетаскивай — вращай камеру, pinch — зум."
                )

                HelpBlock(
                    title = "Математический анализ — базовый набор",
                    body = "Всегда виден, без режима advanced.\n\n" +
                        "• Дескрипторы вибрации (ISO 10816). RMS — среднеквадратичный уровень " +
                        "сигнала, оценка «энергии» вибрации. Peak — максимум по модулю. " +
                        "Crest = Peak/RMS — насколько выраженные пики (Crest > 4 = ударная " +
                        "вибрация, признак подшипниковых дефектов). Kurtosis — «острота» " +
                        "распределения, Kurt > 4 тоже сигнализирует об импульсных событиях.\n\n" +
                        "• Ориентация (Tilt) — пузырьковый уровень: вычисляет углы Pitch и Roll " +
                        "из текущего вектора ускорения (assuming, что главная сила — " +
                        "гравитация). |a| = модуль вектора, в покое ≈ 1000 LSB = 1g.\n\n" +
                        "• Тепловой поток (закон Фурье) — q = -k·∇T. Считает поток тепла Вт/м² " +
                        "через стержень между T1 и T2. ∇T = градиент температуры в К/м. " +
                        "Допущения: расстояние 5 см, материал — медь (k=401)."
                )

                HelpBlock(
                    title = "Математический анализ — advanced",
                    body = "Включается чипом «advanced» над графиками.\n\n" +
                        "• FFT — амплитудный спектр ax1 с окном Ханна. Пик = доминирующая частота " +
                        "вибрации (Гц). Чип «dB» переключает на логарифмическую шкалу. " +
                        "Жёлтые пунктиры — гармоники 2f/3f/4f от пика.\n\n" +
                        "• Спектрограмма (STFT-waterfall) — спектр во ВРЕМЕНИ. По Y частоты, " +
                        "по X время, цвет (turbo-палитра) = амплитуда. Видно как меняется " +
                        "спектр — старт/стоп вибрации, скольжение частоты.\n\n" +
                        "• Orbit / Lissajous — фазовые портреты в координатах (ax, ay). " +
                        "Эллипс = две гармонические компоненты в квадратуре, окружность = " +
                        "круговое движение, хаотичная клякса = шум.\n\n" +
                        "• Phosphor persistence — стиль аналогового осциллографа: новые " +
                        "точки яркие, старые гаснут. Удобно ловить переходные процессы.\n\n" +
                        "• Cross-correlation ax1 ↔ ax2 — взаимная корреляция двух акселерометров. " +
                        "Best lag = сдвиг по времени между сигналами. Если lag > 0 — ax2 отстаёт. " +
                        "R = коэффициент корреляции в пике (1 = идентичны, 0 = независимы).\n\n" +
                        "• Kalman fusion — оптимальная оценка x̂ значения ax по двум зашумлённым " +
                        "измерениям с учётом дисперсий процесса (Q) и измерений (R1, R2). " +
                        "P — оставшаяся неопределённость оценки.\n\n" +
                        "• Velocity & Displacement — скорость (∫a·dt) и смещение (∫∫a·dt²) " +
                        "из ускорения. Drift-detrend подавляет накопление DC-смещения. " +
                        "Единицы LSB·с и LSB·с² — для физических м/с нужна калибровка LSB→g."
                )

                HelpBlock(
                    title = "Жесты на графиках",
                    body = "• Тап — поставить курсор на момент времени, прочитать значения " +
                        "(в dual-режиме два курсора показывают Δt и Δзначений).\n" +
                        "• Double tap — сброс зума.\n" +
                        "• Pinch (двумя пальцами) — увеличить детализацию.\n" +
                        "• Pan (свайп) — сдвиг окна, когда активен зум."
                )

                HelpBlock(
                    title = "Карточка «текущее»",
                    body = "Над графиками — крупные цифры текущих значений всех 8 каналов " +
                        "(T1, T2, ax1/ay1/az1, ax2/ay2/az2). Если канал превышает настроенный " +
                        "в Алертах порог — значение подсвечивается красным."
                )
            }
        }
    }
}

/**
 * Один блок справки: жирный заголовок + многострочное тело.
 * Используется в [HelpSection] для разбиения текста на тематические секции.
 */
@Composable
private fun HelpBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
