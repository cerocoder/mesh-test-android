# Выводы, грабли и чек-лист для собственной реализации

## 1. Главный вывод

Соотношение объёма кода красноречиво:

| Что | Строк |
| :--- | ---: |
| Собственно протокол обмена (`KableMeshtasticRadioProfile`) | ~140 |
| GATT-соединение (`KableBleConnection`) | ~335 |
| **Жизненный цикл + реконнект (`BleRadioTransport`)** | **~845** |
| Бондинг на Android (`AndroidBluetoothRepository`) | ~355 |
| Владение транспортом, сессии, liveness (`SharedRadioInterfaceService`) | ~1095 |

Прочитать пакет из характеристики — 10 строк. Всё остальное — борьба с реальностью Android BLE: протухшие
GATT-хендлы, молчаливые дисконнекты, квоты сканирования, гонки корутин и особенности прошивки ESP32/nRF52.
Планируя своё приложение, закладывайте бюджет именно на это.

## 2. Грабли, за которые уже заплатили авторы (комментарии в коде — по сути постмортемы)

| Проблема | Симптом | Решение в проекте |
| :--- | :--- | :--- |
| Нет бонда перед GATT | Ошибки GATT status 5 / 133 | `bond()` до `connect()`; продолжать, если ОС уже считает устройство спаренным |
| `BluetoothDevice.ACTION_BOND_STATE_CHANGED` ненадёжен | Бондинг «вечно в процессе» | Broadcast **и** polling `bondState` каждые 500 мс, таймаут 30 с |
| `createBond()` вернул `false` | Ложная ошибка | Перепроверять `bondState`: `BOND_BONDED` → успех, `BOND_BONDING` → ждать |
| Direct connect к bonded без свежей рекламы | GATT 133, таймауты (random resolvable address) | `autoConnect = true` для устройств без рекламы, direct — только со свежим advertisement |
| Не вызван `BluetoothGatt.close()` | Утечка, GATT 133 на следующем коннекте | `disconnect()` **и** `close()`, оба под `NonCancellable`, в отдельном detached-скоупе |
| Отмена корутины во время teardown | Cleanup не выполняется, ресурс утёк | `withContext(NonCancellable)` для всего teardown; отдельный `cleanupScope`, не потомок основного |
| Частые старты скана | `SCAN_FAILED_SCANNING_TOO_FREQUENTLY`, иногда **молча** без коллбэка | Свой лимитер 5/30 с **до** обращения к системному сканеру + понятный откат в UI |
| Скан во время коннекта | Handshake рвётся, коннект деградирует | `stopAllScans()` перед началом подключения |
| MTU по умолчанию (23) | Потери/зависания на пакетах >20 байт | `requestMtu(512)` в `onServicesDiscovered` |
| Подписка без ожидания CCCD | Handshake «висит» без ошибок | `awaitSubscriptionReady()` с таймаутом 5 с перед `want_config_id` |
| Ожидание push-данных от FROMNUM | Пакеты не приходят | FROMNUM — только сигнал; читать `FROMRADIO` в цикле до пустого ответа |
| Отсутствие затравочного drain | Зависание на этапе конфига | `triggerDrain` сразу при старте — прошивка не шлёт FROMNUM до `STATE_SEND_PACKETS` |
| Одинаковые байты heartbeat | Прошивка молча дропает | Монотонный `nonce` |
| Немедленный drain после heartbeat | `queueStatus` не виден | Пауза 200 мс перед повторным drain |
| Повторный `want_config_id` в живой сессии | **Краш прошивки** (T-Beam v2.7.25) | Только полный рестарт транспорта |
| Реконнект через 1.5 с | 3–4 из 5 попыток падают в handshake | `settleDelay = 3 с` перед каждой попыткой |
| BLE «зомби»-сессия (нет коллбэка о разрыве) | Приложение думает, что подключено, данных нет | Liveness: тишина > 60 с при `Connected` → принудительный рестарт (только BLE) |
| `SharedFlow` + `launch { emit() }` для входящих | Переупорядочивание пакетов ломает загрузку конфига | Ограниченный `Channel` + `trySend`, строгий FIFO |
| Несколько `onDisconnect` на один разрыв | Двойные диалоги/дёрганый UI | Атомарный CAS `sessionFailed` — «первый победил» |
| Поздние коллбэки от убитого транспорта | Данные пишутся в БД другого устройства | Сессии с `generation` + session-bound обёртка коллбэков |
| Кэш GATT после OTA | Старая таблица сервисов на том же MAC | `BluetoothGatt.refresh()` через рефлексию + переподключение |
| High connection priority навсегда | Быстрый разряд батареи с обеих сторон | High на 30 с (первичный слив конфига), затем Balanced |
| UI смотрит на транспортное состояние | «Подключено» при незагруженном конфиге, мигание при реконнекте | Два уровня состояния; UI видит только прикладное |

