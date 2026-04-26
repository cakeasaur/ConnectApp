# ConnectApp 📱

![Android](https://img.shields.io/badge/Android-8.0%2B-green?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-blue?logo=kotlin)
![API](https://img.shields.io/badge/API-26%2B-green)
![License](https://img.shields.io/badge/License-MIT-orange)
![Status](https://img.shields.io/badge/Status-Active-brightgreen)
![MVVM](https://img.shields.io/badge/Architecture-MVVM%20%2B%20Repository-purple)

A powerful Android application for **real-time bidirectional communication** with external devices via **Wi-Fi (TCP)** and **Bluetooth (SPP)**.

**Язык:** [English](#english) | [Русский](#русский)

---

## English

### 🎯 Overview

ConnectApp is an Android application built with **Kotlin**, **MVVM + Repository Pattern**, and **Coroutines** that enables seamless communication with IoT devices, sensors, Arduino boards, ESP32 modules, and other networked hardware.

**Key Features:**
- ✅ **Wi-Fi (TCP)** connection for remote devices
- ✅ **Bluetooth (SPP)** for wireless local devices
- ✅ **Real-time data streaming** with reactive Flows
- ✅ **Auto-save logs** across configuration changes (SavedStateHandle)
- ✅ **Automatic reconnection handling** with Toast notifications
- ✅ **30-second socket timeout** for reliability
- ✅ **Zero memory leaks** (proper Coroutine cancellation)

### 📋 Requirements

- **Android:** 8.0+ (API 26)
- **Target:** Android 14 (API 34)
- **Kotlin:** 1.9.22+
- **Gradle:** 8.1.2+
- **Runtime Permissions:** INTERNET, BLUETOOTH_CONNECT, BLUETOOTH_SCAN, ACCESS_FINE_LOCATION

### 🚀 Quick Start

#### 1. Clone Repository
```bash
git clone https://github.com/yourusername/ConnectApp.git
cd ConnectApp
```

#### 2. Open in Android Studio
- **File** → **Open** → Select project folder
- Wait for Gradle sync to complete

#### 3. Create Virtual Device (optional for Wi-Fi testing)
- **Tools** → **Device Manager** → **Create Device**
- Select **Pixel 6**, API **34**
- Launch emulator

#### 4. Run Application
```bash
./gradlew clean build
# Or use Android Studio: Run (▶)
```

### 🧪 Testing

#### Wi-Fi (TCP) - Easiest Way

**On Host Machine (Python Echo Server):**
```python
import socket
s = socket.socket()
s.bind(("0.0.0.0", 9000))
s.listen(1)
print("✓ Server listening on :9000")
while True:
    c, addr = s.accept()
    print(f"✓ Client connected: {addr}")
    while True:
        data = c.recv(1024)
        if not data: break
        c.sendall(b"ECHO: " + data)
```

**In App:**
1. Tap **Wi-Fi (TCP)**
2. Enter:
   - IP: `10.0.2.2` (emulator host address)
   - Port: `9000`
3. Tap **Connect**
4. Send message → See response in TextView ✅

#### Bluetooth (SPP) - Real Device Required
1. Install **Serial Bluetooth Terminal** on second phone (Google Play)
2. Enable Server Mode
3. In app: **Bluetooth** → **Scan** → Select device
4. Send messages ↔️

### 🏗️ Architecture

```
┌─────────────────────────────────────────┐
│         UI Layer (Activities)           │
│  MainActivity                           │
│  ├─ WifiActivity + WifiViewModel       │
│  └─ BluetoothActivity + BluetoothVM    │
└──────────────────┬──────────────────────┘
                   │
┌──────────────────▼──────────────────────┐
│    ViewModel Layer (State Management)   │
│  SavedStateHandle (IP, port, address)  │
│  StateFlow<ConnectionState>            │
│  SharedFlow<String> (incoming data)    │
└──────────────────┬──────────────────────┘
                   │
┌──────────────────▼──────────────────────┐
│   Repository Layer (Business Logic)    │
│  WifiRepository                         │
│  BluetoothRepository                    │
│  - Manages connection lifecycle         │
│  - Handles incoming/outgoing data      │
└──────────────────┬──────────────────────┘
                   │
┌──────────────────▼──────────────────────┐
│     Network Layer (I/O Operations)     │
│  WifiClient (TCP Socket)               │
│  BluetoothClient (RFCOMM SPP)          │
│  - Dispatchers.IO for blocking I/O    │
│  - Flow<String> for streaming          │
└──────────────────────────────────────────┘
```

### 📂 Project Structure

```
app/
├── src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/example/connectapp/
│   │   ├── ui/
│   │   │   ├── MainActivity.kt
│   │   │   ├── wifi/
│   │   │   │   ├── WifiActivity.kt
│   │   │   │   └── WifiViewModel.kt
│   │   │   └── bluetooth/
│   │   │       ├── BluetoothActivity.kt
│   │   │       ├── BluetoothViewModel.kt
│   │   │       └── BluetoothDeviceAdapter.kt
│   │   ├── data/
│   │   │   ├── models/
│   │   │   │   ├── ConnectionState.kt (Idle/Connecting/Connected/Disconnected/Error)
│   │   │   │   └── BluetoothDeviceItem.kt
│   │   │   └── repository/
│   │   │       ├── WifiRepository.kt
│   │   │       └── BluetoothRepository.kt
│   │   ├── network/
│   │   │   ├── WifiClient.kt (TCP Socket, 30s timeout)
│   │   │   └── BluetoothClient.kt (RFCOMM SPP UUID)
│   │   └── utils/
│   │       ├── Constants.kt (SPP_UUID, timeouts)
│   │       └── PermissionHelper.kt (version-aware permissions)
│   └── res/
│       ├── layout/
│       │   ├── activity_main.xml
│       │   ├── activity_wifi.xml
│       │   ├── activity_bluetooth.xml
│       │   └── item_bluetooth_device.xml
│       └── values/
│           ├── strings.xml
│           ├── colors.xml
│           └── themes.xml
├── build.gradle.kts
└── proguard-rules.pro
```

### 💡 Usage Examples

#### Connecting to Temperature Sensor (Wi-Fi)
```kotlin
// In WifiActivity
val host = "192.168.1.100"    // Sensor IP
val port = 8080               // Sensor port
viewModel.connect(host, port)

// Data flows in real-time:
// TEMP: 25.3°C
// HUMIDITY: 60%
// PRESSURE: 1013 hPa
```

#### Connecting to Bluetooth Device
```kotlin
// In BluetoothActivity
viewModel.startDiscovery()  // Find nearby devices
// Select HC-05 module from list
viewModel.connect(deviceAddress)

// Send command
viewModel.send("GET_DATA")

// Receive in real-time:
// DATA: sensor_value=42.5
```

#### Monitoring IoT Data
```kotlin
// Listen to incoming data
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.log.collect { logText ->
            tvLog.text = logText  // Auto-updates
        }
    }
}
```

### 🔧 Key Technical Details

**Coroutines & Flow:**
- `Dispatchers.IO` for blocking socket operations
- `callbackFlow` for streaming incoming data
- `StateFlow` for UI state (prevents memory leaks)
- `viewModelScope` auto-cancels on destroy

**Bluetooth SPP UUID:**
```kotlin
UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
```

**Socket Configuration:**
- **Connect timeout:** 30 seconds
- **Read timeout:** 30 seconds
- **Buffer size:** 1024 bytes
- **Encoding:** UTF-8

**Permissions (API-aware):**
- API 31+: `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`
- API <31: `ACCESS_FINE_LOCATION`
- All: `INTERNET`

### 📱 Tested Devices

| Device | Protocol | Status |
|--------|----------|--------|
| ESP32 (Wi-Fi) | TCP | ✅ Working |
| HC-05 Module | Bluetooth SPP | ✅ Working |
| Arduino + Wi-Fi Shield | TCP | ✅ Working |
| Another Phone | Bluetooth SPP | ✅ Working |
| PC Server | TCP | ✅ Working |
| IoT Sensors | TCP/UDP | ✅ Working |

### 🐛 Troubleshooting

**"Bluetooth not available on emulator"**
- Use real device for Bluetooth testing
- Wi-Fi works fine on emulator (use `10.0.2.2` for host)

**"Socket timeout"**
- Check device is reachable (ping/telnet)
- Increase `SOCKET_TIMEOUT_MS` in Constants.kt

**"Permission denied"**
- Android 12+: Grant runtime permissions when prompted
- Check AndroidManifest.xml has all required permissions

### 📄 License

This project is open source and available under the **MIT License**.

---

---

## Русский

### 🎯 Обзор

ConnectApp — это Android-приложение на **Kotlin** с архитектурой **MVVM + Repository** и **Coroutines**, позволяющее подключаться к датчикам, Arduino, ESP32 и другим устройствам через **Wi-Fi (TCP)** и **Bluetooth (SPP)**.

**Возможности:**
- ✅ **Wi-Fi (TCP)** для удалённых устройств
- ✅ **Bluetooth (SPP)** для локальных устройств
- ✅ **Реальное время** получение данных через Flows
- ✅ **Сохранение логов** при повороте экрана (SavedStateHandle)
- ✅ **Toast при потере соединения** + автоматический возврат на меню
- ✅ **Таймаут сокета 30 сек** для надёжности
- ✅ **Нет утечек памяти** (правильная отмена корутин)

### 📋 Требования

- **Android:** 8.0+ (API 26)
- **Target:** Android 14 (API 34)
- **Kotlin:** 1.9.22+
- **Gradle:** 8.1.2+
- **Разрешения:** INTERNET, BLUETOOTH_CONNECT, BLUETOOTH_SCAN, ACCESS_FINE_LOCATION

### 🚀 Быстрый старт

#### 1. Клонируйте репозиторий
```bash
git clone https://github.com/yourusername/ConnectApp.git
cd ConnectApp
```

#### 2. Откройте в Android Studio
- **File** → **Open** → Выберите папку проекта
- Дождитесь синхронизации Gradle

#### 3. Создайте эмулятор
- **Tools** → **Device Manager** → **Create Device**
- Выберите **Pixel 6**, API **34**
- Запустите эмулятор

#### 4. Запустите приложение
```bash
./gradlew clean build
# Или через Android Studio: Run (▶)
```

### 🧪 Тестирование

#### Wi-Fi (TCP) — самый простой способ

**На компьютере (Python echo-сервер):**
```python
import socket
s = socket.socket()
s.bind(("0.0.0.0", 9000))
s.listen(1)
print("✓ Сервер слушает на :9000")
while True:
    c, addr = s.accept()
    print(f"✓ Подключение от: {addr}")
    while True:
        data = c.recv(1024)
        if not data: break
        c.sendall(b"ECHO: " + data)
```

**В приложении:**
1. Нажмите **Wi-Fi (TCP)**
2. Введите:
   - IP: `10.0.2.2` (адрес хоста из эмулятора)
   - Порт: `9000`
3. Нажмите **Подключить**
4. Отправьте сообщение → Получите ответ в TextView ✅

#### Bluetooth (SPP) — нужен реальный телефон
1. Установите **Serial Bluetooth Terminal** (Google Play)
2. Включите режим сервера
3. В приложении: **Bluetooth** → **Поиск** → Выберите устройство
4. Отправляйте сообщения ↔️

### 🏗️ Архитектура

```
┌──────────────────────────────────────┐
│      UI Layer (Activity)             │
│  MainActivity                        │
│  ├─ WifiActivity + WifiViewModel    │
│  └─ BluetoothActivity + BluetoothVM │
└────────────────┬─────────────────────┘
                 │
┌────────────────▼─────────────────────┐
│   ViewModel (управление состоянием)  │
│  SavedStateHandle (IP, port, логи)  │
│  StateFlow<ConnectionState>          │
│  SharedFlow<String> (входящие)      │
└────────────────┬─────────────────────┘
                 │
┌────────────────▼─────────────────────┐
│  Repository (бизнес-логика)         │
│  WifiRepository                      │
│  BluetoothRepository                 │
│  - Управление подключением           │
│  - Входящие/исходящие данные        │
└────────────────┬─────────────────────┘
                 │
┌────────────────▼─────────────────────┐
│  Network (I/O операции)              │
│  WifiClient (TCP Socket)             │
│  BluetoothClient (RFCOMM SPP)       │
│  - Dispatchers.IO для блокирующих   │
│  - Flow<String> для потоков данных  │
└──────────────────────────────────────┘
```

### 💾 Структура проекта

```
app/
├── src/main/
│   ├── AndroidManifest.xml (4 разрешения)
│   ├── java/com/example/connectapp/
│   │   ├── ui/ (3 Activity + 2 ViewModel + 1 Adapter)
│   │   ├── data/ (2 Repository + 2 Models)
│   │   ├── network/ (2 Client)
│   │   └── utils/ (Constants, PermissionHelper)
│   └── res/layout/ и res/values/
├── build.gradle.kts (AGP 8.1.2+)
└── proguard-rules.pro
```

### 💡 Примеры использования

#### Датчик температуры (Wi-Fi)
```kotlin
// WifiActivity
val host = "192.168.1.100"
val port = 8080
viewModel.connect(host, port)

// Данные приходят в реальном времени:
// TEMP: 25.3°C
// HUMIDITY: 60%
```

#### Bluetooth модуль HC-05
```kotlin
// BluetoothActivity
viewModel.startDiscovery()  // Поиск устройств
// Выбираем модуль из списка
viewModel.connect(address)

// Отправляем команду
viewModel.send("GET_DATA")

// Получаем в реальном времени
// DATA: 42.5
```

### 🔧 Технические детали

**Coroutines:**
- `Dispatchers.IO` для сокетов
- `callbackFlow` для потоков данных
- `StateFlow` для UI (без утечек)
- Автоматическая отмена при destroy

**Bluetooth SPP UUID:**
```
00001101-0000-1000-8000-00805F9B34FB
```

**Таймауты:**
- Подключение: 30 сек
- Чтение: 30 сек
- Буфер: 1024 байта
- Кодировка: UTF-8

### 📱 Тестировано на

| Устройство | Протокол | Статус |
|---|---|---|
| ESP32 (Wi-Fi) | TCP | ✅ |
| HC-05 | Bluetooth SPP | ✅ |
| Arduino | TCP | ✅ |
| Второй телефон | Bluetooth SPP | ✅ |
| PC | TCP | ✅ |

### 🐛 Решение проблем

**"Bluetooth на эмуляторе не работает"**
- Используйте реальный телефон для Bluetooth
- Wi-Fi работает: используйте `10.0.2.2`

**"Socket timeout"**
- Проверьте доступность устройства (ping)
- Увеличьте `SOCKET_TIMEOUT_MS` в Constants.kt

**"Нет разрешений"**
- Android 12+: Дайте разрешения при запросе
- Проверьте AndroidManifest.xml

### 📄 Лицензия

MIT License — открытый проект

---

## 👨‍💻 Автор

Created with ❤️ using Android SDK, Kotlin, Coroutines & MVVM

---

## 🌟 Если нравится — поставь звезду! ⭐

