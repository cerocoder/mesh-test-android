# Архитектура Meshtastic-Android v2.8.0

## 1. Общая характеристика

К версии 2.8.0 проект — уже **не «Android-приложение», а Kotlin Multiplatform-продукт**:
один общий код на `commonMain`, три хоста (`androidApp`, `desktopApp`, заготовка iOS).

| Аспект | Решение |
| :--- | :--- |
| Язык / платформа | Kotlin 2.4.10, KMP (android + jvm/desktop + iosArm64-заготовка) |
| UI | Compose Multiplatform 1.11.1 + Material 3, Navigation 3 (`@Serializable sealed interface` роуты) |
| DI | Koin Annotations 4.2.2 через **K2 compiler plugin** (не KSP): `@Module`, `@ComponentScan`, `@Single`, `@KoinViewModel` |
| Асинхронность | kotlinx.coroutines, `atomicfu`, `Mutex`; свой `CoroutineDispatchers` из `core:di` (прямые `Dispatchers.IO` запрещены) |
| БД | Room KMP, **по одной БД на устройство** (`DatabaseManager.switchActiveDatabase(address)`) |
| Настройки | DataStore (multiplatform), слой `core:prefs` |
| Сеть | Ktor 3.5.1 (без OkHttp), MQTT-абстракция |
| I/O | Okio (`BufferedSource`/`BufferedSink`), без `java.io` |
| BLE | **Kable 0.44.3** (`com.juul.kable`) — кроссплатформенный BLE |
| Протокол | protobuf из внешней Maven-зависимости `org.meshtastic:protobufs` (генерённый код не правится руками) |
| Логи | Kermit (`co.touchlab.kermit.Logger`) |
| Сборка | Gradle Kotlin DSL + convention-плагины в `build-logic` (`meshtastic.kmp.library`, `meshtastic.kmp.feature`, `meshtastic.koin`), JDK 25, minSdk 26, targetSdk 36 |
| Флейворы | `fdroid` (только OSS) и `google` (Maps + DataDog) |

## 2. Модульная структура

Из `settings.gradle.kts` — 40+ Gradle-модулей, разделённых на `core:*` и `feature:*`.

### core (инфраструктура и домен)

| Модуль | Роль |
| :--- | :--- |
| `core:model` | Доменные модели и общие типы (`Node`, `ConnectionState`, `DeviceType`, `InterfaceId`) |
| `core:common` | Низкоуровневые утилиты, Okio-I/O, `safeCatching`, `ioDispatcher`, `anonymize` |
| `core:di` | Квалификаторы DI и `CoroutineDispatchers` |
| `core:repository` | **Только интерфейсы**: `RadioInterfaceService`, `RadioTransport`, `RadioController`, `ServiceRepository`, `NodeRepository`, `MeshConnectionManager`, … |
| `core:data` | Реализации менеджеров: `MeshConnectionManagerImpl`, обработчики пакетов, NodeDB |
| `core:database` / `core:datastore` / `core:prefs` | Room, DataStore, типизированные префы |
| `core:network` | Ktor-клиенты, MQTT **и все транспорты радио** (`BleRadioTransport`, `TcpRadioTransport`, `SerialRadioTransport`, `MockRadioTransport`, `ReplayRadioTransport`, `StreamFrameCodec`) |
| **`core:ble`** | **BLE-стек на Kable**: сканер, соединение, бондинг, радио-профиль Meshtastic |
| `core:service` | Сервисный слой: `SharedRadioInterfaceService`, `MeshServiceOrchestrator`, Android-`MeshService` |
| `core:domain` | Чистые UseCase'ы |
| `core:ui`, `core:navigation`, `core:resources` | Общие Compose-компоненты, роуты, строки/иконки |
| `core:takserver`, `core:nfc`, `core:barcode` | Мост в ATAK/iTAK, NFC, сканер QR |
| `core:testing`, `core:konsist` | Тест-даблы; архитектурные тесты (Konsist) |

### feature (экраны)

`intro`, **`connections`**, `messaging`, `map`, `node`, `settings`, `discovery`, `docs`,
`firmware` (OTA/DFU), `wifi-provision`, `car` (Android Auto), `widget` (Glance).

Для нашей задачи важен **`feature:connections`** — экран выбора устройства.

### Хосты

`androidApp` (MainActivity, `AppKoinModule`, `MeshUtilApplication`) и `desktopApp` — тонкие оболочки:
собирают Koin-граф и запускают общий UI.

