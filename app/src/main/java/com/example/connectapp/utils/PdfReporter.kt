package com.example.connectapp.utils

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.example.connectapp.data.models.TimedPoint
import com.example.connectapp.math.Fft
import com.example.connectapp.math.HeatFlux
import com.example.connectapp.math.Orientation
import com.example.connectapp.math.computeVibrationStats
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Генератор PDF-отчёта по сессии.
 *
 * Использует встроенный android.graphics.pdf.PdfDocument — без зависимостей
 * (iText7 / OpenPDF тянут ~3 МБ jar'ника, для одной страницы overkill).
 *
 * Содержимое:
 *   1. Заголовок с timestamp
 *   2. Статистика по температурам (min/avg/max) и акселерометрам (RMS/Peak/
 *      Crest/Kurtosis), tilt, heat flux, доминирующая частота FFT
 *   3. Простой график температур (линии в координатной сетке)
 *   4. Bar chart FFT-спектра ax1
 *
 * Координаты PDF: 595×842 pt = A4 portrait. (0,0) в левом верхнем углу.
 */
object PdfReporter {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 36f
    private const val SAMPLE_RATE_HZ = 10f

    /**
     * @return [outFile] если PDF создан, null при ошибке IO.
     */
    fun build(
        outFile: File,
        temp1: List<TimedPoint>,
        temp2: List<TimedPoint>,
        a1x: List<TimedPoint>,
        a1y: List<TimedPoint>,
        a1z: List<TimedPoint>,
        a2x: List<TimedPoint>,
        a2y: List<TimedPoint>,
        a2z: List<TimedPoint>
    ): File? {
        val pdf = PdfDocument()
        var success = false
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
            val page = pdf.startPage(pageInfo)
            val canvas = page.canvas

            var y = MARGIN
            y = drawHeader(canvas, y)
            y += 12f
            y = drawStatsBlock(canvas, y, temp1, temp2, a1x, a1y, a1z, a2x, a2y, a2z)
            y += 18f
            y = drawTempChart(canvas, y, temp1, temp2)
            y += 18f
            drawFftBars(canvas, y, a1x)

            pdf.finishPage(page)
            FileOutputStream(outFile).use { pdf.writeTo(it) }
            success = true
        } catch (_: Throwable) {
            return null
        } finally {
            pdf.close()
            // Частично записанный файл — мусор. Удаляем, чтобы FileProvider
            // не отдал битый PDF, и cache не накапливался при повторных фейлах.
            if (!success) runCatching { outFile.delete() }
        }
        return outFile
    }

    // ============================================================
    // header
    // ============================================================

    private fun drawHeader(canvas: Canvas, top: Float): Float {
        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val subPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 10f
            isAntiAlias = true
        }
        canvas.drawText("ConnectApp · отчёт по сессии", MARGIN, top + 16f, titlePaint)
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        canvas.drawText("Сгенерировано: $ts", MARGIN, top + 32f, subPaint)
        // Тонкая линия-разделитель.
        val rulePaint = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f }
        canvas.drawLine(MARGIN, top + 40f, PAGE_WIDTH - MARGIN, top + 40f, rulePaint)
        return top + 42f
    }

    // ============================================================
    // stats block
    // ============================================================

    private fun drawStatsBlock(
        canvas: Canvas,
        top: Float,
        temp1: List<TimedPoint>, temp2: List<TimedPoint>,
        a1x: List<TimedPoint>, a1y: List<TimedPoint>, a1z: List<TimedPoint>,
        a2x: List<TimedPoint>, a2y: List<TimedPoint>, a2z: List<TimedPoint>
    ): Float {
        val h = Paint().apply {
            color = Color.BLACK; textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val p = Paint().apply { color = Color.BLACK; textSize = 10f; isAntiAlias = true }
        val mono = Paint(p).apply { typeface = Typeface.MONOSPACE }

        var y = top
        canvas.drawText("Температура", MARGIN, y, h); y += 14f
        y = drawTempStatsLine(canvas, "T1", temp1, mono, y)
        y = drawTempStatsLine(canvas, "T2", temp2, mono, y)
        y += 8f

        canvas.drawText("Акселерометры (ISO 10816)", MARGIN, y, h); y += 14f
        y = drawAccelStatsLine(canvas, "A1.x", a1x, mono, y)
        y = drawAccelStatsLine(canvas, "A1.y", a1y, mono, y)
        y = drawAccelStatsLine(canvas, "A1.z", a1z, mono, y)
        y = drawAccelStatsLine(canvas, "A2.x", a2x, mono, y)
        y = drawAccelStatsLine(canvas, "A2.y", a2y, mono, y)
        y = drawAccelStatsLine(canvas, "A2.z", a2z, mono, y)
        y += 8f

        canvas.drawText("Производные величины", MARGIN, y, h); y += 14f
        // Tilt — последние значения a1.
        val axV = a1x.lastOrNull()?.value
        val ayV = a1y.lastOrNull()?.value
        val azV = a1z.lastOrNull()?.value
        if (axV != null && ayV != null && azV != null) {
            val pitch = Orientation.pitchDeg(axV, ayV, azV)
            val roll = Orientation.rollDeg(axV, ayV, azV)
            val mag = Orientation.magnitude(axV, ayV, azV)
            canvas.drawText(
                "  Ориентация A1: pitch=%+.1f° · roll=%+.1f° · |a|=%.0f"
                    .format(Locale.ROOT, pitch, roll, mag),
                MARGIN, y, mono
            ); y += 12f
        }
        // Heat flux на медном стержне 5см между T1, T2.
        val t1v = temp1.lastOrNull()?.value
        val t2v = temp2.lastOrNull()?.value
        if (t1v != null && t2v != null) {
            val q = HeatFlux.compute(t1v, t2v, 0.05f, HeatFlux.Conductivity.COPPER)
            canvas.drawText(
                "  Тепловой поток (Cu, 5см): q=%+.0f Вт/м²  ΔT=%.2f °C"
                    .format(Locale.ROOT, q, t2v - t1v),
                MARGIN, y, mono
            ); y += 12f
        }
        // FFT peak ax1.
        if (a1x.size >= 32) {
            val winSize = minOf(a1x.size, 256)
            val arr = FloatArray(winSize)
            val from = a1x.size - winSize
            for (i in 0 until winSize) arr[i] = a1x[from + i].value
            val spectrum = Fft.amplitudeSpectrum(arr)
            var peakBin = 1; var peakAmp = 0f
            for (i in 1 until spectrum.size) {
                if (spectrum[i] > peakAmp) { peakAmp = spectrum[i]; peakBin = i }
            }
            val fftSize = nearestPow2Down(winSize)
            val peakHz = Fft.binHz(peakBin, SAMPLE_RATE_HZ, fftSize)
            canvas.drawText(
                "  FFT a1.x: пик %.2f Гц · амплитуда %.1f (окно %d отсчётов, fs=%.0fГц)"
                    .format(Locale.ROOT, peakHz, peakAmp, fftSize, SAMPLE_RATE_HZ),
                MARGIN, y, mono
            ); y += 12f
        }
        return y
    }

    private fun drawTempStatsLine(canvas: Canvas, label: String, series: List<TimedPoint>, paint: Paint, y: Float): Float {
        val line = if (series.isEmpty()) "  $label: — нет данных —"
        else {
            val vals = series.map { it.value }
            "  %s:  min=%6.2f  avg=%6.2f  max=%6.2f  n=%d"
                .format(Locale.ROOT, label, vals.min(), vals.average(), vals.max(), vals.size)
        }
        canvas.drawText(line, MARGIN, y, paint)
        return y + 12f
    }

    private fun drawAccelStatsLine(canvas: Canvas, label: String, series: List<TimedPoint>, paint: Paint, y: Float): Float {
        val line = if (series.isEmpty()) "  $label: — нет данных —"
        else {
            val arr = FloatArray(series.size) { series[it].value }
            val s = computeVibrationStats(arr)
            "  %s:  RMS=%7.2f  Peak=%7.2f  Crest=%5.2f  Kurt=%5.2f"
                .format(Locale.ROOT, label, s.rms, s.peak, s.crest, s.kurtosis)
        }
        canvas.drawText(line, MARGIN, y, paint)
        return y + 12f
    }

    // ============================================================
    // temperature chart
    // ============================================================

    private fun drawTempChart(
        canvas: Canvas,
        top: Float,
        temp1: List<TimedPoint>,
        temp2: List<TimedPoint>
    ): Float {
        val title = Paint().apply {
            color = Color.BLACK; textSize = 12f; isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Температура (T1 красная, T2 синяя)", MARGIN, top, title)
        val chartTop = top + 6f
        val chartHeight = 130f
        val box = RectF(MARGIN, chartTop, PAGE_WIDTH - MARGIN, chartTop + chartHeight)
        val boxPaint = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 0.5f }
        canvas.drawRect(box, boxPaint)

        val all = (temp1 + temp2).map { it.value }
        if (all.isEmpty()) return box.bottom
        val yMin = all.min(); val yMax = all.max()
        val yPad = ((yMax - yMin) * 0.1f).coerceAtLeast(0.5f)
        drawSeries(canvas, box, temp1, yMin - yPad, yMax + yPad, Color.rgb(0xDC, 0x32, 0x32))
        drawSeries(canvas, box, temp2, yMin - yPad, yMax + yPad, Color.rgb(0x32, 0x64, 0xDC))

        // Подписи Y-min / Y-max.
        val lbl = Paint().apply { color = Color.DKGRAY; textSize = 8f; isAntiAlias = true }
        canvas.drawText("%.1f°C".format(Locale.ROOT, yMax + yPad), box.left + 2f, box.top + 8f, lbl)
        canvas.drawText("%.1f°C".format(Locale.ROOT, yMin - yPad), box.left + 2f, box.bottom - 2f, lbl)
        return box.bottom
    }

    private fun drawSeries(canvas: Canvas, box: RectF, series: List<TimedPoint>, yMin: Float, yMax: Float, lineColor: Int) {
        if (series.size < 2) return
        val paint = Paint().apply { color = lineColor; strokeWidth = 1f; isAntiAlias = true }
        val w = box.width(); val h = box.height()
        val yRange = (yMax - yMin).coerceAtLeast(1e-6f)
        val n = series.size
        var prevX = box.left
        var prevY = box.bottom - (series[0].value - yMin) / yRange * h
        for (i in 1 until n) {
            val x = box.left + w * i / (n - 1).toFloat()
            val y = box.bottom - (series[i].value - yMin) / yRange * h
            canvas.drawLine(prevX, prevY, x, y, paint)
            prevX = x; prevY = y
        }
    }

    // ============================================================
    // FFT bars
    // ============================================================

    private fun drawFftBars(canvas: Canvas, top: Float, series: List<TimedPoint>) {
        val title = Paint().apply {
            color = Color.BLACK; textSize = 12f; isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Спектр FFT — a1.x", MARGIN, top, title)
        val box = RectF(MARGIN, top + 6f, PAGE_WIDTH - MARGIN, top + 6f + 130f)
        val boxPaint = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 0.5f }
        canvas.drawRect(box, boxPaint)
        if (series.size < 32) return
        val winSize = minOf(series.size, 256)
        val arr = FloatArray(winSize)
        val from = series.size - winSize
        for (i in 0 until winSize) arr[i] = series[from + i].value
        val spectrum = Fft.amplitudeSpectrum(arr)
        val maxAmp = (spectrum.maxOrNull() ?: 1f).coerceAtLeast(1e-6f)
        val barW = box.width() / spectrum.size
        val barPaint = Paint().apply { color = Color.rgb(0x42, 0x85, 0xF4); isAntiAlias = true }
        for (i in spectrum.indices) {
            val barH = spectrum[i] / maxAmp * box.height()
            canvas.drawRect(
                box.left + i * barW,
                box.bottom - barH,
                box.left + i * barW + (barW - 0.5f).coerceAtLeast(0.5f),
                box.bottom,
                barPaint
            )
        }
        val lbl = Paint().apply { color = Color.DKGRAY; textSize = 8f; isAntiAlias = true }
        canvas.drawText("0 Hz", box.left + 2f, box.bottom + 10f, lbl)
        canvas.drawText("%.1f Hz".format(Locale.ROOT, SAMPLE_RATE_HZ / 2f), box.right - 38f, box.bottom + 10f, lbl)
    }

    private fun nearestPow2Down(n: Int): Int {
        if (n < 1) return 0
        var p = 1
        while (p * 2 <= n) p = p shl 1
        return p
    }
}
