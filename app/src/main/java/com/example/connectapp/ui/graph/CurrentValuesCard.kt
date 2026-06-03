package com.example.connectapp.ui.graph

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.connectapp.data.models.TimedPoint
import com.example.connectapp.utils.AlertEngine
import java.util.Locale
import kotlin.math.abs

/**
 * Карточка текущих значений всех каналов. Крупными монospace-цифрами.
 *
 * Если значение превышает порог из [AlertEngine] — подсвечивается красным.
 * Если нет данных — показываем "—".
 *
 * Layout (одна строка на сенсор):
 *   T1: 27.0 °C         T2: 24.1 °C
 *   A1:  ax -480  ay   4  az -989
 *   A2:  ax -471  ay   3  az -992
 */
@Composable
fun CurrentValuesCard(
    temp1: List<TimedPoint>,
    temp2: List<TimedPoint>,
    a1x: List<TimedPoint>,
    a1y: List<TimedPoint>,
    a1z: List<TimedPoint>,
    a2x: List<TimedPoint>,
    a2y: List<TimedPoint>,
    a2z: List<TimedPoint>,
    modifier: Modifier = Modifier,
    accelInG: Boolean = false,
    accelSensitivity: Float = 1000f,
) {
    // Подписываемся на пороги — UI реагирует на их смену даже при паузе
    // потока данных. Без StateFlow getThreshold() возвращал бы старое
    // значение пока следующий пакет данных не вызовет recompose.
    val thresholds by AlertEngine.thresholds.collectAsStateWithLifecycle()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NeonTheme.bg),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "ТЕКУЩЕЕ",
                color = NeonTheme.axisText,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )

            // Температура — 2 канала
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ValueCell(
                    label = "T1",
                    value = temp1.lastOrNull()?.value,
                    unit = "°C",
                    threshold = thresholds["t1"],
                    format = TEMP_FORMAT,
                    modifier = Modifier.weight(1f),
                )
                ValueCell(
                    label = "T2",
                    value = temp2.lastOrNull()?.value,
                    unit = "°C",
                    threshold = thresholds["t2"],
                    format = TEMP_FORMAT,
                    modifier = Modifier.weight(1f),
                )
            }

            // Акселерометр 1 — 3 оси
            AccelRow(
                label = "A1",
                x = a1x.lastOrNull()?.value, xThr = thresholds["ax1"],
                y = a1y.lastOrNull()?.value, yThr = thresholds["ay1"],
                z = a1z.lastOrNull()?.value, zThr = thresholds["az1"],
                inG = accelInG, sens = accelSensitivity,
            )

            // Акселерометр 2 — 3 оси
            AccelRow(
                label = "A2",
                x = a2x.lastOrNull()?.value, xThr = thresholds["ax2"],
                y = a2y.lastOrNull()?.value, yThr = thresholds["ay2"],
                z = a2z.lastOrNull()?.value, zThr = thresholds["az2"],
                inG = accelInG, sens = accelSensitivity,
            )
        }
    }
}

private const val TEMP_FORMAT = "%.1f"
private const val ACCEL_FORMAT = "%.0f"

@Composable
private fun ValueCell(
    label: String,
    value: Float?,
    unit: String,
    threshold: Float?,
    format: String,
    modifier: Modifier = Modifier,
) {
    val exceeded = value != null && threshold != null && value > threshold
    val numberColor = if (exceeded) NeonTheme.alert else NeonTheme.textPrimary

    Column(modifier = modifier) {
        Text(
            label,
            color = NeonTheme.axisText,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (value == null) "—" else format.format(Locale.ROOT, value),
                color = numberColor,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            if (unit.isNotEmpty() && value != null) {
                Text(
                    " $unit",
                    color = NeonTheme.axisText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun AccelRow(
    label: String,
    x: Float?, xThr: Float?,
    y: Float?, yThr: Float?,
    z: Float?, zThr: Float?,
    inG: Boolean,
    sens: Float,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            color = NeonTheme.axisText,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AccelComponent("ax", x, xThr, inG, sens, Modifier.weight(1f))
            AccelComponent("ay", y, yThr, inG, sens, Modifier.weight(1f))
            AccelComponent("az", z, zThr, inG, sens, Modifier.weight(1f))
        }
    }
}

@Composable
private fun AccelComponent(
    label: String,
    value: Float?,
    threshold: Float?,
    inG: Boolean,
    sens: Float,
    modifier: Modifier = Modifier,
) {
    // Превышение — по СЫРОМУ LSB и по модулю (как в AlertEngine: вибрация в обе
    // стороны). Раньше сравнивали знаково (value > threshold) — расходилось с
    // движком алертов и вердиктом. Отображение — в выбранных единицах.
    val exceeded = value != null && threshold != null && abs(value) > threshold
    val numberColor = if (exceeded) NeonTheme.alert else NeonTheme.textPrimary
    val display = if (value == null) null else if (inG && sens > 0f) value / sens else value
    val fmt = if (inG) "%.2f" else ACCEL_FORMAT

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            "$label ",
            color = NeonTheme.axisText,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 3.dp),
        )
        Text(
            if (display == null) "—" else fmt.format(Locale.ROOT, display),
            color = numberColor,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}
