package com.example.connectapp.ui.graph

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.connectapp.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.OutlinedTextField
import com.example.connectapp.data.models.CommandBus
import com.example.connectapp.data.models.CommandLog
import com.example.connectapp.ui.wifi.LogView
import com.example.connectapp.utils.stripAnsi
import com.example.connectapp.utils.AlertEngine
import com.example.connectapp.data.models.SensorDataBus
import com.example.connectapp.data.models.TimedPoint
import com.example.connectapp.ui.theme.AppThemeWithSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class GraphActivity : ComponentActivity() {

    // Статус долгих операций: null = простой, "..." = текст для пользователя.
    // Показывается LinearProgressIndicator сверху + подпись.
    //
    // Refcount: если запустить 2 операции одновременно (PDF + CSV) — обе
    // вызовут push/pop через withLoading. Индикатор скрывается только когда
    // ВСЕ операции завершились. Иначе finally от первой сбрасывал бы статус
    // пока вторая ещё идёт.
    private val loadingStatus = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val loadingOps = mutableListOf<String>()  // только из Main потока

    private inline fun <T> withLoading(label: String, block: () -> T): T {
        loadingOps.add(label)
        loadingStatus.value = loadingOps.last()
        try {
            return block()
        } finally {
            loadingOps.remove(label)
            loadingStatus.value = loadingOps.lastOrNull()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Источник транспорта зашит в Intent caller'ом (BluetoothActivity,
        // UsbSerialActivity, TestActivity). Если extra отсутствует (например,
        // запуск напрямую через ADB или старый shortcut) — fallback на "bt"
        // для совместимости со старым поведением «всё уходило в BT».
        val transport = intent.getStringExtra(EXTRA_TRANSPORT) ?: DEFAULT_TRANSPORT
        setContent {
            AppThemeWithSettings {
                GraphScreen(
                    onBack = { finish() },
                    onExport = { exportCsv() },
                    onExportPdf = { exportPdf() },
                    onScreenshot = { shareScreenshot() },
                    loadingStatus = loadingStatus,
                    transport = transport,
                )
            }
        }
    }

    companion object {
        /** Ключ Intent extra: транспорт-источник графиков ("bt"/"usb"/"test"). */
        const val EXTRA_TRANSPORT = "transport"
        /** Fallback при отсутствии extra — BT, как было до фикса. */
        const val DEFAULT_TRANSPORT = "bt"
    }

    private fun exportPdf() {
        // Снимок данных из SensorDataBus — синхронно на UI потоке (StateFlow.value
        // дёшево). Сам рендеринг PDF (FFT-256, рисование Canvas, write на диск)
        // — на Dispatchers.IO, иначе ~50-100мс ANR на крупных сериях.
        val temp1 = SensorDataBus.temp1.value
        val temp2 = SensorDataBus.temp2.value
        val a1x = SensorDataBus.accel1X.value
        val a1y = SensorDataBus.accel1Y.value
        val a1z = SensorDataBus.accel1Z.value
        val a2x = SensorDataBus.accel2X.value
        val a2y = SensorDataBus.accel2Y.value
        val a2z = SensorDataBus.accel2Z.value
        if (temp1.isEmpty() && temp2.isEmpty() && a1x.isEmpty() && a2x.isEmpty()) {
            Toast.makeText(this, getString(R.string.graph_no_export_data), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val file = withLoading("Генерация PDF-отчёта...") {
                withContext(Dispatchers.IO) {
                    runCatching {
                        cacheDir.listFiles { f -> f.name.startsWith("sensor_report_") && f.name.endsWith(".pdf") }
                            ?.forEach { it.delete() }
                    }
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
                    val out = File(cacheDir, "sensor_report_$ts.pdf")
                    com.example.connectapp.utils.PdfReporter.build(
                        out, temp1, temp2, a1x, a1y, a1z, a2x, a2y, a2z
                    )
                }
            }
            if (file == null) {
                Toast.makeText(this@GraphActivity, "PDF export failed", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val uri = FileProvider.getUriForFile(this@GraphActivity, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.graph_export_subject))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.graph_export_intent_title)))
        }
    }

    fun shareScreenshot() {
        lifecycleScope.launch {
            withLoading("Создание скриншота...") {
                val view = window.decorView.rootView
                val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(bmp).also { view.draw(it) }
                val file = withContext(Dispatchers.IO) {
                    runCatching {
                        cacheDir.listFiles { f -> f.name == "chart_screenshot.png" }?.forEach { it.delete() }
                    }
                    val f = java.io.File(cacheDir, "chart_screenshot.png")
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
                    f
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(this@GraphActivity, "$packageName.fileprovider", file)
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        getString(R.string.graph_export_intent_title)
                    )
                )
            }
        }
    }

    private fun exportCsv() {
        lifecycleScope.launch {
            withLoading("Экспорт CSV...") {
                val csv = withContext(Dispatchers.IO) { buildCsv() }
                if (csv == null) {
                    Toast.makeText(this@GraphActivity, getString(R.string.graph_no_export_data), Toast.LENGTH_SHORT).show()
                    return@withLoading
                }
                val file = withContext(Dispatchers.IO) {
                    runCatching {
                        cacheDir.listFiles { f -> f.name.startsWith("sensor_data_") && f.name.endsWith(".csv") }
                            ?.forEach { it.delete() }
                    }
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
                    val f = File(cacheDir, "sensor_data_$timestamp.csv")
                    f.writeText(csv)
                    f
                }
                val uri = FileProvider.getUriForFile(this@GraphActivity, "$packageName.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.graph_export_subject))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.graph_export_intent_title)))
            }
        }
    }

    private fun buildCsv(): String? {
        val series = listOf(
            "T1" to SensorDataBus.temp1.value,
            "T2" to SensorDataBus.temp2.value,
            "A1X" to SensorDataBus.accel1X.value,
            "A1Y" to SensorDataBus.accel1Y.value,
            "A1Z" to SensorDataBus.accel1Z.value,
            "A2X" to SensorDataBus.accel2X.value,
            "A2Y" to SensorDataBus.accel2Y.value,
            "A2Z" to SensorDataBus.accel2Z.value
        )
        val allTs = series.flatMap { it.second.map { p -> p.t } }.toSortedSet()
        if (allTs.isEmpty()) return null
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ROOT)
        val maps = series.map { (_, list) -> list.associateBy { it.t } }
        return buildString {
            append("Timestamp,"); append(series.joinToString(",") { it.first }); append('\n')
            for (t in allTs) {
                append(iso.format(Date(t))); append(',')
                for ((i, _) in series.withIndex()) {
                    val v = maps[i][t]?.value
                    if (v != null) append(String.format(Locale.ROOT, "%.3f", v))
                    if (i != series.lastIndex) append(',')
                }
                append('\n')
            }
        }
    }
}

