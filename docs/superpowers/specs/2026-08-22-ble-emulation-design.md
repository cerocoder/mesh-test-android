# Дизайн: эмуляция ноды Meshtastic для отладки + BLE-транспорт

**Дата:** 2026-08-22
**Статус:** одобрен, готов к написанию плана реализации
**Основание:** исследование эталонной реализации Meshtastic-Android v2.8.0 — см. `Research/connection/`

## 1. Цель

Приложение для работы с нодой Meshtastic под Android. Разработка ведётся без физической ноды,
поэтому нужен способ отлаживать логику приложения на эмулируемом устройстве, а также реальный
BLE-транспорт для работы с настоящей нодой, когда она появится.

Два результата:

1. **Демо-устройство** в списке подключений, которое ведёт себя как нода: отвечает на handshake,
   отдаёт конфиг, каналы и базу нод из заранее заданного сценария. Bluetooth не участвует —
   работает на эмуляторе телефона и в CI.
2. **Реальный BLE-транспорт** — сканирование, бондинг, GATT-обмен, реконнект.

Обе реализации входят через один шов, поэтому всё, что выше него, не знает, к чему подключено.

## 2. Принятые решения

| Решение | Выбор | Обоснование |
| :--- | :--- | :--- |
| Платформа | Обычный Android-проект, не KMP | Целевая платформа одна; KMP даёт ограничения `commonMain` и сложную сборку без выгоды |
| Шов подмены | На уровне `RadioTransport` (байты) | Выше шва работает настоящий код: декодирование, handshake, состояния. Реальный BLE встаёт на то же место без правок вокруг |
| Протокол | `org.meshtastic:protobufs:2.7.26` | Релиз на Maven Central (не снапшот), Kotlin-модели Square Wire; своя генерация из `.proto` не нужна |
| BLE-библиотека | `no.nordicsemi.android:ble:2.11.0` + `ble-ktx:2.11.0` | Очередь GATT-операций, ретраи, `requestMtu`, `useAutoConnect`, обходы багов OEM. Свежее корутинной линейки `kotlin.ble:1.3.1` |
| Данные эмуляции | Синтетические сценарии в коде | Физической ноды нет, записать реальный трафик невозможно |
| Глубина эмуляции | Handshake + статичный меш + ответ на heartbeat | Достаточно, чтобы приложение дошло до `Connected` и показало ноды; маршрутизация и сбои не эмулируются |
| Активация эмулятора | Виртуальные устройства в списке, только debug | Фейк входит через тот же шов, что BLE; переключение без пересборки |
| DI | Вручную, `AppContainer` | Hilt на десяток классов — лишняя церемония; добавится при росте |
| Модульность | Один Gradle-модуль, границы пакетами | Многомодульность на первом срезе даёт только накладные расходы |

### Цена выбора Nordic

Код эталона написан на Kable и **не переносится копированием** — у Nordic другая модель
(`BleManager` с очередью операций вместо `Peripheral` с корутинами). Переносится знание:
порядок операций handshake, ожидание CCCD, drain-цикл, бондинг до коннекта, классификация
GATT-статусов, тайминги реконнекта. Часть кода эталона при этом не нужна вовсе:
`retryBleOperation`, мьютекс записи и пул потоков обеспечивает сам `BleManager`.

## 3. Область

**Входит:** транспортный шов; фейковый транспорт со сценариями; BLE-транспорт (скан, бондинг,
GATT, реконнект); менеджер соединения с handshake; foreground-сервис; экран выбора устройства;
диагностический экран ленты пакетов.

**Не входит:** база нод в Room, чат, карта, отправка сообщений, телеметрия, MQTT, OTA,
эмуляция маршрутизации меша, эмуляция сбоев по сценарию, инструментальные и Compose-тесты.

### Два этапа реализации

Работа разбивается на два последовательных этапа, план реализации следует этому делению:

1. **Этап 1 — шов, фейк, экраны.** Транспортные интерфейсы, `FakeRadioTransport` со сценариями,
   `RadioConnectionManager` с handshake, экран устройств и лента пакетов. Полностью проверяемо
   в CI и на эмуляторе телефона. По завершении на руках рабочее приложение, а не полуфабрикат.
