# Stability Fixes: stream corruption, stale alerts, math tests

## Overview

Три реальных бага + покрытие тестами математического модуля.
Нет новых зависимостей, нет рефакторинга вне затронутых файлов.

## Context

- Files involved:
  - `app/src/main/java/com/example/connectapp/network/BluetoothClient.kt`
  - `app/src/main/java/com/example/connectapp/network/WifiClient.kt`
  - `app/src/main/java/com/example/connectapp/data/repository/BluetoothRepository.kt`
  - `app/src/main/java/com/example/connectapp/data/repository/WifiRepository.kt`
  - `app/src/main/java/com/example/connectapp/data/repository/UsbSerialRepository.kt`
  - `app/src/main/java/com/example/connectapp/math/Fft.kt`
  - `app/src/main/java/com/example/connectapp/math/Kalman1D.kt`
  - `app/src/main/java/com/example/connectapp/math/VibrationStats.kt`
  - `app/src/main/java/com/example/connectapp/math/CrossCorrelation.kt`
  - `app/src/main/java/com/example/connectapp/math/Integration.kt`
  - `app/src/main/java/com/example/connectapp/math/HeatFlux.kt`
  - `app/src/main/java/com/example/connectapp/math/Orientation.kt`
  - `app/src/test/java/com/example/connectapp/utils/DataParserTest.kt`
  - `app/src/test/java/com/example/connectapp/math/` (новые файлы)

- Related patterns: существующие тесты в `DataParserTest.kt`, паттерн `callbackFlow` в клиентах
- Dependencies: нет новых

## Development Approach

- Testing approach: code first, then tests (кроме Task 4 — там только тесты)
- Завершать каждую задачу полностью перед переходом к следующей
- Все тесты должны проходить до перехода к следующей задаче
- Тесты запускать через `./gradlew :app:testDebugUnitTest` (JVM, без устройства)

## Implementation Steps

### Task 1: Fix UTF-8 multi-byte corruption in BluetoothClient and WifiClient

**Проблема:** `incoming()` в обоих клиентах вызывает `String(buffer, 0, read, UTF_8)` на каждый
сырой `read()`. Если многобайтовый символ UTF-8 (2–4 байта) разрезан между двумя вызовами
`read()`, строка декодируется с кракозябрами. Это касается устройств, которые шлют Кириллицу
в метках, хотя проблема воспроизводима и с любым не-ASCII.

**Files:**
- Modify: `app/src/main/java/com/example/connectapp/network/BluetoothClient.kt`
- Modify: `app/src/main/java/com/example/connectapp/network/WifiClient.kt`

- [x] В `BluetoothClient.incoming()` заменить прямое `String(buffer, 0, read, UTF_8)` на
      накопление байт в `ByteArrayOutputStream`, разбивать поток по байту `\n` (0x0A),
      декодировать только полные строки. Незавершённый хвост держать в буфере до следующего
      `read()`. Это же гарантирует, что `DataParser.parse()` всегда получает цельную строку.
- [x] Применить тот же подход в `WifiClient.incoming()`.
- [x] Убедиться, что при закрытии сокета накопленный хвост без `\n` всё равно эмитируется
      (на случай устройств, которые не завершают последнюю строку).
- [x] Написать unit-тест `StreamDecoderTest` (без Android зависимостей): подать массив байт,
      в котором двухбайтовый UTF-8 символ разрезан по границе двух чанков — проверить,
      что на выходе строка без кракозябр. Тест можно написать на логике декодирования,
      вынесенной в отдельный pure-Kotlin объект, либо напрямую тестировать конечный автомат.
- [x] Запустить `./gradlew :app:testDebugUnitTest` — все тесты должны пройти.

### Task 2: Fix AlertEngine stale events on reconnect

**Проблема:** `AlertEngine.events` — singleton-буфер, который никогда не очищается между
сессиями. После переподключения к устройству пользователь видит алерты из прошлой сессии.
Метод `clearEvents()` существует, но нигде не вызывается.

**Files:**
- Modify: `app/src/main/java/com/example/connectapp/data/repository/BluetoothRepository.kt`
- Modify: `app/src/main/java/com/example/connectapp/data/repository/WifiRepository.kt`
- Modify: `app/src/main/java/com/example/connectapp/data/repository/UsbSerialRepository.kt`

- [x] В `BluetoothRepository` найти место, где состояние переходит в `ConnectionState.Connected`
      (после успешного `client.connect()`), добавить вызов `AlertEngine.clearEvents()` прямо
      перед установкой `_state.value = ConnectionState.Connected`.
- [x] То же самое в `WifiRepository`.
- [x] То же самое в `UsbSerialRepository`.
- [x] Запустить `./gradlew :app:testDebugUnitTest` — все тесты должны пройти.

