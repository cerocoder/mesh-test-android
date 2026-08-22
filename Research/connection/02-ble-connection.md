# Подключение к ноде по Bluetooth LE — полный разбор

Все пути указаны относительно корня репозитория Meshtastic-Android v2.8.0.
Копии исходников — в [`code/`](code/).

## 0. Карта задействованных классов

| Класс | Модуль / файл | Ответственность |
| :--- | :--- | :--- |
| `MeshtasticBleConstants` | `core/ble/commonMain` | UUID сервиса и характеристик, regex имени |
| `BleScanner` / `KableBleScanner` | `core/ble/commonMain` | Сканирование, классификация ошибок старта скана |
| `AndroidBleScanStartLimiter` | `core/ble/androidMain` | Локальная квота «5 стартов / 30 с» |
| `BluetoothRepository` / `AndroidBluetoothRepository` | `core/ble` | Состояние адаптера, разрешения, список bonded, **бондинг** |
| `BleConnection` / `KableBleConnection` | `core/ble/commonMain` | GATT-соединение, доступ к профилю сервиса |
| `KablePlatformSetup` (expect/actual) | `core/ble` | MTU 512, connection priority, autoConnect, refresh кэша GATT |
| `MeshtasticRadioProfile` / `KableMeshtasticRadioProfile` | `core/ble/commonMain` | **Протокол обмена**: fromRadio / logRadio / sendToRadio |
| `BleRadioTransport` | `core/network/commonMain` | **Жизненный цикл**: поиск → бонд → коннект → подписки → реконнект |
| `BleReconnectPolicy` | `core/network/commonMain` | Экспоненциальный backoff |
| `BleExceptionClassifier`, `BleRetry` | `core/ble/commonMain` | Фатальность GATT-ошибок, ретраи |
| `SharedRadioInterfaceService` | `core/service/commonMain` | Владелец транспорта, heartbeat, liveness |
| `ScannerViewModel` / `AndroidScannerViewModel` | `feature/connections` | UI выбора устройства |

## 1. Разрешения (`androidApp/src/main/AndroidManifest.xml`)

```xml
<!-- legacy, только до Android 12 -->
<uses-permission android:name="android.permission.BLUETOOTH"       android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<!-- Android 12+ -->
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />   <!-- + usesPermissionFlags="neverForLocation" -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<!-- сервис -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

Проверка в рантайме (`AndroidBluetoothRepository.hasBluetoothPermissions`):

```kotlin
private fun hasBluetoothPermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    checkSelfPermission(BLUETOOTH_CONNECT) == GRANTED && checkSelfPermission(BLUETOOTH_SCAN) == GRANTED
} else {
    true  // до Android 12 разрешения install-time
}
```

## 2. Обнаружение устройств (экран Connections)

Список в UI формируется из **двух источников** (`ScannerViewModel.bleDevicesForUi`):

1. **Спаренные (bonded) устройства** от системы — `BluetoothAdapter.bondedDevices`, отфильтрованные
   по шаблону имени прошивки, чтобы не показывать наушники и часы:

   ```kotlin
   const val BLE_NAME_PATTERN = "^.*_([0-9a-fA-F]{4})$"   // Meshtastic_1234
   ```

   Показываются только те bonded, которые **сейчас видны в скане** или являются выбранным устройством —
   чтобы не висели «мёртвые» записи.

2. **Найденные сканом, но не спаренные** — помечаются `bonded = false`, чтобы UI повёл их через bonding.

Сканирование (`ScannerViewModel.startBleScan`):

```kotlin
bleScanner.scan(timeout = Duration.INFINITE, serviceUuid = MeshtasticBleConstants.SERVICE_UUID)
    .flowOn(dispatchers.io)
    .collect { device -> /* обновить map адрес→устройство, сохранить порядок обнаружения */ }