2. **Этап 2 — BLE.** `MeshGattClient` / `MeshRadioProfile`, `NordicMeshGattClient`, сканер,
   `BleRadioTransport`, foreground-сервис, разрешения. Встаёт поверх готового шва: код выше
   `transport/` не меняется.

## 4. Архитектура

Один Gradle-модуль `app`, minSdk 26. Границы — пакетами:

```
app/src/main/kotlin/<pkg>/
├── transport/   RadioTransport, RadioTransportCallback, RadioTransportFactory,
│                FakeRadioTransport, MeshProtocol (нонсы handshake)
├── emulator/    MeshScenario + готовые наборы сценариев
├── ble/         MeshGattClient, MeshRadioProfile — протокол (чистый Kotlin)
│                NordicMeshGattClient, BleScannerImpl — реализации на Nordic
│                FakeMeshGattClient — для тестов протокола без железа
│                BleRadioTransport — жизненный цикл соединения
├── connection/  RadioConnectionManager: handshake, ConnectionState, поток FromRadio
├── service/     MeshForegroundService
└── ui/          DeviceListScreen, PacketLogScreen
```

**Владелец соединения** — `RadioConnectionManager`, application-scoped синглтон в `AppContainer`.
Foreground-сервис (тип `connectedDevice`) лишь удерживает процесс живым, поэтому менеджер
переживает пересоздание сервиса. Сервис достаёт зависимости через `(application as App).container`.

**Инвариант:** ничто выше пакета `transport` не знает, что подключено — фейк или BLE.

## 5. Компоненты и интерфейсы

### 5.1 Шов транспорта

```kotlin
interface RadioTransport {
    fun start()
    fun send(bytes: ByteArray)
    suspend fun close()
}

interface RadioTransportCallback {
    fun onConnect()
    fun onDisconnect(isPermanent: Boolean)
    fun onDataReceived(bytes: ByteArray)
}

interface RadioTransportFactory {
    fun create(address: String, callback: RadioTransportCallback): RadioTransport
}
```

Адресация: первый символ задаёт транспорт (конвенция взята из эталона).

```kotlin
sealed class DeviceListEntry(val name: String, val address: String) {
    class Demo(scenarioId: String, name: String) : DeviceListEntry(name, "m:$scenarioId")
    class Ble(name: String, mac: String, val bonded: Boolean, val rssi: Int?) :
        DeviceListEntry(name, "x$mac")
}
```

Фабрика ветвится только по префиксу: `"m:"` → `FakeRadioTransport` с указанным сценарием,
`"x"` → `BleRadioTransport` с MAC-адресом.

### 5.2 Сценарии эмуляции

Данные отделены от поведения: что эмулировать — значение, как эмулировать — транспорт.

```kotlin
data class MeshScenario(
    val myInfo: MyNodeInfo,
    val metadata: DeviceMetadata,
    val config: List<Config>,
    val moduleConfig: List<ModuleConfig>,
    val channels: List<Channel>,
    val nodes: List<NodeInfo>,
)
```

Готовые наборы: «Demo: 5 нод», «Demo: 200 нод» (проверка производительности списка),
«Demo: пустой меш», «Demo: только handshake». Каждый — отдельный пункт в списке устройств.

`MeshScenario` дополнительно принимает готовый `List<FromRadio>` — это позволит позже подложить
бинарный дамп с реальной ноды или собрать сценарий прямо в тесте, не трогая транспорт.

### 5.3 BLE: два уровня

Разделение существует затем, чтобы самая хрупкая часть протокола проверялась без ноды.

```kotlin
/** Нижний уровень: три операции над характеристиками. */
interface MeshGattClient {
    val fromNumNotifications: Flow<Unit>
    suspend fun awaitSubscriptionReady()
    suspend fun readFromRadio(): ByteArray
    suspend fun writeToRadio(bytes: ByteArray)
}

/** Верхний уровень: протокол. Чистый Kotlin, никакого Android. */
class MeshRadioProfile(private val client: MeshGattClient) {
    val fromRadio: Flow<ByteArray>
    suspend fun send(bytes: ByteArray)
}
```

