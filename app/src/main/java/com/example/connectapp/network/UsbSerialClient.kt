package com.example.connectapp.network

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/**
 * USB serial клиент поверх библиотеки usb-serial-for-android (3.7+).
 * Поддерживает CP210x (Silicon Labs), CDC (Arduino), FTDI, CH34x, Prolific.
 *
 * Семантика та же что у [BluetoothClient] / [WifiClient]:
 *   connect(...) → incoming(): Flow<String> → send(...) → close()
 */
class UsbSerialClient {

    @Volatile private var port: UsbSerialPort? = null
    @Volatile private var connection: UsbDeviceConnection? = null
    private val sendMutex = Mutex()

    /** Список найденных USB-устройств (драйверы зашиты в библиотеке). */
    fun probe(context: Context): List<UsbSerialDriver> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return UsbSerialProber.getDefaultProber().findAllDrivers(manager)
    }

    /**
     * Открыть порт первого устройства указанного [device] с настройками
     * по умолчанию (115200 8N1) — стандарт для HC-05/PIC24FJ128GB106.
     *
     * Caller должен УЖЕ получить разрешение через UsbManager.requestPermission
     * (см. UsbSerialRepository). Иначе openDevice вернёт null.
     */
    suspend fun connect(
        context: Context,
        device: UsbDevice,
        baudRate: Int = 115200
    ) = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: throw IllegalStateException("Драйвер для ${device.deviceName} не найден")
        val conn = manager.openDevice(device)
            ?: throw IllegalStateException("Нет разрешения на USB-устройство")
        val p = driver.ports.firstOrNull()
            ?: throw IllegalStateException("Устройство без serial-портов")
        try {
            p.open(conn)
            p.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (t: Throwable) {
            runCatching { p.close() }
            runCatching { conn.close() }
            throw t
        }
        port = p
        connection = conn
    }

    /**
     * Поток входящих данных. Блокирующий read() с 200мс timeout — иначе
     * cancel() корутины может застревать в нативном вызове.
     */
    fun incoming(): Flow<String> = callbackFlow {
        val p = port ?: throw IllegalStateException("USB port не открыт")
        val buffer = ByteArray(2048)
        try {
            while (true) {
                val read = p.read(buffer, 200)
                if (read > 0) {
                    trySend(String(buffer, 0, read, StandardCharsets.UTF_8))
                }
                // read == 0 → таймаут, продолжаем. read < 0 → ошибка ниже в exception.
            }
        } catch (_: Throwable) {
            // I/O ошибка или порт закрыт — выходим, awaitClose почистит.
        } finally {
            close()
        }
        awaitClose { /* ресурсы освободятся в [close]. */ }
    }.flowOn(Dispatchers.IO)

    suspend fun send(payload: String) = withContext(Dispatchers.IO) {
        sendBytes(payload.toByteArray(StandardCharsets.UTF_8))
    }

    suspend fun sendBytes(bytes: ByteArray) = withContext(Dispatchers.IO) {
        sendMutex.withLock {
            val p = port ?: throw IllegalStateException("USB port не открыт")
            // 500мс write-timeout достаточно для типичного 115200 baud.
            p.write(bytes, 500)
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { port?.close() }
        runCatching { connection?.close() }
        port = null
        connection = null
    }
}