```

Особенности:
* фильтр **по service UUID** — аппаратная фильтрация на уровне ОС;
* порядок в списке стабилизирован (bonded по имени, затем найденные в порядке обнаружения) — карточки не «прыгают»
  при обновлении RSSI;
* скан **обязательно останавливается** при выборе устройства: `onSelected()` первым делом зовёт `stopAllScans()`
  — активный скан конкурирует с GATT-коннектом за радио.

### Квота Android на старты скана

Android отклоняет 6-й старт скана в скользящем окне ~30 с, причём на части прошивок **молча** — коллбэк не приходит
вовсе, и поток просто висит. Проект держит собственный лимитер (`AndroidBleScanStartLimiter`, 5 стартов / 30 с),
который бросает типизированное `BleScanStartException(ScanningTooFrequently, retryAfter)` **до** обращения к
`BluetoothLeScanner`, и UI показывает понятный таймер отката.

`KableBleScanner` также распознаёт по тексту исключений Kable:
`SCAN_FAILED_APPLICATION_REGISTRATION_FAILED`, `SCAN_FAILED_SCANNING_TOO_FREQUENTLY`, «Missing required … for scanning»,
и через типизированный `UnmetRequirementReason` — «Bluetooth выключен» / «геолокация выключена».

## 3. Бондинг (спаривание)

Выполняется **до** GATT-коннекта. Причина в комментарии `BleRadioTransport.bondDeviceBeforeConnect`:

> firmware may require an encrypted link, and without a bond Android fails with status 5 or 133.

`AndroidBluetoothRepository.bond()` — не «вызвал createBond и ждём», а гибрид:

1. если уже `BOND_BONDED` — выходим;
2. регистрируем `BroadcastReceiver` на `ACTION_BOND_STATE_CHANGED` (`RECEIVER_NOT_EXPORTED`);
3. `remoteDevice.createBond()`;
   * `false` не всегда ошибка — бонд мог быть уже запущен GATT-операцией по защищённой характеристике;
     поэтому перепроверяем `bondState` (`BOND_BONDED` → успех, `BOND_BONDING` → ждём);
4. **параллельно с ресивером опрашиваем `bondState` каждые 500 мс** — broadcast ненадёжен на части устройств
   (в коде ссылка на Kable issue #111);
5. общий таймаут 30 с; есть «грейс» на две первые выборки `BOND_NONE` после успешного `createBond()`
   (некоторые OEM сообщают `BOND_BONDING` с задержкой);
6. в `finally` — обновление `BluetoothState`.

Есть и `removeBond(address)` — через **рефлексию** (`BluetoothDevice.removeBond` — public-but-hidden API);
нужен перед прошивкой nRF Legacy-DFU bootloader, который переиспользует тот же MAC.

Обработка результата в UI (`AndroidScannerViewModel.requestBonding`): если `bond()` бросил исключение, но
`isBonded(address)` уже `true` — всё равно продолжаем подключение; если нет — показываем сообщение и **не**
запускаем транспорт.

## 4. Выбор устройства и запуск транспорта

```
onSelected(entry)                       ScannerViewModel
  ├─ stopAllScans()
  ├─ radioPrefs.setDevName(entry.name)
  └─ entry.bonded ? changeDeviceAddress(fullAddress) : requestBonding(entry)
        │
        └─> radioController.setDeviceAddress("x" + MAC)          RadioControllerImpl
              ├─ radioInterfaceService.disconnect()              (снять старую сессию)
              ├─ switchDevice(address)                           (переключить Room-БД, очистить NodeDB)
              └─ radioInterfaceService.setDeviceAddress(address)
                    └─> SharedRadioInterfaceService
                          ├─ radioPrefs.setDevAddr(address)      (персист)
                          ├─ connectionRequested = true          (гейт жизненного цикла)
                          ├─ stopTransportLocked()
                          └─ startTransportLocked()
                                ├─ generation = ++counter        (новая сессия)
                                └─ transportFactory.createTransport(address, sessionBoundService)
                                      └─> BleRadioTransport(...).start() → connect()
