# ConnectApp 📱

![Android](https://img.shields.io/badge/Android-8.0%2B-green?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-blue?logo=kotlin)
![API](https://img.shields.io/badge/API-26%2B-green)
![License](https://img.shields.io/badge/License-MIT-orange)
![MVVM](https://img.shields.io/badge/Architecture-MVVM%20%2B%20Repository-purple)
![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose)

Android-приложение для **двусторонней связи в реальном времени** с внешними устройствами через **Wi-Fi (TCP)**, **Bluetooth (SPP)** и **USB Serial**, с визуализацией данных датчиков на графиках.

---

### 🎯 Обзор

ConnectApp — приложение на **Kotlin + Jetpack Compose** с архитектурой **MVVM + Repository** и **Coroutines/Flow**, позволяющее подключаться к датчикам, Arduino, ESP32, HC-05 и другим устройствам через **Wi-Fi (TCP)** и **Bluetooth (SPP)**.

**Возможности:**
- ✅ **Wi-Fi (TCP)** для удалённых устройств — keep-alive, авто-реконнект, разрыв по EOF
- ✅ **Bluetooth (SPP)** для локальных устройств с авто-реконнектом
- ✅ **USB Serial** через USB-OTG (FTDI, CP210x, CH340 и другие)
- ✅ **Реальное время** — приём через `Flow<String>` и `callbackFlow`
- ✅ **Сохранение логов** при повороте экрана (`SavedStateHandle`)
- ✅ **Toast при потере соединения**; возврат на меню — на терминальной ошибке (Wi-Fi реконнектит)
- ✅ **Графики** — кастомный Canvas-чарт (две оси, glow, FFT/спектрограмма/3D); вид графика
  переключается per-card (линейный/сглаженный/площадной/столбчатый/точки)
- ✅ **Экспорт CSV/PDF** из меню графиков (через `FileProvider`)
- ✅ **Лог с временными метками** и фиксированным окном (без OOM)

---

### 📋 Требования

| | |
|---|---|
| **min SDK** | 26 (Android 8.0) |
| **target / compile SDK** | 35 (Android 15) |
| **Kotlin** | 1.9.24 |
| **AGP** | 8.5.2 |
| **Gradle** | 8.9 |
| **JDK** | 17 |
| **Разрешения** | `INTERNET`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (`neverForLocation`), USB permission (динамически) |

---

### 🚀 Быстрый старт

```bash
git clone https://github.com/cakeasaur/ConnectApp.git
cd ConnectApp
./gradlew assembleDebug
```

Откройте проект в **Android Studio** (Iguana или новее) → дождитесь синхронизации Gradle → **Run** ▶ на эмуляторе/устройстве с Android 8+.

---

### 🧪 Тестирование

#### Wi-Fi (TCP) — самый простой способ

**На компьютере (Python echo-сервер):**
```python
import socket
s = socket.socket()
s.bind(("0.0.0.0", 9000))
s.listen(1)
print("Сервер слушает :9000")
while True:
    c, _ = s.accept()
    while True:
        data = c.recv(1024)
        if not data: break
        c.sendall(b"ECHO: " + data)
```

**В приложении:**
1. **Wi-Fi (TCP)** → IP: `10.0.2.2` (хост из эмулятора), Порт: `9000`
2. **Подключить** → Введите сообщение → Получите ECHO в логе

#### Bluetooth (SPP) — нужен реальный телефон

1. Установите **Serial Bluetooth Terminal** на втором устройстве (режим сервера).
2. **Bluetooth (SPP)** → **Поиск** → Выберите устройство → Сообщения идут двусторонне.

#### Юнит-тесты

```bash
./gradlew testDebugUnitTest
```

Покрытие: `DataParser`, `LineDecoder` (UTF-8 split, CRLF), `Fft`, `Kalman1D`, `VibrationStats`, `CrossCorrelation`, `Integration`, `Orientation`, `HeatFlux`.

При каждом успешном подключении (Bluetooth, Wi-Fi, USB Serial) буфер алертов очищается автоматически — после реконнекта не отображаются алерты предыдущей сессии.

---

### 🏗️ Архитектура

```
┌──────────────────────────────────────┐
│  UI (Jetpack Compose)                │
│  MainActivity                        │
│  ├─ WifiActivity   + WifiViewModel   │
│  ├─ BluetoothActivity + BluetoothVM  │
│  ├─ UsbSerialActivity + UsbSerialVM  │
│  ├─ GraphActivity (custom Canvas)    │
│  └─ Onboarding/History/RRD/MQTT      │
└────────────────┬─────────────────────┘
                 │
┌────────────────▼─────────────────────┐
│  ViewModel (управление состоянием)   │
│  · SavedStateHandle (host, port, log)│
│  · StateFlow<ConnectionState>        │
│  · SharedFlow<String> incoming       │
│  · LogBuffer (StringBuilder, debounce)│
└────────────────┬─────────────────────┘
                 │
┌────────────────▼─────────────────────┐
│  Repository (бизнес-логика)          │
│  · WifiRepository (авто-реконнект)   │
│  · BluetoothRepository (авто-реконн.)│
│  · UsbSerialRepository               │
│  · отмена корутин (cancelAndJoin)    │
└────────────────┬─────────────────────┘
                 │
┌────────────────▼─────────────────────┐
│  Network (I/O)                       │
│  · WifiClient (TCP Socket)           │
│  · BluetoothClient (RFCOMM SPP)      │
│  · UsbSerialClient (USB-OTG)         │
│  · Dispatchers.IO + Mutex            │
└──────────────────────────────────────┘
```

Поверх транспортов — `MqttBridge` (ретрансляция в Home Assistant/Grafana).

Глобальные шины:
- `SensorDataBus` — публикует разобранные значения для `GraphActivity`
- `CommandBus` — `GraphActivity` → активная VM (отправка команд)
- `RrdLog` — авто-детект и парсинг журнала событий платы (`log dump`)

---

### 💾 Структура проекта

```
app/src/main/
├── AndroidManifest.xml        — разрешения + backup-rules
├── java/com/example/connectapp/
│   ├── ui/                    — Compose Activities + ViewModels
│   │   ├── MainActivity
│   │   ├── wifi/
│   │   ├── bluetooth/
│   │   └── graph/
│   ├── data/
│   │   ├── models/            — ConnectionState, SensorData, шины
│   │   └── repository/        — Wifi/Bluetooth Repository
│   ├── network/               — Wifi/Bluetooth Client (raw I/O)
│   └── utils/                 — Constants, DataParser, LogBuffer, PermissionHelper
└── res/
    ├── values/strings.xml     — все UI-строки
    └── xml/                   — file_paths, backup_rules, data_extraction_rules

app/src/test/                  — юнит-тесты (DataParser)
.github/workflows/android.yml  — CI: test → lint → assembleDebug
```

---

### 🔧 Технические детали

**Coroutines:**
- `Dispatchers.IO` для сокетов
- `callbackFlow` для потоков данных
- `StateFlow` / `SharedFlow` для UI без утечек
- `cancelAndJoin` после `client.close()` — безопасное освобождение
- `NonCancellable` в `finally` — гарантированный close сокета

**Bluetooth SPP UUID:**
```
00001101-0000-1000-8000-00805F9B34FB
```

**Таймауты и лимиты** (`Constants.kt`):

| Параметр | Значение |
|---|---|
| Connect timeout | 10 000 ms |
| Read timeout (SO_TIMEOUT) | 0 (∞; разрыв по EOF/IOException) |
| Discovery timeout | 12 000 ms |
| Reconnect delay | 3 000 ms |
| Reconnect attempts (без данных) | 4, затем ошибка |
| Read buffer | 1024 bytes |
| Лог в памяти | 30 000 chars (FIFO) |
| Точек на графике | 60 (FIFO) |
| Кодировка | UTF-8 |

**Безопасность:**
- `BLUETOOTH_SCAN` с `neverForLocation` — без `ACCESS_FINE_LOCATION` на API 31+
- `data_extraction_rules.xml` + `backup_rules.xml` — host/port/логи не утекают в Auto Backup
- R8 + ProGuard включены для `release`-сборки

---

### 📱 Совместимость

| Устройство | Протокол | Статус |
|---|---|---|
| ESP32 | Wi-Fi TCP | ✅ |
| Arduino + Wi-Fi-shield | TCP | ✅ |
| HC-05 / HC-06 | Bluetooth SPP | ✅ |
| Серийный BT-терминал | Bluetooth SPP | ✅ |
| ПК (Python `socket`) | TCP | ✅ |

---

### 🐛 Решение проблем

**«Bluetooth на эмуляторе не работает»** — используйте реальный телефон. Эмулятор Android Studio Bluetooth не виртуализирует.

**«Socket timeout»** — проверьте `ping host`, увеличьте `SOCKET_TIMEOUT_MS` в [Constants.kt](app/src/main/java/com/example/connectapp/utils/Constants.kt).

**«Нет разрешений на скан»** — Android 12+: дайте `BLUETOOTH_SCAN` и `BLUETOOTH_CONNECT` при первом запросе. Если отказали — Settings → Apps → ConnectApp → Permissions.

**«CSV открывается криво в Excel»** — экспорт всегда в `Locale.ROOT` (точка как разделитель). В Excel: «Импорт текста» → разделитель `,`.

---

### 🤝 Вклад

Pull request'ы приветствуются. Перед PR:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

---

### 📄 Лицензия

MIT License — открытый проект.

---

⭐ Если приложение помогло — поставьте звезду на GitHub!
