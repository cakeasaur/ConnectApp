# ConnectApp 📱

![Android](https://img.shields.io/badge/Android-8.0%2B-green?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-blue?logo=kotlin)
![API](https://img.shields.io/badge/API-26%2B-green)
![License](https://img.shields.io/badge/License-MIT-orange)
![Status](https://img.shields.io/badge/Status-Active-brightgreen)
![MVVM](https://img.shields.io/badge/Architecture-MVVM%20%2B%20Repository-purple)

Android-приложение для **двусторонней связи в реальном времени** с внешними устройствами через **Wi-Fi (TCP)** и **Bluetooth (SPP)**.

---

### 🎯 Обзор

ConnectApp — это Android-приложение на **Kotlin** с архитектурой **MVVM + Repository** и **Coroutines**, позволяющее подключаться к датчикам, Arduino, ESP32 и другим устройствам через **Wi-Fi (TCP)** и **Bluetooth (SPP)**.

**Возможности:**
- ✅ **Wi-Fi (TCP)** для удалённых устройств
- ✅ **Bluetooth (SPP)** для локальных устройств
- ✅ **Реальное время** — получение данных через Flows
- ✅ **Сохранение логов** при повороте экрана (SavedStateHandle)
- ✅ **Toast при потере соединения** + автоматический возврат на меню
- ✅ **Таймаут сокета 30 сек** для надёжности
- ✅ **Нет утечек памяти** (правильная отмена корутин)

---

### 📋 Требования

- **Android:** 8.0+ (API 26)
- **Target:** Android 14 (API 34)
- **Kotlin:** 1.9.22+
- **Gradle:** 8.1.2+
- **Разрешения:** INTERNET, BLUETOOTH_CONNECT, BLUETOOTH_SCAN, ACCESS_FINE_LOCATION

---

### 🚀 Быстрый старт

#### 1. Клонируйте репозиторий
```bash
git clone https://github.com/cakeasaur/ConnectApp.git
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

---

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

---

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
│  Repository (бизнес-логика)          │
│  WifiRepository                      │
│  BluetoothRepository                 │
│  - Управление подключением           │
│  - Входящие/исходящие данные         │
└────────────────┬─────────────────────┘
                 │
┌────────────────▼─────────────────────┐
│  Network (I/O операции)              │
│  WifiClient (TCP Socket)             │
│  BluetoothClient (RFCOMM SPP)        │
│  - Dispatchers.IO для блокирующих    │
│  - Flow<String> для потоков данных   │
└──────────────────────────────────────┘
```

---

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

---

### 💡 Примеры использования

#### Датчик температуры (Wi-Fi)
```kotlin
val host = "192.168.1.100"
val port = 8080
viewModel.connect(host, port)

// Данные приходят в реальном времени:
// TEMP: 25.3°C
// HUMIDITY: 60%
```

#### Bluetooth модуль HC-05
```kotlin
viewModel.startDiscovery()  // Поиск устройств
viewModel.connect(address)  // Выбираем из списка
viewModel.send("GET_DATA")  // Отправляем команду
// DATA: 42.5               // Получаем в реальном времени
```

---

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

---

### 📱 Тестировано на

| Устройство | Протокол | Статус |
|---|---|---|
| ESP32 (Wi-Fi) | TCP | ✅ |
| HC-05 | Bluetooth SPP | ✅ |
| Arduino | TCP | ✅ |
| Второй телефон | Bluetooth SPP | ✅ |
| PC | TCP | ✅ |

---

### 🐛 Решение проблем

**«Bluetooth на эмуляторе не работает»**
- Используйте реальный телефон для Bluetooth
- Wi-Fi работает на эмуляторе: используйте `10.0.2.2`

**«Socket timeout»**
- Проверьте доступность устройства (ping)
- Увеличьте `SOCKET_TIMEOUT_MS` в `Constants.kt`

**«Нет разрешений»**
- Android 12+: дайте разрешения при запросе
- Проверьте `AndroidManifest.xml`

---

### 📄 Лицензия

MIT License — открытый проект

---

## 🌟 Если нравится — поставь звезду! ⭐