```

Фабрика (`BaseRadioTransportFactory.createTransport`) — единственная точка ветвления по транспорту:

```kotlin
address.startsWith(InterfaceId.BLUETOOTH.id) || address.startsWith("!") -> {
    val bleAddress = address.removePrefix("x").removePrefix("!")
    BleRadioTransport(scope = service.serviceScope, scanner, bluetoothRepository,
                      connectionFactory, callback = service, address = bleAddress)
}
```

## 5. `BleRadioTransport` — цикл одного подключения

Главный класс (`core/network/.../radio/BleRadioTransport.kt`, 843 строки). Метод `start()` запускает
бесконечный цикл политики переподключения:

```kotlin
reconnectPolicy.execute(
    attempt = { attemptConnection() },            // одна попытка «подключиться и жить до разрыва»
    onTransientDisconnect = { callback.onDisconnect(isPermanent = false) },
    onPermanentDisconnect = { callback.onDisconnect(isPermanent = true, errorMessage = …) },
)
```

### 5.1 `attemptConnection()` по шагам

| # | Шаг | Детали |
| :-- | :--- | :--- |
| 1 | Сброс флагов сессии | `sessionFailed = false`, `sessionFailureCause = null` |
| 2 | `findDevice()` | поиск устройства (см. 5.2) |
| 3 | `bondDeviceBeforeConnect()` | бонд, если не спарено |
| 4 | `connectAndAwait(device, 15 с)` | GATT-коннект с таймаутом |
| 5 | *(опционально)* инвалидация кэша GATT | после OTA: `refresh()` + переподключение |
| 6 | `onConnected()` | чтение RSSI (диагностика) |
| 7 | `discoverServicesAndSetupCharacteristics()` | подписки + handshake-готовность (см. 5.4) |
| 8 | «Connected-гейт» | дождаться, что `StateFlow` действительно показал `Connected` (таймаут 5 с) |
| 9 | Ожидание разрыва | `connectionState.filterIsInstance<Disconnected>().first()` |
| 10 | Классификация разрыва | стабильное ли было соединение (≥ 5 с), намеренный ли разрыв |

Шаг 8 выглядит избыточным, но защищает от реальной гонки: `connectAndAwait` возвращается синхронно по состоянию
Kable-периферии, а наблюдатель `connectionState` работает на отдельной корутине и может отставать. Без гейта
шаг 9 мог бы «поймать» *прошлый* `Disconnected` и мгновенно уйти в реконнект.

### 5.2 `findDevice()` — поиск устройства перед коннектом

Две ветки:

* **Устройство спарено**: **ровно один** скан с фильтром по адресу, 5 с. Если свежая реклама поймана — используем
  её (быстрый прямой коннект). Если нет — берём bonded-хендл и идём по «терпеливому» пути `autoConnect`.
  Одна попытка вместо двух — сознательное решение ради квоты сканов Android
  (`SCAN_FAILED_SCANNING_TOO_FREQUENTLY` при одновременных сканах экрана Connections и реконнекта).
* **Не спарено**: 3 попытки скана по 5 с с паузой 1 с; при неудаче — `RadioNotConnectedException`.

### 5.3 `KableBleConnection.connect()` — стратегия «direct → autoConnect»

Взято из рекомендаций Kable (SensorTag sample):

```kotlin
var autoConnect = meshtasticDevice.advertisement == null       // нет рекламы → сразу autoConnect

val p = meshtasticDevice.advertisement?.let { adv -> Peripheral(adv) { commonConfig() } }
        ?: createPeripheral(device.address) { commonConfig() }