Реализации `MeshGattClient`: `NordicMeshGattClient` (поверх `BleManager`, только на устройстве)
и `FakeMeshGattClient` (очередь пакетов + эмуляция notify, работает на JVM).

Сканирование вынесено в отдельный контракт, чтобы список устройств не зависел от транспорта:

```kotlin
interface BleScanner {
    /** Устройства, рекламирующие сервис Meshtastic. Поток живёт, пока на него подписаны. */
    fun scan(serviceUuid: UUID, address: String? = null): Flow<DeviceListEntry.Ble>
}
```

Экран устройств подписывается без фильтра по адресу; `BleRadioTransport` — с фильтром, чтобы
дождаться рекламы конкретной ноды перед коннектом. Скан обязательно останавливается в момент
выбора устройства: активный скан конкурирует с коннектом за радио.

`BleRadioTransport` реализует `RadioTransport` и отвечает за жизненный цикл: скан → бондинг →
коннект → `MeshRadioProfile` → реконнект; переводит исключения в `onDisconnect(isPermanent)`.

Бондинг у Nordic оформлен запросом `ensureBond()` в очереди операций. Если он отработает как
заявлено, отдельный менеджер бондинга с `BroadcastReceiver` и опросом `bondState` не понадобится;
проверяется на первом живом подключении.

### 5.4 Менеджер соединения

```kotlin
class RadioConnectionManager(
    private val factory: RadioTransportFactory,
    private val scope: CoroutineScope,
) : RadioTransportCallback {
    val connectionState: StateFlow<ConnectionState>   // Disconnected | Connecting | Connected
    val packets: Flow<FromRadio>                      // из Channel — строгий FIFO
    fun connect(address: String)
    suspend fun disconnect()
}
```

`Connecting` держится, пока не пришёл `config_complete_id` второй стадии — разделение
транспортного и прикладного состояния из исследования, реализованное в одном классе,
поскольку слой пока один.

Константы `CONFIG_NONCE = 69420` и `NODE_INFO_NONCE = 69421` лежат в `transport/MeshProtocol.kt` —
самом нижнем пакете, от которого зависят и фейк, и менеджер.

### 5.5 Константы протокола

| Назначение | UUID |
| :--- | :--- |
| Сервис Meshtastic | `6ba1b218-15a8-461f-9fa8-5dcae273eafd` |
| `TORADIO` (write) | `f75c76d2-129e-4dad-a1dd-7866124401e7` |
| `FROMRADIO` (read) | `2c55e69e-4993-11ed-b878-0242ac120002` |
| `FROMNUM` (notify) | `ed9da18c-a800-4f66-a670-aa7547e34453` |

Имя устройства Meshtastic: `^.*_([0-9a-fA-F]{4})$`. Максимальный размер кадра — 512 байт.

## 6. Потоки данных

### 6.1 Демо-путь и BLE-путь

```
ДЕМО                                    BLE
connect("m:5nodes")                     connect("xAA:BB:CC:DD:EE:FF")
   │ FakeRadioTransport.start()            │ BleRadioTransport.start()
   │ задержка ~300 мс                      │ 1. скан по service UUID, фильтр по MAC, 5 с
   │                                       │ 2. ensureBond() — если не спарено
   │                                       │ 3. connect + service discovery
   │                                       │    requestMtu(512), enableNotifications(FROMNUM),
   │                                       │    requestConnectionPriority(HIGH)
   │                                       │ 4. awaitSubscriptionReady() — CCCD записан
   │                                       │ 5. затравочный drain
   ▼                                       ▼
   └──────────► onConnect() ◄──────────────┘
                    │
                    ▼   RadioConnectionManager — не знает, кто под ним
```

### 6.2 Handshake