## 3. Ключевой архитектурный приём: инверсия зависимостей через `core:repository`

Интерфейсы объявлены в `core:repository`, реализации — в `core:data`, `core:service`, `core:network`.
Модули зависят от абстракций, а не друг от друга. Платформенная специфика подключается двумя способами
(в порядке предпочтения проекта):

1. **Интерфейс + DI** (основной): `BluetoothRepository` в `commonMain` → `AndroidBluetoothRepository` в `androidMain`,
   биндится Koin'ом.
2. **`expect`/`actual`** (только для мелких примитивов): `KablePlatformSetup.kt` — настройка MTU/priority/autoConnect.

## 4. Слои подключения к ноде — сверху вниз

```
┌─────────────────────────────────────────────────────────────────────────┐
│ UI: feature:connections (Compose)                                        │
│   ConnectionsScreen → ScannerViewModel / AndroidScannerViewModel         │
│   • список устройств, скан, bonding, выбор                              │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │ radioController.setDeviceAddress("x" + MAC)
┌───────────────────────────────▼─────────────────────────────────────────┐
│ Прикладной слой: RadioControllerImpl + MeshConnectionManagerImpl         │
│   • переключение БД устройства, очистка NodeDB                          │
│   • handshake (want_config_id), watchdog, состояние ConnectionState      │
│   • ServiceRepository.connectionState ← КАНОНИЧЕСКОЕ состояние для UI    │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │
┌───────────────────────────────▼─────────────────────────────────────────┐
│ Сервисный слой: SharedRadioInterfaceService (core:service)               │
│   • владеет ЕДИНСТВЕННЫМ активным RadioTransport                        │
│   • сессии (generation), приём кадров через Channel (строгий FIFO)       │
│   • heartbeat 30 c, liveness-детектор «зомби»-соединения                 │
│   • transport-level ConnectionState (НЕ для UI)                          │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │ RadioTransportFactory.createTransport(address)
┌───────────────────────────────▼─────────────────────────────────────────┐
│ Транспортный слой: RadioTransport (core:network)                         │
│   BleRadioTransport │ TcpRadioTransport │ SerialRadioTransport │ Mock    │
│   • жизненный цикл соединения, реконнект, ретраи                        │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │
┌───────────────────────────────▼─────────────────────────────────────────┐
│ BLE-абстракции: core:ble                                                 │
│   BleScanner │ BleConnection │ BleService │ BluetoothRepository          │
│   MeshtasticRadioProfile (fromRadio / sendToRadio)                       │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │
┌───────────────────────────────▼─────────────────────────────────────────┐
│ Kable 0.44.3 → Android BluetoothGatt / CoreBluetooth / bluez             │
└─────────────────────────────────────────────────────────────────────────┘
```

Адрес устройства кодируется одной строкой с префиксом-символом транспорта (`core/model/InterfaceId.kt`):

```kotlin
enum class InterfaceId(val id: Char) {
    BLUETOOTH('x'), MOCK('m'), NOP('n'), REPLAY('r'), SERIAL('s'), TCP('t')
}
```

То есть BLE-устройство хранится как `"xAA:BB:CC:DD:EE:FF"`. Фабрика (`BaseRadioTransportFactory`) смотрит
на первый символ и создаёт нужный транспорт — это **вся** точка ветвления между тремя способами подключения.

## 5. Два состояния соединения — критично важное различие

| Уровень | Где | Значения | Кто читает |
| :--- | :--- | :--- | :--- |
| **Транспортный** | `RadioInterfaceService.connectionState` | `Connected` / `Disconnected` / `DeviceSleep` | **только** `MeshConnectionManager` |
| **Прикладной** | `ServiceRepository.connectionState` | + `Connecting` (идёт handshake) | UI, все ViewModel'и |

Из KDoc `RadioInterfaceService`:

> **Important:** UI and feature modules should **never** observe `connectionState` directly.

Смысл: физический GATT-линк уже поднят, но конфигурация с ноды ещё не выкачана — приложение обязано
показывать «Подключение», а не «Подключено». Прикладное состояние переводится в `Connected` только после
завершения двухстадийного handshake.

## 6. Жизненный цикл на Android