repeat(2) {                                    // максимум 2 попытки на один connect()
    if (p.state.value is State.Connected) return
    autoConnect = try { connectionScope = p.connect(); false }
                  catch (e: Exception) {
                      if (autoConnect) { emit(Disconnected(ConnectionFailed)); throw e }
                      delay(1.seconds); true   // fallback на autoConnect
                  }
}
```

Смысл: свежая реклама → `autoConnect=false` (быстро); bonded без рекламы → `autoConnect=true`, чтобы система
терпеливо ждала появления устройства (иначе на Android типичны GATT 133 и таймауты, особенно при random
resolvable address). Макро-ретраи здесь **намеренно отсутствуют** — ими владеет `BleReconnectPolicy`.

Установка владения периферией сделана атомарно под `withContext(NonCancellable)`: отмена между созданием
`Peripheral` и присваиванием поля утекла бы (Kable сразу создаёт скоуп и наблюдателя состояния BT).

### 5.4 Настройка профиля и подписок

```kotlin
bleConnection.profile(serviceUuid = SERVICE_UUID) { service ->
    val radioService = service.toMeshtasticRadioProfile()

    radioService.fromRadio.onEach { dispatchPacket(it) }
        .catch { handleFailure(it) }.launchIn(this)
    radioService.logRadio.onEach { dispatchPacket(it) }
        .catch { handleFailure(it) }.launchIn(this)

    this@BleRadioTransport.radioService = radioService

    // КРИТИЧНО: дождаться записи CCCD для FROMNUM, иначе нотификации не придут
    val ok = withTimeoutOrNull(5.seconds) { radioService.awaitSubscriptionReady(); true } ?: false
    if (!ok || sessionFailed.value) throw (sessionFailureCause ?: RuntimeException("…"))

    val maxLen = bleConnection.maximumWriteValueLength(BleWriteType.WITHOUT_RESPONSE)  // диагностика MTU
    requestHighPriorityAndScheduleDowngrade()

    if (!sessionFailed.value) callback.onConnect()      // ← только теперь «подключено» для верхних слоёв
}
```

`profile()` внутри ждёт завершения service discovery (`p.services.first { it != null }`) и превращает падение
скоупа соединения во время setup в `NotConnectedException`, а не в зависание до таймаута.

### 5.5 Приоритет соединения и энергопотребление

```kotlin
if (bleConnection.requestHighConnectionPriority()) { delay(1.seconds) }   // быстрый первичный слив конфига
launch { delay(30.seconds); bleConnection.requestBalancedConnectionPriority() }
```

High priority (интервал ~7.5 мс) держится 30 с — этого хватает на выкачку конфига и NodeDB даже у медленных
ESP32 с большой базой; дальше — Balanced, иначе заметно растёт расход батареи на обеих сторонах.

MTU запрашивается в Android-специфичном `platformConfig` в коллбэке `onServicesDiscovered`:

```kotlin
val negotiatedMtu = requestMtu(512)   // Android по умолчанию 23 байта, пакеты Meshtastic — до 512
```

Также задаётся общий на всё приложение `PooledThreadingStrategy()` (Kable рекомендует ровно один пул на процесс).

## 6. Обмен данными: `KableMeshtasticRadioProfile`

Это ядро протокола. Логика:

```
        ┌──────────────── notify FROMNUM ────────────────┐
        │  (прошивка сообщает «есть данные»)             │
        ▼                                                │
  triggerDrain (MutableSharedFlow, replay=1, DROP_OLDEST)│
        │                                                │
        ▼                                                │
  while (keepReading) {                                  │
      packet = read(FROMRADIO)                           │
      if (packet.isEmpty()) keepReading = false          │
      else emit(packet)                                  │
  }                                                      │
        ▲                                                │
        └── также триггерится после КАЖДОЙ записи в TORADIO