## 3. Чек-лист для собственного приложения

### Манифест и разрешения
- [ ] `BLUETOOTH_SCAN` (+ `neverForLocation`, если геолокация не нужна) и `BLUETOOTH_CONNECT` для API 31+
- [ ] Legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` с `maxSdkVersion="30"`, `ACCESS_FINE/COARSE_LOCATION` для < 12
- [ ] `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `WAKE_LOCK`
- [ ] Runtime-проверка разрешений перед сканом и перед доступом к `bondedDevices` (ловить `SecurityException`)

### Архитектура
- [ ] Соединение живёт в **foreground-сервисе**, не в Activity/ViewModel
- [ ] Один владелец активного соединения на всё приложение
- [ ] Абстракции `Scanner` / `Connection` / `RadioProfile` поверх BLE-библиотеки; `BluetoothGatt` не течёт в UI
- [ ] Узкий коллбэк транспорта: `onConnect` / `onDisconnect(isPermanent)` / `onDataReceived(bytes)`
- [ ] Разделены транспортное и прикладное состояния соединения
- [ ] Входящие кадры — упорядоченная ограниченная очередь, а не «эмиссия на корутину на пакет»
- [ ] Идентификатор сессии, отбрасывающий коллбэки старого соединения

### BLE
- [ ] Скан с фильтром по service UUID (в UI) и по адресу (при реконнекте)
- [ ] Собственный лимитер частоты сканов
- [ ] Bond до GATT-коннекта; polling `bondState` параллельно с broadcast
- [ ] `requestMtu(512)` после service discovery
- [ ] Ожидание записи CCCD перед первым запросом конфигурации
- [ ] Drain-цикл `read(FROMRADIO)` до пустого ответа; триггеры: FROMNUM, старт, каждая запись в TORADIO
- [ ] Тип записи выбирается по свойствам характеристики (`WITHOUT_RESPONSE`, если доступно)
- [ ] `disconnect()` + `close()` под `NonCancellable`; detached-скоуп для аварийного cleanup
- [ ] Классификация GATT-статусов на фатальные/транзиентные (по цепочке `cause`)
- [ ] Реконнект: settle 3 с, backoff 5→60 с, порог «нестабильного» соединения 5 с
- [ ] Heartbeat 30 с с монотонным nonce + отложенный drain
- [ ] Liveness-детектор тишины (~60 с) с принудительным рестартом

### Протокол
- [ ] Protobuf-схемы из upstream (`org.meshtastic:protobufs`), не писать руками
- [ ] Handshake: heartbeat → 100 мс → `want_config_id = 69420` → `want_config_id = 69421`
- [ ] Watchdog на зависший handshake → **рестарт транспорта**, а не повторный `want_config_id`
- [ ] «Вежливое прощание» `ToRadio(disconnect = true)` перед закрытием
- [ ] Ограничение размера кадра (512 байт)

### Разработка и отладка
- [ ] Mock-транспорт (синтетика) и replay-транспорт (запись реального трафика) — разработка без железа
- [ ] Виртуальные транспорты доступны **только в debug**
- [ ] Логи с анонимизацией MAC и без PII/ключей
- [ ] Отдельная БД (или пространство имён) на каждое устройство, переключение **до** старта транспорта

## 4. Если писать под Android «как у них», но проще

Минимальный скелет, сохраняющий все важные свойства:

```
app/
├── ble/
│   ├── MeshtasticBleConstants.kt     UUID + regex имени
│   ├── BleScanner.kt                 скан с фильтром + лимитер
│   ├── BleBondManager.kt             createBond + receiver + polling
│   ├── BleConnection.kt              connect/disconnect/profile поверх Kable (или Nordic BLE Library)
│   └── MeshtasticRadioProfile.kt     fromRadio: Flow<ByteArray> / sendToRadio
├── transport/
│   ├── RadioTransport.kt             интерфейс: send / start / keepAlive / close
│   ├── BleRadioTransport.kt          жизненный цикл + реконнект (главный класс)
│   └── ReconnectPolicy.kt            backoff
├── service/
│   ├── MeshForegroundService.kt      foreground-сервис — якорь жизненного цикла
│   └── RadioInterface.kt             владелец транспорта, очередь кадров, heartbeat, liveness
└── ui/
    └── ConnectionsScreen + ViewModel список/скан/бонд/выбор
```

Библиотека BLE: у Meshtastic — **Kable** (нужен KMP). Для чисто Android-приложения альтернатива —
Nordic Android BLE Library, которая закрывает часть тех же граблей (очередь операций, ретраи, MTU),
но описанные выше правила протокола и жизненного цикла остаются в силе в любом случае.
