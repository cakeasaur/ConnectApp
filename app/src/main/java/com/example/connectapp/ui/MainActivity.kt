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
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.connectapp.R
import com.example.connectapp.utils.CrashReporter
import com.example.connectapp.data.models.ConnectionState
import com.example.connectapp.data.models.GlobalConnectionStatus
import com.example.connectapp.data.settings.SettingsRepository
import com.example.connectapp.ui.bluetooth.BluetoothActivity
import com.example.connectapp.ui.onboarding.OnboardingActivity
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

    // Онбординг первого запуска. null = ещё не прочитали из DataStore (не дёргаем),
    // false = показать визард один раз. Запускаем отдельной Activity.
    val onboardingDone by remember(repo) { repo.flow.map { it.onboardingDone } }
        .collectAsStateWithLifecycle(initialValue = null)
    var onboardingLaunched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(onboardingDone) {
        if (onboardingDone == false && !onboardingLaunched) {
            onboardingLaunched = true
            ctx.startActivity(Intent(ctx, OnboardingActivity::class.java))
        }
    }

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

            // Баннер прошлого краша — отчёт пишется локально (CrashReporter),
            // отсюда отправляется разработчику (share файла через FileProvider).
            val crashCtx = LocalContext.current
            var hasCrash by remember { mutableStateOf(CrashReporter.hasReport(crashCtx)) }
            if (hasCrash) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Прошлый сеанс завершился аварийно",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "Журнал ошибки сохранён локально. Отправь его разработчику.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Text(
                                "Отправить отчёт",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    val uri = FileProvider.getUriForFile(
                                        crashCtx,
                                        "${crashCtx.packageName}.fileprovider",
                                        CrashReporter.file(crashCtx)
                                    )
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "ConnectApp crash report")
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    crashCtx.startActivity(
                                        Intent.createChooser(send, "Отправить отчёт")
                                    )
                                }
                            )
                            Text(
                                "Скрыть",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable {
                                    CrashReporter.clear(crashCtx)
                                    hasCrash = false
                                }
                            )
                        }
                    }
                }
            }

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
                onClick = { ctx.startActivity(Intent(ctx, WifiActivity::class.java)) },
                status = connectionsMap["wifi"],
            )

            ConnectOption(
                icon = Icons.Filled.Bluetooth,
                title = stringResource(R.string.btn_bluetooth),
                subtitle = stringResource(R.string.bluetooth_subtitle),
                gradient = listOf(
                    MaterialTheme.colorScheme.secondary,
                    MaterialTheme.colorScheme.primary
                ),
                onClick = { ctx.startActivity(Intent(ctx, BluetoothActivity::class.java)) },
                status = connectionsMap["bt"],
            )

            ConnectOption(
                icon = Icons.Filled.Usb,
                title = stringResource(R.string.btn_usb),
                subtitle = stringResource(R.string.usb_subtitle),
                gradient = listOf(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.secondary
                ),
                onClick = { ctx.startActivity(Intent(ctx, UsbSerialActivity::class.java)) },
                status = connectionsMap["usb"],
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
    onClick: () -> Unit,
    status: GlobalConnectionStatus.Snapshot? = null,
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
            val pill = status?.let { transportStatusPill(it) }
            if (pill != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(color = pill.second, shape = CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = pill.first,
                        style = MaterialTheme.typography.labelMedium,
                        color = pill.second,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Короткая метка состояния транспорта для карточки главного меню: текст + цвет.
 * Idle не показываем — карточка остаётся чистой, индикатор только когда есть
 * что сообщить (подключено / процесс / ошибка / обрыв).
 */
@Composable
private fun transportStatusPill(s: GlobalConnectionStatus.Snapshot): Pair<String, androidx.compose.ui.graphics.Color>? =
    when (s.state) {
        is ConnectionState.Connected -> stringResource(R.string.status_connected) to SuccessGreen
        is ConnectionState.Connecting -> stringResource(R.string.status_connecting) to WarningAmber
        is ConnectionState.Reconnecting -> stringResource(R.string.main_status_reconnecting) to WarningAmber
        is ConnectionState.Error -> stringResource(R.string.main_status_error) to ErrorRed
        is ConnectionState.Disconnected -> stringResource(R.string.status_disconnected) to ErrorRed
        is ConnectionState.Idle -> null
    }

/**
 * Встроенное руководство пользователя. Официальным языком описывает назначение
 * всех экранов, графиков, кнопок, чипов и режимов. Свёрнуто по умолчанию —
 * иконка справки + заголовок; нажатие раскрывает полный текст по разделам.
 *
 * Поддерживается в актуальном состоянии относительно UI: при добавлении кнопок,
 * чипов или экранов соответствующий раздел обновляется здесь же.
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
                    "руководство: назначение экранов, графиков, кнопок и режимов",
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
                    title = "Назначение приложения",
                    body = "ConnectApp — клиент телеметрии микроконтроллерной платы. Принимает поток " +
                        "измерений: два канала температуры (T1, T2) и два трёхосевых акселерометра " +
                        "(ax/ay/az). Поддерживаемые транспорты: Bluetooth SPP, Wi-Fi TCP, USB Serial " +
                        "и MQTT-мост. На экране «Графики» выполняется визуализация временных рядов и " +
                        "анализ в реальном времени: вибродиагностика, оценка ориентации, тепловой поток, " +
                        "спектральный анализ. Все вычисления производятся локально на устройстве; данные " +
                        "не передаются на внешний сервер."
                )

                HelpBlock(
                    title = "Главный экран — источники подключения",
                    body = "• Wi-Fi — соединение с платой по TCP (например, ESP32, Raspberry Pi).\n" +
                        "• Bluetooth — классический профиль SPP (HC-05, BOLUTEK и совместимые модули). " +
                        "Неспаренное устройство приложение сопрягает автоматически при подключении " +
                        "(потребуется однократно подтвердить PIN, обычно 1234 или 0000).\n" +
                        "• USB — Serial через OTG-кабель (CDC ACM, FTDI, CH34x, Prolific).\n" +
                        "• Тест — встроенный имитатор данных (синусоидальная температура и зашумлённый " +
                        "акселерометр) для проверки графиков без подключённого оборудования.\n" +
                        "• История — список прошлых сессий с измерениями и графиками.\n" +
                        "• MQTT — мост к брокеру: плата публикует данные в топик, приложение подписано."
                )

                HelpBlock(
                    title = "Панель инструментов экрана «Графики»",
                    body = "Слева направо:\n" +
                        "• Назад — закрыть экран и вернуться к источнику подключения.\n" +
                        "• Курсор (значок линейки) — режим считывания значений: касание по графику " +
                        "ставит вертикальный маркер на выбранный момент времени. Касание самого значка " +
                        "переключает одиночный/двойной курсор; в двойном режиме отображается интервал Δt " +
                        "между маркерами. Долгое нажатие сбрасывает курсоры.\n" +
                        "• Очистка курсоров — отдельная кнопка, отображается только при активных курсорах.\n" +
                        "• Сброс масштаба — отменяет увеличение; отображается только при активном зуме.\n" +
                        "• Пауза (значок снежинки) — фиксирует графики на текущем кадре, пока поток данных " +
                        "продолжается. Позволяет спокойно изучить значения. Повторное нажатие возобновляет.\n" +
                        "• Меню (значок «⋮») — дополнительные действия:\n" +
                        "   – Журнал событий (RRD) — таблица выгруженного журнала платы;\n" +
                        "   – Журнал аномалий — история срабатываний пороговых алертов;\n" +
                        "   – Настроить алерты — задание порогов по каждому каналу;\n" +
                        "   – Экспорт PDF — отчёт со всеми графиками и таблицами;\n" +
                        "   – Экспорт CSV — выгрузка сырых данных;\n" +
                        "   – Скриншот — снимок экрана и передача через системное «Поделиться»."
                )

                HelpBlock(
                    title = "Управление платой",
                    body = "Под индикатором состояния:\n" +
                        "• Мониторинг / Стоп (на всю ширину) — запуск и остановка непрерывного потока " +
                        "данных. Остановка выполняется управляющим байтом ESC (требование текущей " +
                        "прошивки).\n" +
                        "• calib — команда калибровки (обнуление акселерометра в состоянии покоя).\n" +
                        "• test — контрольный запрос: плата отвечает тестовым пакетом, проверка связи.\n" +
                        "• dump — выгрузка журнала событий RRD с платы. Результат открывается в разделе " +
                        "«Журнал событий» (меню «⋮»)."
                )

                HelpBlock(
                    title = "Окно времени (панель масштаба)",
                    body = "Управляет длиной отображаемого временного окна:\n" +
                        "• Уменьшить масштаб — окно увеличивается (в 1,5×, до 60 минут).\n" +
                        "• Текущая длина окна (например, 1 мин / 30 с / «все»).\n" +
                        "• Увеличить детализацию — окно сужается (в 1,5×, минимум 5 секунд).\n" +
                        "• «∞» — показать все данные сессии без ограничения.\n" +
                        "Непосредственно на графиках доступны масштабирование сведением пальцев и " +
                        "сдвиг окна свайпом."
                )

                HelpBlock(
                    title = "Чипы — слои и режимы отображения",
                    body = "• envelope — полупрозрачная полоса min/max в скользящем окне; показывает " +
                        "диапазон колебаний, а не только линию среднего.\n" +
                        "• ±σ — полоса ±1 стандартное отклонение. Узкая полоса соответствует " +
                        "стабильному сигналу, широкая — шумному.\n" +
                        "• alert — пунктирные линии настроенных порогов; участок сигнала выше порога " +
                        "выделяется красным.\n" +
                        "• время отн. / время — переключение оси X между относительным временем (секунды " +
                        "от начала окна) и абсолютным (ЧЧ:ММ:СС по часам устройства). Абсолютное время " +
                        "удобно для сопоставления выбросов с записями Журнала событий.\n" +
                        "• ед: LSB / g — единицы измерения ускорения. В режиме «g» сырые отсчёты делятся " +
                        "на чувствительность датчика, заданную в Настройках (по умолчанию 1000 LSB/g). " +
                        "Переключение влияет на графики, статистику, легенду и карточку значений; " +
                        "температура и спектральный анализ не затрагиваются.\n" +
                        "• phase-lock — автоматическое окно длиной ровно два периода доминирующей частоты " +
                        "(определяется по FFT). Каждый кадр показывает ровно два цикла; применимо только " +
                        "к периодическим сигналам.\n" +
                        "• advanced — раскрывает блок расширенного анализа (FFT, спектрограмма, Lissajous, " +
                        "Phosphor, кросс-корреляция, фильтр Калмана, скорость/смещение)."
                )

                HelpBlock(
                    title = "Графики",
                    body = "• Температура (°C) — каналы T1 и T2 во времени. Разность ΔT используется " +
                        "в расчёте теплового потока (см. ниже).\n" +
                        "• Акселерометр 1 и 2 — три оси ax/ay/az в выбранных единицах (LSB или g). " +
                        "Чипы ax/ay/az над каждым графиком включают и отключают отдельные оси. Ось az " +
                        "в покое составляет ≈1g (≈1000 LSB — гравитация) и выводится на отдельной правой " +
                        "оси Y; без этого колебания ax/ay (порядка ±0,05g) были бы визуально подавлены.\n" +
                        "• Легенда (верхний левый угол графика) показывает текущее значение каждой оси; " +
                        "символ «ᴿ» помечает серию, привязанную к правой оси.\n" +
                        "• Под каждым графиком акселерометра — строки статистики за окно: минимум, " +
                        "среднее и максимум по каждой включённой оси.\n" +
                        "• 3D-облако — точки (ax, ay, az) в пространстве за всё окно: A1 — красный, " +
                        "A2 — синий. Перетаскивание вращает камеру, сведение пальцев масштабирует."
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
                        "из ускорения. Drift-detrend подавляет накопление постоянной составляющей. " +
                        "Единицы зависят от выбранного режима: в «LSB» — отсчёты, в «g» (при заданной " +
                        "чувствительности) — физические величины."
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
                    title = "Индикатор состояния (вердикт)",
                    body = "Над карточкой значений выводится сводный статус по настроенным порогам " +
                        "алертов:\n" +
                        "• НОРМА (зелёный) — все каналы в пределах порогов;\n" +
                        "• ПОВЫШЕННАЯ ВИБРАЦИЯ (оранжевый) — превышен порог по оси акселерометра;\n" +
                        "• ПЕРЕГРЕВ (красный) — превышен порог по температуре;\n" +
                        "• ПЕРЕГРЕВ + ВИБРАЦИЯ — оба условия одновременно;\n" +
                        "• ПОРОГИ НЕ ЗАДАНЫ (серый) — ни один порог не настроен, оценка невозможна.\n" +
                        "Температура сравнивается со знаком, ускорение — по модулю (выброс в любую " +
                        "сторону)."
                )

                HelpBlock(
                    title = "Карточка «Текущее»",
                    body = "Крупные значения всех восьми каналов (T1, T2, ax1/ay1/az1, ax2/ay2/az2). " +
                        "Превышение настроенного порога подсвечивается красным. Единицы ускорения " +
                        "соответствуют выбранному режиму (LSB или g)."
                )

                HelpBlock(
                    title = "Журнал событий (RRD)",
                    body = "Выгрузка внутреннего журнала платы по команде «dump» на экране «Графики». " +
                        "Открывается из меню «⋮» → «Журнал событий».\n" +
                        "Таблица содержит: индекс записи, дату и время, событие, маркер начала/конца " +
                        "(S — start, E — end), текущее/минимальное/максимальное значение, единицу и " +
                        "длительность. Парные записи S и E сворачиваются в один интервал с вычисленной " +
                        "длительностью.\n" +
                        "Переключатель «Парсинг / Сырьё» показывает либо разобранную таблицу, либо " +
                        "исходный текст дампа. Доступен экспорт в CSV и PDF. Журнал сохраняется на " +
                        "устройстве и восстанавливается после перезапуска приложения."
                )

                HelpBlock(
                    title = "Встроенная консоль",
                    body = "В нижней части экрана «Графики» — текстовые ответы платы и поле ввода " +
                        "произвольных команд. Позволяет отправлять команды вручную и видеть ответ, " +
                        "не покидая экран с графиками."
                )

                HelpBlock(
                    title = "Настройки (экран Bluetooth, значок шестерёнки)",
                    body = "• Автоподключение и автозапуск мониторинга при соединении.\n" +
                        "• Тема оформления, терминатор команд, режим HEX-отправки.\n" +
                        "• Частота опроса платы — используется для частотных осей FFT и спектрограммы.\n" +
                        "• Чувствительность акселерометра (LSB→g) — делитель для перевода сырых отсчётов " +
                        "в физические g (по умолчанию 1000, настраивается под конкретный датчик).\n" +
                        "• Набор быстрых команд над полем ввода."
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