### Task 3: Expand DataParser edge case coverage

**Проблема:** существующие тесты не проверяют ряд граничных случаев, которые могут молча
ломаться при изменениях regex.

**Files:**
- Modify: `app/src/test/java/com/example/connectapp/utils/DataParserTest.kt`

- [ ] Добавить тест: строка с только температурой без акселерометра (`"Temp: 25.0 C"`) —
      `accel1X == null`.
- [ ] Добавить тест: `"T=28.5"` (equals вместо colon) корректно парсится.
- [ ] Добавить тест: отрицательные значения акселерометра (`"X: -128 Y: 0 Z: 64"`).
- [ ] Добавить тест: строка с `\r\n` в конце не ломает парсинг (устройства на Windows-стиле).
- [ ] Добавить тест: firmware CSV с отрицательной температурой (`"1;-5.5;-3.2;0,0,0;0,0,0;0;0;"`).
- [ ] Добавить тест: bare-4 с comma-разделителем (`"28.50,254,0,59"`).
- [ ] Запустить `./gradlew :app:testDebugUnitTest` — все тесты должны пройти.

### Task 4: Add unit tests for math module

**Проблема:** `Fft`, `Kalman1D`, `VibrationStats`, `CrossCorrelation`, `Integration`,
`Orientation`, `HeatFlux` — чистый Kotlin без Android зависимостей, но ни одного теста нет.
Любое изменение в математике невозможно верифицировать регрессионно.

**Files:**
- Create: `app/src/test/java/com/example/connectapp/math/FftTest.kt`
- Create: `app/src/test/java/com/example/connectapp/math/Kalman1DTest.kt`
- Create: `app/src/test/java/com/example/connectapp/math/VibrationStatsTest.kt`
- Create: `app/src/test/java/com/example/connectapp/math/CrossCorrelationTest.kt`
- Create: `app/src/test/java/com/example/connectapp/math/IntegrationTest.kt`
- Create: `app/src/test/java/com/example/connectapp/math/OrientationTest.kt`
- Create: `app/src/test/java/com/example/connectapp/math/HeatFluxTest.kt`

- [ ] `FftTest`: подать синусоиду частоты f при sampleRate S, убедиться что пик спектра
      находится на бине f, амплитуда в норме. Подать DC-сигнал (все одинаковые значения),
      убедиться что пик только на нулевом бине. Проверить длину выходного массива = N/2.
- [ ] `Kalman1DTest`: подать два одинаковых измерения — оценка должна быть близка к ним.
      Подать одно точное (малый шум) и одно зашумлённое — оценка должна тяготеть к точному.
      Проверить, что `reset()` сбрасывает состояние (ковариация возвращается к начальной).
- [ ] `VibrationStatsTest`: подать массив нулей — RMS=0, Peak=0, Crest=NaN или 0.
      Подать чистую синусоиду — RMS = amplitude/√2 (погрешность <1%). Проверить Kurtosis:
      для гауссова шума ≈3, для импульсного — >3.
- [ ] `CrossCorrelationTest`: подать два одинаковых сигнала с известным сдвигом N семплов,
      убедиться что `bestLag == N` и корреляция ≈1.0. Подать антифазные — корреляция ≈ -1.0.
- [ ] `IntegrationTest`: подать константный акселератор 1.0 за 1 секунду (10 семплов по 0.1с),
      убедиться что интеграл скорости ≈1.0. Проверить, что detrend убирает DC: на выходе
      нет накапливающегося дрейфа при подаче константного смещения.
- [ ] `OrientationTest`: при `ax=0, ay=0, az=9.81` pitch=0°, roll=0°. При `ax=9.81` pitch≈-90°.
      Magnitude при `(1,0,0)` = 1.0.
- [ ] `HeatFluxTest`: для меди k=400, T1=100, T2=50, d=0.01 — flux = -400*(50-100)/0.01 = 2_000_000.
      Знак: поток идёт от горячего к холодному (направление зависит от реализации — проверить
      что abs(flux) корректен). Проверить все presets на ненулевой результат при T1≠T2.
- [ ] Запустить `./gradlew :app:testDebugUnitTest` — все тесты должны пройти.

### Task 5: Final verification

- [ ] Запустить `./gradlew :app:testDebugUnitTest` — полный прогон всех тестов.
- [ ] Запустить `./gradlew :app:lintDebug` — нет новых предупреждений lint.
- [ ] Убедиться что изменения в клиентах (Task 1) не сломали UsbSerialClient (там другой
      механизм чтения — проверить что он не требует аналогичного фикса или уже корректен).
