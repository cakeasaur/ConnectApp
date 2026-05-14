package com.example.connectapp.utils

import android.content.Context
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Пишет сырой поток с устройства в файл `externalCacheDir/sessions/session_*.txt`
 * для пост-анализа. Поделиться через FileProvider — кнопка в Bluetooth-экране.
 *
 * I/O вынесен на отдельный SupervisorJob + Dispatchers.IO — иначе при потоке
 * 10-100 пакет/сек blocking-write на UI-потоке заметно лагает интерфейс.
 */
class SessionRecorder(private val appContext: Context) {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _currentFile = MutableStateFlow<File?>(null)
    val currentFile: StateFlow<File?> = _currentFile.asStateFlow()

    @Volatile private var writer: BufferedWriter? = null
    private val lock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Открывает файл и переводит recorder в активное состояние. */
    fun start(): File? = synchronized(lock) {
        if (_isRecording.value) return _currentFile.value
        val dir = File(appContext.externalCacheDir ?: appContext.cacheDir, "sessions")
            .apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val file = File(dir, "session_$ts.txt")
        runCatching {
            writer = BufferedWriter(FileWriter(file, true))
            writer?.write("# ConnectApp session recording started ${Date()}\n")
            writer?.flush()
            _currentFile.value = file
            _isRecording.value = true
        }.onFailure {
            writer = null
            return null
        }
        return file
    }

    /**
     * Кладёт чанк в очередь записи. Возвращается мгновенно — запись на IO-потоке.
     * Безопасно вызывать из любого потока. Если recording не активен — no-op.
     */
    fun appendChunk(chunk: String) {
        if (!_isRecording.value) return
        // Захват текущего writer — снимок для IO-корутины. Если до её
        // запуска вызовут stop(), writer уже будет закрыт, и runCatching
        // тихо проглотит IOException.
        val w = writer ?: return
        ioScope.launch {
            synchronized(lock) {
                runCatching {
                    w.write(chunk)
                    w.flush()
                }
            }
        }
    }

    fun stop(): File? = synchronized(lock) {
        if (!_isRecording.value) return null
        runCatching {
            writer?.write("\n# stopped ${Date()}\n")
            writer?.flush()
            writer?.close()
        }
        writer = null
        _isRecording.value = false
        return _currentFile.value
    }

    /** Останавливает scope. Вызывать в onCleared() ViewModel'и. */
    fun shutdown() {
        stop()
        ioScope.cancel()
    }

    /** Возвращает content-uri для шаринга через Intent.EXTRA_STREAM. */
    fun shareUriFor(file: File) = FileProvider.getUriForFile(
        appContext, "${appContext.packageName}.fileprovider", file
    )
}