```
MainActivity.onStart()
   └─> MeshService.startService(context, trigger)      ← ForegroundStartPolicy решает, можно ли
         └─> startForegroundService(MeshService)
               MeshService (Android Service, foreground)
                 • тип: connectedDevice (+ location, если есть разрешение и app на переднем плане)
                 • partial WakeLock (30 мин), уведомление статуса
                 └─> MeshServiceOrchestrator.start()    ← KMP, общий с Desktop
                       1. resetReceivedBuffer()          — выкинуть кадры прошлой сессии
                       2. ждать валидный адрес устройства
                       3. databaseManager.switchActiveDatabase(address)
                       4. nodeManager.loadCachedNodeDB()
                       5. radioInterfaceService.connect()
                       6. подписка на receivedData → MeshMessageProcessor
```

Инвариант, явно закреплённый в комментариях: **БД устройства должна быть переключена до старта транспорта**,
иначе handshake запишет данные в чужую/пустую базу.

Остановка (`onDestroy` → `orchestrator.stop()`) отправляет ноде «вежливое прощание»
`ToRadio(disconnect = true)` на отдельном detached-скоупе, чтобы отмена скоупа не оборвала отправку.

## 7. Потоки данных

**Входящие (нода → приложение):**

```
BLE notify FROMNUM → drain-цикл читает FROMRADIO
   → KableMeshtasticRadioProfile.fromRadio: Flow<ByteArray>
   → BleRadioTransport.dispatchPacket → callback.handleFromRadio(bytes)
   → SharedRadioInterfaceService.enqueueReceivedData → Channel(8192)   ← СТРОГИЙ FIFO
   → MeshServiceOrchestrator: receivedData.onEach { ... }
   → MeshMessageProcessor → NodeDB / Room / UI-флоу
```

Почему `Channel`, а не `SharedFlow` — в коде есть прямое объяснение:

> A `Channel` preserves strict FIFO delivery of incoming radio bytes, which the firmware handshake depends on
> (initial config packet ordering). A `SharedFlow` with `launch { emit() }` per packet reorders under concurrent
> dispatch and breaks config load.

Очередь **ограниченная** (8192 кадра) — при переполнении дропается новейший кадр, чтобы сохранить порядок уже
принятых. Плюс потолок на размер кадра `MAX_FRAME_BYTES = 512`.

**Исходящие (приложение → нода):**

```
UI / менеджеры → RadioController → PacketHandler.sendToRadio(ToRadio)
   → SharedRadioInterfaceService.sendToRadio(bytes)
   → BleRadioTransport.handleSendToRadio (под writeMutex, с ретраями)
   → GATT write в TORADIO
```

## 8. Механика сессий транспорта (защита от гонок)

При каждом старте транспорта инкрементируется `sessionGeneration`, и транспорт получает не сам сервис, а
**обёртку** `SessionBoundRadioInterfaceService`, которая пропускает коллбэки (`onConnect`/`onDisconnect`/
`handleFromRadio`) только если их сессия всё ещё активна. Так «опоздавший» пакет от убитого транспорта не
попадёт в БД нового устройства и не переключит состояние.

Дополнительно есть механизм аренды (`RadioSessionLease` / `runWithSessionLease`): teardown сначала закрывает
приём новой работы, потом дожидается завершения уже допущенной, и только затем публикует смену сессии.

## 9. Виртуальные транспорты для разработки

`MockRadioTransport` (синтетический меш) и `ReplayRadioTransport` (проигрывание записанного дампа
`burningmesh.fromradio` ~200 нод) доступны **только в debug-сборках** — в релизе фабрика их отклоняет,
т.к. адрес вида `connections?address=m` достижим через app-link. Полезный приём: разработка UI и парсера
пакетов без железа.

## 10. Что из этой архитектуры стоит забрать в своё приложение

Полностью копировать KMP-структуру для «своего приложения под Android» смысла нет. Ценность — в разделении:

1. **`core:ble`-подобный слой**: абстракции `BleScanner` / `BleConnection` / `RadioProfile` поверх библиотеки BLE.
   Не тащить `BluetoothGatt` в бизнес-логику.
2. **Отдельный `RadioTransport`** с узким коллбэком `onConnect / onDisconnect / handleFromRadio` —
   позже бесплатно добавляются TCP/USB.
3. **Один владелец соединения** (сервис/синглтон), а не соединение в каждой ViewModel.
4. **Foreground Service** — обязателен, иначе Android убьёт связь в фоне.
5. **Разделение транспортного и прикладного состояния** — иначе UI будет «мигать» на каждом реконнекте.