```
ToRadio(want_config_id = 69420)  ── СТАДИЯ 1 ──>
   <── FromRadio(my_info)                          первым: даёт номер локальной ноды
   <── FromRadio(metadata)                         версия прошивки
   <── FromRadio(config) × 7                       device/position/power/network/display/lora/bluetooth
   <── FromRadio(moduleConfig) × N
   <── FromRadio(channel) × 8
   <── FromRadio(config_complete_id = 69420)

ToRadio(want_config_id = 69421)  ── СТАДИЯ 2 ──>
   <── FromRadio(node_info) × N                    по числу нод в сценарии
   <── FromRadio(config_complete_id = 69421)

state = Connected
```

Далее фейк отвечает на `ToRadio(heartbeat)` пакетом `queueStatus`; другого трафика не шлёт.

### 6.3 Механика

**Пакеты идут с задержкой ~25 мс, а не мгновенным взрывом.** Мгновенная выдача скрыла бы гонки:
реальный транспорт всегда асинхронный. Задержка — параметр конструктора, в тестах равна нулю.

**Строгий FIFO через `Channel(capacity = 256)` + `receiveAsFlow()`.** Эмиссия каждого пакета
отдельной корутиной переупорядочивает поток и ломает загрузку конфига.

**`config_complete_id` эхом возвращает полученный нонс** — так делает прошивка; позволяет отличить
ответ на свой запрос от чужого при реконнекте.

**`FromRadio.id` проставляется инкрементом** — поле существует для дозапроса потерянных пакетов.

**Drain-цикл** (только BLE): нотификация FROMNUM запускает чтение `FROMRADIO` до пустого ответа.
Триггеры drain: нотификация, старт сессии, каждая запись в `TORADIO`. Затравочный drain обязателен —
прошивка не шлёт FROMNUM, пока не перешла в состояние отправки пакетов, то есть во время handshake
нотификаций нет.

**Порядок «бонд → коннект»**: без бонда прошивка, требующая шифрованный линк, даёт GATT status 5 или 133.

**`awaitSubscriptionReady()` перед handshake**: пока CCCD не записан, нотификации не придут, и
`want_config_id` уйдёт в пустоту — приложение зависнет в `Connecting` без ошибок в логе.

**Приоритет соединения** понижается до Balanced через 30 с после подключения: высокий нужен только
на первичную выкачку конфига.

## 7. Обработка ошибок и восстановление

**Битый кадр не убивает приёмный цикл.** `ToRadio.ADAPTER.decode()` бросает `IOException` на
некорректных байтах. Исключение логируется, кадр дропается, сбор потока продолжается. Это
единственная жила, через которую в приложение попадают данные.

**Неизвестный `ToRadio` фейк игнорирует** — debug-лог, без исключения.

**Watchdog handshake — 30 секунд.** Если после `onConnect()` не пришёл `config_complete_id` второй
стадии, состояние переходит в `Disconnected` с текстом ошибки.

**`connect()` идемпотентен.** Повторный вызов с тем же адресом при живом соединении — no-op;
с другим адресом — сначала `close()` старого транспорта, потом создание нового.

**Дроп-политика канала:** при переполнении отбрасывается новейший кадр (уже принятые сохраняют
порядок), ведётся счётчик отброшенных.

**Классификация GATT-статусов.** Фатальны для сессии: `8` (supervision timeout), `19` (нода
разорвала сама), `22` (LMP timeout), `62` (не удалось установить), `129`, `133` (протухший хендл).
Требуют полного teardown и нового коннекта. Остальное Nordic ретраит сам. Проверка идёт по цепочке
`cause` — исключения приходят обёрнутыми.

**Политика реконнекта:**

| Параметр | Значение |
| :--- | :--- |
| Пауза перед каждой попыткой | 3 с (при 1.5 с прошивка не успевает освободить GATT-сессию) |
| Backoff | 5 → 10 → 20 → 40 → 60 с (кап) |
| Порог стабильного соединения | 5 с |
| Предел попыток | нет, пока устройство выбрано пользователем |

