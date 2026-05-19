package com.example.connectapp.math

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FftTest {

    @Test
    fun `sine wave peak is at correct bin`() {
        val n = 256
        val sampleRate = 256f
        val freq = 4f
        val input = FloatArray(n) { i -> sin(2.0 * PI * freq * i / sampleRate).toFloat() }

        val spectrum = Fft.amplitudeSpectrum(input)

        assertEquals(n / 2, spectrum.size)
        val expectedBin = (freq * n / sampleRate).toInt()
        val peakBin = spectrum.indices.maxByOrNull { spectrum[it] }!!
        assertEquals(expectedBin, peakBin)
    }

    @Test
    fun `dc signal peak is at bin zero`() {
        val n = 256
        val input = FloatArray(n) { 2f }

        val spectrum = Fft.amplitudeSpectrum(input)

        val peakBin = spectrum.indices.maxByOrNull { spectrum[it] }!!
        assertEquals(0, peakBin)
    }

    @Test
    fun `output length is half of input length`() {
        val n = 512
        val spectrum = Fft.amplitudeSpectrum(FloatArray(n))
        assertEquals(n / 2, spectrum.size)
    }

    @Test
    fun `sine amplitude is approximately correct after hann correction`() {
        val n = 256
        val sampleRate = 256f
        val freq = 8f
        val amplitude = 2f
        val input = FloatArray(n) { i -> amplitude * sin(2.0 * PI * freq * i / sampleRate).toFloat() }

        val spectrum = Fft.amplitudeSpectrum(input)
        val expectedBin = (freq * n / sampleRate).toInt()
        val peakAmplitude = spectrum[expectedBin]

        assertEquals(amplitude, peakAmplitude, amplitude * 0.15f)
    }
}
