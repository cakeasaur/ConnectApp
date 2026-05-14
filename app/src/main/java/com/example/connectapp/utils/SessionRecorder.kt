package com.example.connectapp.utils

import android.content.Context
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Пишет сырой поток с устройства в файл `Documents/ConnectApp/session_*.txt`
 * для пост-анализа. Запускается/останавливается флагом, потокобезопасен.
 *
 * Файл хранится в externalCacheDir (доступен через FileProvider для шаринга).
 */
class SessionRecorder(private val appContext: Context) {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _currentFile = MutableStateFlow<File?>(null)
    val currentFile: StateFlow<File?> = _currentFile.asStateFlow()

    @Volatile private var writer: BufferedWriter? = null
    private val lock = Any()

    fun start(): File? = synchronized(lock) {
        if (_isRecording.value) return _currentFile.value
        val dir = File(appContext.externalCacheDir ?: appContext.cacheDir, "sessions").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val file = File(dir, "session_$ts.txt")
        runCatching {
            writer = BufferedWriter(FileWriter(file, true))
            writer?.write("# ConnectApp session recording started ${Date()}\n")
            _currentFile.value = file
            _isRecording.value = true
        }.onFailure {
            writer = null
            return null
        }
        return file
    }

    fun appendChunk(chunk: String) {
        if (!_isRecording.value) return
        synchronized(lock) {
            runCatching {
                writer?.write(chunk)
                writer?.flush()
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
        val finished = _currentFile.value
        return finished
    }

    /** Возвращает content-uri последнего файла для Intent.EXTRA_STREAM. */
    fun shareUriFor(file: File) = FileProvider.getUriForFile(
        appContext, "${appContext.packageName}.fileprovider", file
    )
}