**Liveness-детектор — только BLE.** Heartbeat раз в 30 с; тишина дольше 60 с при `Connected` →
принудительный рестарт транспорта. Лечит «зомби»-сессии, когда стек Android не сообщил о разрыве.
На демо-транспорт не распространяется.

**Разрешения и выключенный Bluetooth — состояния, а не исключения.** Отказ в `BLUETOOTH_SCAN` /
`BLUETOOTH_CONNECT`, выключенный адаптер, выключенная геолокация на Android до 12 — каждое даёт
понятный текст на экране устройств.

**Прощание и закрытие.** Перед разрывом отправляется `ToRadio(disconnect = true)` с окном ~500 мс
на дослать. Teardown идёт под `NonCancellable`: отменённый вызывающий не должен пропустить
освобождение GATT — прямой путь к утечке и статусу 133 при следующем подключении.

## 8. Тестирование

Проверяется на JVM, в CI, без устройства:

```
FakeRadioTransportTest
  • последовательность стадий, my_info первым, config_complete_id последним
  • config_complete_id эхом равен полученному нонсу
  • heartbeat → queueStatus
  • disconnect = true → onDisconnect(isPermanent = true)
  • неизвестный ToRadio не бросает исключение

MeshRadioProfileTest (с FakeMeshGattClient)
  • notify FROMNUM → чтение до пустого ответа, ни одним больше
  • затравочный drain срабатывает до первой нотификации
  • write в TORADIO триггерит drain
  • пачка нотификаций схлопывается в один цикл чтения
  • fromRadio не завершается на транзиентной ошибке чтения

RadioConnectionManagerTest
  • connect → две стадии → Connected; Connecting держится до второго config_complete_id
  • битый кадр логируется, поток живёт
  • watchdog 30 с → Disconnected
  • повторный connect на тот же адрес — no-op

Табличные тесты: классификация GATT-статусов, расчёт backoff
```

Задержки — через `kotlinx-coroutines-test`: виртуальное время, тесты мгновенные, но проходят
асинхронный путь.

**Не проверяется без железа:** `NordicMeshGattClient` поверх реального GATT, сканирование,
бондинг, запрос разрешений, согласование MTU, реконнект на настоящем разрыве. Проверяется только
вручную, на телефоне, рядом с нодой.

Инструментальных и Compose-тестов на этом этапе нет: один диагностический экран не окупает их
настройку.

## 9. Критерии готовности

**Этап 1 — шов, фейк, экраны:**

1. `./gradlew assembleDebug test` зелёный локально и в GitHub Actions;
2. на эмуляторе Android виден список демо-устройств, выбор доводит состояние до `Connected`;
3. лента показывает все кадры сценария в правильном порядке;
4. в release-сборке демо-устройств нет.

**Этап 2 — BLE:**

1. тесты `MeshRadioProfile` с фейковым GATT-клиентом зелёные;
2. на реальном телефоне запрашиваются разрешения, скан показывает ноды поблизости, выключенный
   Bluetooth даёт внятное сообщение;
3. *(отложенная приёмка, требует ноду)* подключение к ноде доходит до `Connected`, лента
   показывает реальные пакеты, уход из зоны и возврат отрабатывают реконнект.

## 10. Риски

**Основной риск: BLE-код пишется без возможности проверки.** Физической ноды нет, а BLE нельзя
эмулировать ни в CI, ни на эмуляторе Android. Разделение `MeshGattClient` / `MeshRadioProfile`
выносит протокольную логику в проверяемую зону, но слой поверх Nordic остаётся непроверенным до
появления железа. Риск принят осознанно; пункт 3 критериев этапа 2 отложен.

**Поведение `ensureBond()` у Nordic не подтверждено на практике.** Если запрос не покрывает
известные проблемы бондинга на Android, потребуется собственный менеджер с `BroadcastReceiver`
и опросом `bondState` (в эталоне это ~350 строк).

**Точный вид API Nordic 2.11 уточняется при реализации.** Дизайн фиксирует ответственности и
наши интерфейсы; конкретные вызовы `BleManager` (форма коллбэков инициализации, работа с очередью)
проверяются по документации на этапе 2.