@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
private fun GraphScreen(
    onBack: () -> Unit,
    onExport: () -> Unit,
    onExportPdf: () -> Unit,
    onScreenshot: () -> Unit,
    loadingStatus: kotlinx.coroutines.flow.StateFlow<String?>,
    transport: String,
) {
    // Частота опроса из настроек — используется в FFT/STFT для подписей оси
    // частот и расчёта периодов. Меняется в SettingsDialog → DataStore.
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsRepo = remember { com.example.connectapp.data.settings.SettingsRepository(context) }
    val appSettings by settingsRepo.flow.collectAsStateWithLifecycle(
        initialValue = com.example.connectapp.data.settings.AppSettings.DEFAULT
    )
    val sampleRateHz = appSettings.sampleRateHz

    // Live-данные с шины. Дальше прогоняем через snapshotWhen(paused) и
    // applyWindow(window) — фильтры применяются и к графикам, и к MathSection.
    val temp1Live by SensorDataBus.temp1.collectAsStateWithLifecycle()
    val temp2Live by SensorDataBus.temp2.collectAsStateWithLifecycle()
    val a1xLive by SensorDataBus.accel1X.collectAsStateWithLifecycle()
    val a1yLive by SensorDataBus.accel1Y.collectAsStateWithLifecycle()
    val a1zLive by SensorDataBus.accel1Z.collectAsStateWithLifecycle()
    val a2xLive by SensorDataBus.accel2X.collectAsStateWithLifecycle()
    val a2yLive by SensorDataBus.accel2Y.collectAsStateWithLifecycle()
    val a2zLive by SensorDataBus.accel2Z.collectAsStateWithLifecycle()
    val dynIds by SensorDataBus.dynamicIds.collectAsStateWithLifecycle()

    // «Идёт ли поток» определяем по факту прихода данных, а не локальным флагом:
    // если последний отсчёт свежее 3 с — стрим активен. Кнопка честно отражает
    // состояние, даже когда мониторинг запущен с BT-экрана. Тикер раз в секунду
    // нужен, чтобы статус «протух» после остановки потока, когда новых данных нет.
    var nowTick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            nowTick = System.currentTimeMillis()
            delay(1000)
        }
    }
    val lastDataAt = maxOf(
        temp1Live.lastOrNull()?.t ?: 0L, temp2Live.lastOrNull()?.t ?: 0L,
        a1xLive.lastOrNull()?.t ?: 0L, a1yLive.lastOrNull()?.t ?: 0L, a1zLive.lastOrNull()?.t ?: 0L,
        a2xLive.lastOrNull()?.t ?: 0L, a2yLive.lastOrNull()?.t ?: 0L, a2zLive.lastOrNull()?.t ?: 0L,
    )
    val monitoring = lastDataAt > 0L && nowTick - lastDataAt < 3000L
    var paused by remember { mutableStateOf(false) }
    // windowMs: 0L = "все" (без фильтра), иначе — длина окна в мс.
    // Заменили enum TimeWindow на непрерывное значение + zoom-кнопки —
    // дискретный выбор 30с/1м/5м/все был ограничен 4 пресетами.
    var windowMs by remember { mutableLongStateOf(0L) }
    // Overlays для NeonChart — научный режим: envelope / ±1σ / threshold /
    // phase-lock (автоподгонка окна к 2 периодам доминирующей частоты).
    var showEnvelope by remember { mutableStateOf(false) }
    var showSigma by remember { mutableStateOf(false) }
    var showThreshold by remember { mutableStateOf(false) }
    var absoluteTime by rememberSaveable { mutableStateOf(false) }
    var accelInG by rememberSaveable { mutableStateOf(false) }
    var showPeaks by rememberSaveable { mutableStateOf(false) }
    var phaseLock by remember { mutableStateOf(false) }
    // Видимость осей акселерометров — пользователь сам решает что показывать.
    // По умолчанию все включены. rememberSaveable: переживают rotation/конфиг.
    // Advanced — скрывает FFT/STFT/Lissajous/Phosphor/Kalman/Velocity до галочки.
    // Базовый набор (RMS/Tilt/HeatFlux) остаётся всегда.
    var advancedMath by rememberSaveable { mutableStateOf(false) }
    var showAx1 by rememberSaveable { mutableStateOf(true) }
    var showAy1 by rememberSaveable { mutableStateOf(true) }
    var showAz1 by rememberSaveable { mutableStateOf(true) }
    var showAx2 by rememberSaveable { mutableStateOf(true) }
    var showAy2 by rememberSaveable { mutableStateOf(true) }
    var showAz2 by rememberSaveable { mutableStateOf(true) }
    // Shared crosshair-state — тап на любом из 3 line charts двигает линию
    // во ВСЕХ. Объект (не StateFlow) держит Long? state без боксинга.
    val crosshair = remember { CrosshairBus() }
    val zoom = remember { ZoomBus() }
    var showAlertDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // 1) Freeze: при paused = true возвращаем снимок, снятый в момент перехода.
    val temp1Snap = snapshotWhen(paused, temp1Live)
    val temp2Snap = snapshotWhen(paused, temp2Live)
    val a1xSnap = snapshotWhen(paused, a1xLive)
    val a1ySnap = snapshotWhen(paused, a1yLive)
    val a1zSnap = snapshotWhen(paused, a1zLive)
    val a2xSnap = snapshotWhen(paused, a2xLive)
    val a2ySnap = snapshotWhen(paused, a2yLive)
    val a2zSnap = snapshotWhen(paused, a2zLive)

    // 2) Window: показываем только хвост длиной windowMs (0 = всё).
    //    remember с ключом (snap, windowMs) — не пересчитываем если данные те же.
    val temp1 = remember(temp1Snap, windowMs) { applyWindow(temp1Snap, windowMs) }
    val temp2 = remember(temp2Snap, windowMs) { applyWindow(temp2Snap, windowMs) }
    val a1x = remember(a1xSnap, windowMs) { applyWindow(a1xSnap, windowMs) }
    val a1y = remember(a1ySnap, windowMs) { applyWindow(a1ySnap, windowMs) }
    val a1z = remember(a1zSnap, windowMs) { applyWindow(a1zSnap, windowMs) }
    val a2x = remember(a2xSnap, windowMs) { applyWindow(a2xSnap, windowMs) }
    val a2y = remember(a2ySnap, windowMs) { applyWindow(a2ySnap, windowMs) }
    val a2z = remember(a2zSnap, windowMs) { applyWindow(a2zSnap, windowMs) }

    // 3) g-режим: отображаемые версии accel (деление на чувствительность).
    //    Только для графиков/статов/карточки; MathSection и 3D берут сырые LSB
    //    (спектр и облако единиц не показывают, конверсия там бессмысленна).
    val accelSens = appSettings.accelSensitivityLsbPerG
    val accelUnit = if (accelInG) "g" else "LSB"
    val a1xD = remember(a1x, accelInG, accelSens) { convAccel(a1x, accelInG, accelSens) }
    val a1yD = remember(a1y, accelInG, accelSens) { convAccel(a1y, accelInG, accelSens) }
    val a1zD = remember(a1z, accelInG, accelSens) { convAccel(a1z, accelInG, accelSens) }
    val a2xD = remember(a2x, accelInG, accelSens) { convAccel(a2x, accelInG, accelSens) }
    val a2yD = remember(a2y, accelInG, accelSens) { convAccel(a2y, accelInG, accelSens) }
    val a2zD = remember(a2z, accelInG, accelSens) { convAccel(a2z, accelInG, accelSens) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.graph_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                },
                actions = {
                    // Cursor mode toggle: тап → переключить single/dual,
                    // long-press → clear обоих курсоров.
                    // Используем Box + combinedClickable, потому что IconButton
                    // с onClick КОНФЛИКТУЕТ с pointerInput { detectTapGestures } —
                    // последний поглощает тапы и onClick не срабатывает.
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .combinedClickable(
                                onClick = { crosshair.dualMode = !crosshair.dualMode },
                                onLongClick = { crosshair.clear() }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Straighten,
                            contentDescription = if (crosshair.dualMode) "dual cursor" else "single cursor",
                            tint = if (crosshair.dualMode) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    // Clear cursors — появляется только когда курсор активен.
                    if (crosshair.selectedT != null || crosshair.secondT != null) {
                        IconButton(onClick = { crosshair.clear() }) {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = "Clear cursors",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // Сброс pinch-zoom — появляется только когда активен зум.
                    if (zoom.isZoomed) {
                        IconButton(onClick = { zoom.reset() }) {
                            Icon(
                                Icons.Filled.ZoomOutMap,
                                contentDescription = "Сбросить зум",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    // Pause — частое действие, оставляем видимым.
                    IconButton(onClick = { paused = !paused }) {
                        Icon(
                            Icons.Filled.AcUnit,
                            contentDescription = stringResource(
                                if (paused) R.string.graph_resume else R.string.graph_pause
                            ),
                            tint = if (paused) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    // Остальное — в overflow-меню, чтобы заголовок не зажимался.
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Ещё")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Журнал событий (RRD)") },
                                leadingIcon = { Icon(Icons.Filled.Storage, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    context.startActivity(
                                        android.content.Intent(context, com.example.connectapp.ui.rrd.RrdEventLogActivity::class.java)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Журнал аномалий") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    context.startActivity(
                                        android.content.Intent(context, com.example.connectapp.ui.anomaly.AnomalyLogActivity::class.java)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Настроить алерты") },
                                leadingIcon = { Icon(Icons.Filled.NotificationsActive, contentDescription = null) },
                                onClick = { menuOpen = false; showAlertDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.graph_export_pdf)) },
                                leadingIcon = { Icon(Icons.Filled.PictureAsPdf, contentDescription = null) },
                                onClick = { menuOpen = false; onExportPdf() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.graph_export_csv)) },
                                leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                                onClick = { menuOpen = false; onExport() }
                            )
                            DropdownMenuItem(
                                text = { Text("Скриншот") },
                                leadingIcon = { Icon(Icons.Filled.CameraAlt, contentDescription = null) },
                                onClick = { menuOpen = false; onScreenshot() }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Индикатор долгих операций (PDF/CSV/Screenshot) — анимированно
            // появляется/исчезает. Показывает текст текущего шага под прогрессом.
            val currentLoad by loadingStatus.collectAsStateWithLifecycle()
            androidx.compose.animation.AnimatedVisibility(
                visible = currentLoad != null,
                enter = androidx.compose.animation.expandVertically() +
                    androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() +
                    androidx.compose.animation.fadeOut(),
            ) {
                Column {
                    androidx.compose.material3.LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        currentLoad.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val cmdStart = stringResource(R.string.cmd_one)
            val cmdCalib = stringResource(R.string.cmd_two)
            val cmdQuick = stringResource(R.string.cmd_three)
            val cmdDump = stringResource(R.string.cmd_log_dump)
            val scope = rememberCoroutineScope()
            // Основное действие — monitor/stop — на всю ширину сверху, акцентом.
            // Три служебные команды — равным рядом снизу. Раньше все 4 жались в
            // один ряд и «monitor» переносился по буквам вертикально.
            Button(
                onClick = {
                    // Старт — словом `monitor`; стоп — ESC (этой плате поток
                    // обрывает только ESC, повторная команда не останавливает).
                    if (monitoring) CommandBus.send("\u001B", transport)
                    else CommandBus.send(cmdStart, transport)
                    // Состояние кнопки не трогаем — оно производное от потока данных.
                },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(
                    imageVector = if (monitoring) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (monitoring) "Стоп" else "Мониторинг",
                    maxLines = 1,
                    softWrap = false
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { CommandBus.send(cmdCalib, transport) },
                    modifier = Modifier.weight(1f)
                ) { Text("calib", maxLines = 1, softWrap = false) }
                FilledTonalButton(
                    onClick = { CommandBus.send(cmdQuick, transport) },
                    modifier = Modifier.weight(1f)
                ) { Text("test", maxLines = 1, softWrap = false) }
                FilledTonalButton(
                    onClick = {
                        // ESC прерывает мониторинг → ждём промпт → шлём `log dump`.
                        scope.launch {
                            CommandBus.send("\u001B", transport)
                            CommandLog.append("→ log dump")
                            delay(300)
                            CommandBus.send(cmdDump, transport)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("dump", maxLines = 1, softWrap = false) }
            }

            // Zoom-bar — непрерывный контроль окна вместо 4 пресетов.
            // IconButton 48×48 дают комфортные touch-targets.
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.graph_window_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = {
                        // Zoom out: если "все" → переходим в фиксированное 5мин,
                        // иначе ×1.5, кап 60 минут.
                        windowMs = when {
                            windowMs == 0L -> 5L * 60_000L
                            else -> (windowMs * 3L / 2L).coerceAtMost(60L * 60_000L)
                        }
                    }
                ) {
                    Icon(Icons.Filled.ZoomOut, contentDescription = "Уменьшить масштаб")
                }
                // Текущее окно в человеко-читаемом виде.
                Text(
                    formatWindow(windowMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 4.dp).widthIn(min = 56.dp)
                )
                IconButton(
                    onClick = {
                        // Zoom in: если "все" → начинаем с 1 минуты, иначе /1.5.
                        windowMs = when {
                            windowMs == 0L -> 60_000L
                            else -> (windowMs * 2L / 3L).coerceAtLeast(5_000L)
                        }
                    }
                ) {
                    Icon(Icons.Filled.ZoomIn, contentDescription = "Увеличить масштаб")
                }
                IconButton(onClick = { windowMs = 0L }) {
                    Icon(
                        Icons.Filled.AllInclusive,
                        contentDescription = "Показать всё",
                        tint = if (windowMs == 0L) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Overlays-row: envelope / ±1σ / threshold-alert / phase-lock / advanced.
            // Применяются ко ВСЕМ NeonChart'ам единообразно — научный режим.
            // FlowRow вместо обычного Row: 5 чипов на узком экране не помещаются
            // в одну строку и последний (advanced) уезжал за правый край —
            // пользователь его просто не видел. FlowRow переносит на новую.
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "overlays:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                val chipColors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
                FilterChip(
                    selected = showEnvelope,
                    onClick = { showEnvelope = !showEnvelope },
                    label = { Text("envelope") },
                    colors = chipColors
                )
                FilterChip(
                    selected = showSigma,
                    onClick = { showSigma = !showSigma },
                    label = { Text("±σ") },
                    colors = chipColors
                )
                FilterChip(
                    selected = showThreshold,
                    onClick = { showThreshold = !showThreshold },
                    label = { Text("⚠ alert") },
                    colors = chipColors
                )
                FilterChip(
                    selected = absoluteTime,
                    onClick = { absoluteTime = !absoluteTime },
                    label = { Text(if (absoluteTime) "время" else "время отн.") },
                    colors = chipColors
                )
                FilterChip(
                    selected = accelInG,
                    onClick = { accelInG = !accelInG },
                    label = { Text(if (accelInG) "ед: g" else "ед: LSB") },
                    colors = chipColors
                )
                FilterChip(
                    selected = showPeaks,
                    onClick = { showPeaks = !showPeaks },
                    label = { Text("пики") },
                    colors = chipColors
                )
                FilterChip(
                    selected = phaseLock,
                    onClick = { phaseLock = !phaseLock },
                    label = { Text("phase-lock") },
                    colors = chipColors
                )
                FilterChip(
                    selected = advancedMath,
                    onClick = { advancedMath = !advancedMath },
                    label = { Text("advanced") },
                    colors = chipColors
                )
            }

            // Neon-палитра — насыщенные неоновые цвета поверх тёмного фона
            // карточки. Не путать с цветами серий в Vico (FF3232 / 3264DC):
            // здесь они подобраны под dark BG для максимальной читаемости.
            val tempColors = listOf(Color(0xFFFF6B6B), Color(0xFF4FC3F7))
            val accelColors = listOf(Color(0xFFFF5252), Color(0xFF69F0AE), Color(0xFF40C4FF))

            // Конфиги per chart. Thresholds — тематические:
            //   T > 28°C → перегрев
            //   az отклонение от 1g (1000) > 100 LSB → сильная вибрация
            val tempConfig = NeonChartConfig(
                showEnvelope = showEnvelope,
                showSigma = showSigma,
                thresholds = if (showThreshold) listOf(
                    NeonThreshold(28f, "overheat", Color(0xFFFFAA00))
                ) else emptyList(),
                phaseLock = phaseLock,
                absoluteTime = absoluteTime,
                showPeaks = showPeaks,
            )
            val vibThreshold = if (accelInG && accelSens > 0f) 1100f / accelSens else 1100f
            val accelConfig = NeonChartConfig(
                showEnvelope = showEnvelope,
                showSigma = showSigma,
                thresholds = if (showThreshold) listOf(
                    NeonThreshold(vibThreshold, "vibration", Color(0xFFFF8800), NeonAxis.RIGHT)
                ) else emptyList(),
                phaseLock = phaseLock,
                absoluteTime = absoluteTime,
                showPeaks = showPeaks,
            )

            // Сводный вердикт по порогам AlertEngine — человекочитаемый статус
            // вместо чтения сырых линий. Источник порогов тот же, что красит
            // числа в карточке ниже, так что баннер и цифры согласованы.
            HealthBanner(
                temp1 = temp1, temp2 = temp2,
                a1x = a1x, a1y = a1y, a1z = a1z,
                a2x = a2x, a2y = a2y, a2z = a2z,
            )

            // Текущие значения всех каналов крупными цифрами — сразу видно состояние
            // без необходимости искать crosshair или смотреть на графики. Красным,
            // если значение превышает настроенный в AlertEngine порог.
            CurrentValuesCard(
                temp1 = temp1, temp2 = temp2,
                a1x = a1x, a1y = a1y, a1z = a1z,
                a2x = a2x, a2y = a2y, a2z = a2z,
                accelInG = accelInG, accelSensitivity = accelSens,
            )

            Text(stringResource(R.string.label_temperature_unit), style = MaterialTheme.typography.titleLarge)
            StatsRow("T1", temp1, "°C")
            StatsRow("T2", temp2, "°C")
            ChartCard(height = 220) {
                NeonChart(
                    seriesList = listOf(
                        NeonSeries(temp1, tempColors[0], "T1"),
                        NeonSeries(temp2, tempColors[1], "T2"),
                    ),
                    config = tempConfig,
                    zoom = zoom,
                    crosshair = crosshair,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Text("${stringResource(R.string.label_accelerometer)} 1 · $accelUnit", style = MaterialTheme.typography.titleLarge)
            AxisFilterRow(
                showX = showAx1, onShowXChange = { showAx1 = it },
                showY = showAy1, onShowYChange = { showAy1 = it },
                showZ = showAz1, onShowZChange = { showAz1 = it },
            )
            if (showAx1) StatsRow("ax", a1xD, accelUnit)
            if (showAy1) StatsRow("ay", a1yD, accelUnit)
            if (showAz1) StatsRow("az", a1zD, accelUnit)
            ChartCard(height = 220) {
                // Multi-axis: az → правая ось (диапазон ~900-1100 от гравитации),
                // ax/ay → левая (±50). Без этого ax/ay сплющены в линию.
                // Фильтруем по чекбоксам — если ось выключена, серия не добавляется.
                val a1Series = buildList {
                    if (showAx1) add(NeonSeries(a1xD, accelColors[0], "ax", NeonAxis.LEFT))
                    if (showAy1) add(NeonSeries(a1yD, accelColors[1], "ay", NeonAxis.LEFT))
                    if (showAz1) add(NeonSeries(a1zD, accelColors[2], "az", NeonAxis.RIGHT))
                }
                NeonChart(
                    seriesList = a1Series,
                    config = accelConfig,
                    zoom = zoom,
                    crosshair = crosshair,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Text("${stringResource(R.string.label_accelerometer)} 2 · $accelUnit", style = MaterialTheme.typography.titleLarge)
            AxisFilterRow(
                showX = showAx2, onShowXChange = { showAx2 = it },
                showY = showAy2, onShowYChange = { showAy2 = it },
                showZ = showAz2, onShowZChange = { showAz2 = it },
            )
            if (showAx2) StatsRow("ax", a2xD, accelUnit)
            if (showAy2) StatsRow("ay", a2yD, accelUnit)
            if (showAz2) StatsRow("az", a2zD, accelUnit)
            ChartCard(height = 220) {
                val a2Series = buildList {
                    if (showAx2) add(NeonSeries(a2xD, accelColors[0], "ax", NeonAxis.LEFT))
                    if (showAy2) add(NeonSeries(a2yD, accelColors[1], "ay", NeonAxis.LEFT))
                    if (showAz2) add(NeonSeries(a2zD, accelColors[2], "az", NeonAxis.RIGHT))
                }
                NeonChart(
                    seriesList = a2Series,
                    config = accelConfig,
                    zoom = zoom,
                    crosshair = crosshair,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Динамические каналы — авто-детект произвольных меток с платы
            // (voltage, rpm, pressure…). Появляются сами, по одному графику на канал.
            if (dynIds.isNotEmpty()) {
                Text("Дополнительные каналы", style = MaterialTheme.typography.titleLarge)
                dynIds.forEach { id ->
                    key(id) {
                        val live by (SensorDataBus.dynamicFlow(id) ?: emptyFlow).collectAsStateWithLifecycle()
                        val pts = remember(live, windowMs) { applyWindow(live, windowMs) }
                        Text(id, style = MaterialTheme.typography.titleMedium)
                        if (pts.isNotEmpty()) StatsRow(id, pts, "")
                        ChartCard(height = 180) {
                            NeonChart(
                                seriesList = listOf(NeonSeries(pts, dynColor(id), id)),
                                config = NeonChartConfig(
                                    absoluteTime = absoluteTime,
                                    showPeaks = showPeaks,
                                ),
                                zoom = zoom,
                                crosshair = crosshair,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }

            Text("3D облако акселерометров", style = MaterialTheme.typography.titleLarge)
            Text(
                "A1 красным, A2 синим. Перетаскивай — вращай, pinch — зум.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ChartCard(height = 320) {
                Accel3DChart(
                    a1 = AccelTriple(a1x, a1y, a1z),
                    a2 = AccelTriple(a2x, a2y, a2z),
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Раздел "Математический анализ" получает те же отфильтрованные
            // данные что и графики выше — pause/window применяются единообразно.
            // generation = ключ для stateful-математики (Kalman): инкрементится
            // в SensorDataBus.clear(), на новом значении сбрасываем накопленный
            // state, иначе Kalman продолжит "помнить" удалённые отсчёты.
            val generation by SensorDataBus.generation.collectAsStateWithLifecycle()
            MathSection(
                t1 = temp1, t2 = temp2,
                a1x = a1x, a1y = a1y, a1z = a1z,
                a2x = a2x, a2y = a2y,
                generation = generation,
                advanced = advancedMath,
                sampleRateHz = sampleRateHz,
            )
        }
            // Встроенная консоль внизу: лог текстовых ответов платы + ввод команд.
            CommandConsole(transport = transport)
        } // outer Column (loading indicator wrapper)
    }

    if (showAlertDialog) {
        AlertSettingsDialog(onDismiss = { showAlertDialog = false })
    }
}

/**
 * Встроенная консоль внизу экрана графиков: лог текстовых ответов платы
 * ([CommandLog]) + поле ввода команды. Отправка идёт через [CommandBus] на
 * активный транспорт; отправленная команда эхо-логируется как "→ cmd".
 */
@Composable
private fun CommandConsole(transport: String) {
    val log by CommandLog.text.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }

    fun send() {
        val cmd = input.trim()
        if (cmd.isEmpty()) return
        CommandBus.send(cmd, transport)
        CommandLog.append("→ $cmd")
        input = ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    RoundedCornerShape(8.dp)
                )
                .padding(6.dp)
        ) {
            if (log.isEmpty()) {
                Text(
                    "Лог команд платы (help / меню / статус)…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LogView(log = stripAnsi(log), modifier = Modifier.fillMaxSize(), autoScroll = true)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                label = { Text("команда плате") },
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { send() }, enabled = input.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
            }
        }
    }
}

/**
 * Чипы выбора видимых осей акселерометра (ax/ay/az).
 * Тап по чипу — toggle отдельной оси без затрагивания других.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AxisFilterRow(
    showX: Boolean, onShowXChange: (Boolean) -> Unit,
    showY: Boolean, onShowYChange: (Boolean) -> Unit,
    showZ: Boolean, onShowZChange: (Boolean) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        FilterChip(
            selected = showX,
            onClick = { onShowXChange(!showX) },
            label = { Text("ax", style = MaterialTheme.typography.labelMedium) }
        )
        FilterChip(
            selected = showY,
            onClick = { onShowYChange(!showY) },
            label = { Text("ay", style = MaterialTheme.typography.labelMedium) }
        )
        FilterChip(
            selected = showZ,
            onClick = { onShowZChange(!showZ) },
            label = { Text("az", style = MaterialTheme.typography.labelMedium) }
        )
    }
}

/**
 * Сводный статус «здоровья» платы по порогам [AlertEngine]. Темп — знаковое
 * сравнение (перегрев вверх), accel — по модулю (вибрация в обе стороны), как
 * в [AlertEngine.check]. Если порог канала не задан — он не участвует в вердикте.
 */
@Composable
private fun HealthBanner(
    temp1: List<TimedPoint>, temp2: List<TimedPoint>,
    a1x: List<TimedPoint>, a1y: List<TimedPoint>, a1z: List<TimedPoint>,
    a2x: List<TimedPoint>, a2y: List<TimedPoint>, a2z: List<TimedPoint>,
) {
    val thresholds by AlertEngine.thresholds.collectAsStateWithLifecycle()

    fun over(key: String, pts: List<TimedPoint>, accel: Boolean): Boolean {
        val thr = thresholds[key] ?: return false
        val v = pts.lastOrNull()?.value ?: return false
        return (if (accel) abs(v) else v) > thr
    }

    val overheat = over("t1", temp1, false) || over("t2", temp2, false)
    val vibration = over("ax1", a1x, true) || over("ay1", a1y, true) || over("az1", a1z, true) ||
        over("ax2", a2x, true) || over("ay2", a2y, true) || over("az2", a2z, true)

    val (text, bg) = when {
        // Без единого порога судить не по чему — нейтральный статус, а не
        // ложно-зелёная «НОРМА» (иначе пустая конфигурация выглядит как
        // «проверено, всё хорошо»).
        thresholds.isEmpty() -> "ПОРОГИ НЕ ЗАДАНЫ" to Color(0xFF607D8B)
        overheat && vibration -> "ПЕРЕГРЕВ + ВИБРАЦИЯ" to Color(0xFFD32F2F)
        overheat -> "ПЕРЕГРЕВ" to Color(0xFFD32F2F)
        vibration -> "ПОВЫШЕННАЯ ВИБРАЦИЯ" to Color(0xFFF57C00)
        else -> "НОРМА" to Color(0xFF2E7D32)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg),
    ) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

/** LSB→g для отображения: делит value на чувствительность. on=false → как есть. */
private fun convAccel(points: List<TimedPoint>, on: Boolean, sens: Float): List<TimedPoint> =
    if (on && sens > 0f) points.map { it.copy(value = it.value / sens) } else points

/** Пустой поток-заглушка для динамического канала, который ещё не успел появиться. */
private val emptyFlow = kotlinx.coroutines.flow.MutableStateFlow<List<TimedPoint>>(emptyList())

private val dynPalette = listOf(
    Color(0xFFFFD54F), Color(0xFF4DD0E1), Color(0xFFBA68C8),
    Color(0xFF81C784), Color(0xFFFF8A65), Color(0xFF7986CB),
)

/** Стабильный цвет динамического канала по его id. */
private fun dynColor(id: String) = dynPalette[(id.hashCode() and 0x7fffffff) % dynPalette.size]

@Composable
private fun StatsRow(label: String, points: List<TimedPoint>, unit: String) {
    if (points.isEmpty()) return
    val values = points.map { it.value }
    val mn = values.min(); val mx = values.max(); val avg = values.average()
    Text(
        "$label: min %.2f / avg %.2f / max %.2f %s".format(Locale.ROOT, mn, avg, mx, unit),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ChartCard(height: Int = 220, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(height.dp)
    ) {
        Box(Modifier.padding(8.dp)) { content() }
    }
}