```

Ключевые решения:

* **`FROMNUM` — не канал данных, а «звонок в дверь».** Данные всегда вычитываются read'ами `FROMRADIO`,
  пока не вернётся пустой массив.
* **Затравка при старте**: `triggerDrain.tryEmit(Unit)` вызывается сразу, до первой нотификации. Прошивка
  выдаёт FROMNUM только в состоянии `STATE_SEND_PACKETS`, поэтому во время handshake нужно опрашивать активно.
* **Латч из одного слота** (`replay = 1, extraBufferCapacity = 1, DROP_OLDEST`) — пачка триггеров схлопывается
  в один опрос, писатели не блокируются, устаревшие запросы не копятся.
* **Классификация ошибок чтения**: `isSessionFatalBleException()` → пробрасываем наверх (транспорт пойдёт в
  реконнект); иначе — пауза 500 мс и продолжаем.
* `LOGRADIO` (логи прошивки) обрабатывается мягче: транзиентная ошибка просто завершает поток до следующего
  переподключения — это диагностический канал.

Отправка:

```kotlin
override suspend fun sendToRadio(packet: ByteArray) {
    service.write(toRadio, packet, toRadioWriteType)   // тип записи вычислен один раз в конструкторе
    triggerDrain.tryEmit(Unit)                         // ответ обычно уже готов
}
```

Тип записи выбирается из свойств характеристики: `WITHOUT_RESPONSE`, если поддерживается, иначе `WITH_RESPONSE`.
Кэшируется при создании профиля, чтобы горячий путь не обходил список сервисов на каждый пакет.

## 7. Handshake (двухстадийный)

Инициируется **прикладным** слоем после `callback.onConnect()` (`MeshConnectionManagerImpl.handleConnected`):

```kotlin
heartbeatSender.sendHeartbeat("pre-handshake")   // «разбудить» NimBLE-контекст прошивки
delay(PRE_HANDSHAKE_SETTLE_MS)                   // 100 мс — чтобы запись heartbeat встала в очередь раньше
startConfigOnly()                                // ToRadio(want_config_id = 69420)  — Stage 1
…
startNodeInfoOnly()                              // ToRadio(want_config_id = 69421)  — Stage 2
```

```kotlin
object HandshakeConstants {
    const val CONFIG_NONCE = 69420      // конфиг устройства, конфиг модулей, каналы
    const val NODE_INFO_NONCE = 69421   // база нод
}
```

Watchdog «зависшего handshake»: для быстрых транспортов (TCP/USB) — 12 с, для BLE — длинный бюджет с ретраями,
т.к. задержки GATT велики и непредсказуемы. **Восстановление — только полным рестартом транспорта.**
В коде явно описано, почему повторный `want_config_id` в той же сессии запрещён:

> firmware's `handleStartConfig()` has no in-flight guard, so a second want_config on the same session re-enters
> it and crashes the firmware (reproduced on T-Beam v2.7.25.104df5f in QA).

Прикладное состояние переходит в `Connected` только после завершения Stage 2 и установки NodeDB.

## 8. Поддержание живости

**Heartbeat** — каждые 30 с (`SharedRadioInterfaceService.startHeartbeat`):

```kotlin
ToRadio(heartbeat = Heartbeat(nonce = n))   // nonce монотонно растёт
```

Монотонный nonce обязателен: у прошивки есть per-connection фильтр дубликатов записей, и одинаковые байты
были бы молча отброшены. После отправки — пауза 200 мс и принудительный drain: ESP32 обрабатывает запись
асинхронно (NimBLE callback → очередь FreeRTOS → `handleToRadio()`), поэтому ответ `queueStatus` в момент
немедленного drain ещё не готов.

**Liveness / детектор «зомби»** (`checkLiveness`): если состояние `Connected`, но входящих данных нет
дольше 60 с (2 × интервал heartbeat) — **только для BLE** — транспорт принудительно перезапускается.
Причина: BLE-стек Android умеет «терять» линк, не сообщая о дисконнекте. Для TCP/USB такой эвристики нет —
там нет контракта, по которому тишина означает смерть сессии.

## 9. Переподключение: `BleReconnectPolicy`

```kotlin
BleReconnectPolicy(maxFailures = Int.MAX_VALUE)   // пока устройство выбрано — не сдаёмся никогда
```

| Параметр | Значение | Смысл |
| :--- | :--- | :--- |
| `DEFAULT_SETTLE_DELAY` | **3 с** | пауза перед *каждой* попыткой, включая первую |
| `DEFAULT_MIN_STABLE_CONNECTION` | 5 с | соединение короче — считается нестабильным (растим счётчик) |
| `DEFAULT_FAILURE_THRESHOLD` | 3 | после 3 подряд неудач наверх идёт `DeviceSleep` |
| backoff | 5 / 10 / 20 / 40 / 60 с (кап) | `RECONNECT_BASE_DELAY << (n-1)`, максимум 60 с |
| `CONNECTION_TIMEOUT` | 15 с | таймаут одной попытки коннекта |

`settleDelay = 3 с` — не «на всякий случай», а измеренное: при паузе 1.5 с между disconnect→reconnect
3–4 попытки из 5 падали посреди handshake, потому что прошивка ещё не освободила свою GATT-сессию; при ≥ 5 с — 5/5.

Намеренный (пользовательский) разрыв и стабильное соединение сбрасывают счётчик неудач; внутренний сбой сессии
(`sessionFailureCause != null`) намеренным **не** считается и эскалирует backoff.

## 10. Обработка ошибок

`BleExceptionClassifier` делит ошибки на «сессия мертва» и «можно повторить»:

```kotlin
private val FATAL_GATT_STATUSES = setOf(
    8,    // GATT_CONN_TIMEOUT           — supervision timeout (ушёл из зоны)
    19,   // GATT_CONN_TERMINATE_PEER_USER — разрыв со стороны ноды (ребут/выключение) ← самый частый
    22,   // GATT_CONN_LMP_TIMEOUT       — зависание радио/прошивки
    62,   // GATT_CONN_FAIL_ESTABLISH
    133,  // GATT_ERROR                  — классический «протухший» GATT-хендл
    129,  // GATT_FAILURE
)
```

Плюс `NotConnectedException` — всегда фатально. Проверка идёт **по цепочке cause** (глубина ≤ 10), т.к. Kable и
корутины оборачивают исключения.

Ретраи одиночных операций — `retryBleOperation` (3 попытки, задержка 250 мс × 2^n, кап 2 с, **джиттер ±25 %**
против «штормов» одновременных повторов TX/RX).

Политика записи: если после всех ретраев запись всё равно упала — сессия считается мёртвой и уходит в полный
teardown+reconnect. Обоснование в коде: в длинных сессиях запись однажды начинает падать с `NotConnectedException`
после сотен успешных, и на этом этапе GATT-хендл уже нерабочий — «перезаписать на месте» невозможно.

Дедупликация: `sessionFailed` — атомарный CAS «первый победил», чтобы один разрыв не породил несколько
`onDisconnect` (гонка между ошибкой записи, ошибкой `fromRadio` и коллбэком дисконнекта).

## 11. Корректное закрытие соединения

```kotlin
override suspend fun close() {
    connectionScope.cancel()
    withContext(NonCancellable) {                       // отменённый вызывающий не должен пропустить cleanup
        withTimeoutOrNull(5.seconds) { bleConnection.disconnect() }
    }
    cleanupScope.cancel()
}
```

И внутри `KableBleConnection.safeClosePeripheral`:

```kotlin
try { peripheral?.disconnect() } catch (_: NotConnectedException) { /* уже отключено */ }
try { peripheral?.close() }      catch (e: Exception) { … }   // Kable требует close() для release broadcast receivers
```

Отдельный **detached** `cleanupScope` (не потомок основного скоупа) существует ровно для одного случая:
при необработанном исключении верхние слои часто рвут родительский скоуп, и cleanup, запущенный на нём,
может не стартовать вообще — а это утёкший `BluetoothGatt` и GATT 133 при следующем подключении.

Перед разрывом транспорт по возможности отправляет ноде «вежливое прощание»:

```kotlin
currentTransport.handleSendToRadio(ToRadio(disconnect = true).encode())
delay(500)   // окно на дослать перед close()
```

## 12. Инвалидация кэша GATT (после OTA)

Android кэширует таблицу сервисов на MAC. Если устройство перезагрузилось в другой GATT-профиль
(например, OTA-загрузчик ESP32) с тем же адресом — кэш врёт. Решение — скрытый `BluetoothGatt.refresh()`
через **рефлексию в два прыжка по внутренностям Kable** (`Peripheral.connection` → `.value` → `Connection.gatt`),
с честным предупреждением в комментарии:

> Direct 2-hop reflection on Kable 0.43.1 internals. Re-verify field names on Kable version bumps.

Флаг одноразовый: `requestGattCacheInvalidationOnNextConnect()` → `consumeGattCacheInvalidationRequest()`.
После refresh делается disconnect, пауза 500 мс и повторный коннект — чтобы service discovery прошёл заново.

## 13. Полная последовательность (сводка)

```
[UI] выбор устройства
  → stopAllScans()
  → bond (если нужно): createBond + BroadcastReceiver + polling, ≤30 с
  → setDeviceAddress("x" + MAC): БД устройства ↔ адрес, новая сессия
  → BleRadioTransport.start()

  ┌─ повтор по BleReconnectPolicy (settle 3 с → попытка → backoff 5..60 с) ────┐
  │  findDevice(): 1 скан 5 с по адресу (bonded) | 3×5 с (не bonded)          │
  │  bond, если ещё не спарено                                                │
  │  connect: Peripheral(advertisement) direct → fallback autoConnect (≤15 с) │
  │  service discovery → MTU 512 → high connection priority                   │
  │  подписка на FROMNUM (+LOGRADIO) → ЖДАТЬ запись CCCD (≤5 с)               │
  │  onConnect() → [прикладной слой] heartbeat → 100 мс → want_config_id      │
  │      Stage 1 (69420: конфиг/каналы) → Stage 2 (69421: база нод)           │
  │  через 30 с → balanced connection priority                                │
  │  работа: notify FROMNUM → drain read FROMRADIO; write TORADIO + drain     │
  │  heartbeat каждые 30 с (nonce++) + drain через 200 мс                     │
  │  liveness: тишина > 60 с → рестарт транспорта                             │
  │  разрыв → классификация (стабильно? намеренно?) → следующая итерация      │
  └───────────────────────────────────────────────────────────────────────────┘

  close(): ToRadio(disconnect=true) → 500 мс → disconnect() + close() под NonCancellable
```
