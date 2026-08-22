# Исследование: подключение к ноде Meshtastic по Bluetooth (Meshtastic-Android v2.8.0)

Разбор эталонного открытого приложения: **https://github.com/meshtastic/Meshtastic-Android/tree/v2.8.0**
(тег `v2.8.0`, `VERSION_NAME_BASE=2.8.0`, минимальная поддерживаемая прошивка `MIN_FW_VERSION=2.5.14`).

Исследованы два вопроса:
1. Как реализовано подключение к ноде по **Bluetooth LE** (сканирование → бондинг → GATT → обмен пакетами → переподключение).
2. В какой **архитектуре** это всё живёт.

Приложение поддерживает три транспорта — BLE, TCP/WiFi, USB-Serial — но здесь разобран только BLE;
остальные упомянуты лишь там, где важна общая абстракция.

## Состав

| Файл | Что внутри |
| :--- | :--- |
| [`01-architecture.md`](01-architecture.md) | Архитектура приложения: KMP, модули, слои, DI, жизненный цикл, потоки данных |
| [`02-ble-connection.md`](02-ble-connection.md) | Полный разбор BLE-подключения: от списка устройств до реконнекта и heartbeat |
| [`03-ble-protocol.md`](03-ble-protocol.md) | Спецификация BLE-протокола Meshtastic: UUID, характеристики, алгоритм обмена, handshake |
| [`04-lessons-and-checklist.md`](04-lessons-and-checklist.md) | Грабли, инварианты и чек-лист для собственной реализации |
| [`code/`](code/) | Важные фрагменты кода из оригинального репозитория (см. ниже) |

## Фрагменты кода (`code/`)

Файлы приведены **без изменений**, срезан только GPL-заголовок; в шапке каждого — путь к оригиналу.

| Файл | Оригинал | Зачем |
| :--- | :--- | :--- |
| `01-MeshtasticBleConstants.kt` | `core/ble/.../MeshtasticBleConstants.kt` | UUID сервиса и характеристик |
| `02-MeshtasticRadioProfile.kt` | `core/ble/.../MeshtasticRadioProfile.kt` | Контракт «радио-профиля» (fromRadio/sendToRadio) |
| `03-KableMeshtasticRadioProfile.kt` | `core/ble/.../KableMeshtasticRadioProfile.kt` | **Ядро протокола**: FROMNUM-notify → drain-цикл чтения FROMRADIO |
| `04-BleConnection.kt` | `core/ble/.../BleConnection.kt` | Платформенно-независимые абстракции BLE-соединения и GATT-сервиса |
| `05-KableBleConnection.kt` | `core/ble/.../KableBleConnection.kt` | Реализация на Kable: connect/disconnect/profile, direct → autoConnect fallback |
| `06-KableBleScanner.kt` | `core/ble/.../KableBleScanner.kt` | Сканирование + классификация ошибок старта скана |
| `07-KablePlatformSetup.android.kt` | `core/ble/androidMain/.../KablePlatformSetup.kt` | Android-специфика: MTU 512, connection priority, autoConnect |
| `08-AndroidBluetoothRepository.kt` | `core/ble/androidMain/.../AndroidBluetoothRepository.kt` | Бондинг (createBond + BroadcastReceiver + polling), список bonded |
| `09-BleRadioTransport.kt` | `core/network/.../radio/BleRadioTransport.kt` | **Ядро жизненного цикла**: поиск, бонд, коннект, подписки, реконнект, heartbeat |
| `10-BleReconnectPolicy.kt` | `core/network/.../radio/BleReconnectPolicy.kt` | Политика переподключения с экспоненциальным backoff |
| `11-BleExceptionClassifier.kt` | `core/ble/.../BleExceptionClassifier.kt` | Классификация GATT-ошибок: фатально для сессии или нет |
| `12-BleRetry.kt` | `core/ble/.../BleRetry.kt` | Ретраи BLE-операций с backoff + jitter |
| `13-KableStateMapping.kt` | `core/ble/.../KableStateMapping.kt` | Маппинг состояний/причин дисконнекта Kable → доменные |
| `14-AndroidBleScanStartLimiter.kt` | `core/ble/androidMain/.../AndroidBleScanStartLimiter.kt` | Обход квоты Android «5 стартов скана / 30 с» |
| `15-BaseRadioTransportFactory.kt` | `core/network/.../radio/BaseRadioTransportFactory.kt` | Выбор транспорта по префиксу адреса |
| `16-HeartbeatSender.kt` | `core/network/.../transport/HeartbeatSender.kt` | Heartbeat с монотонным nonce |
| `17-SharedRadioInterfaceService-fragments.kt` | `core/service/.../SharedRadioInterfaceService.kt` | Владение транспортом, сессии, liveness, очередь входящих кадров |
| `18-ScannerViewModel-fragments.kt` | `feature/connections/.../ScannerViewModel.kt` | UI-слой: список устройств, скан, выбор устройства |
| `19-transport-contracts.kt` | сводка интерфейсов | `RadioTransport`, `RadioTransportCallback`, `BluetoothRepository`, `InterfaceId`, handshake-nonce |

## TL;DR — что нужно знать, чтобы написать своё приложение

1. **Транспорт BLE тривиален, сложность — в жизненном цикле.** Самих GATT-операций мало: 1 сервис,
   4 характеристики, запись в одну и чтение из другой. Основной объём кода (~850 строк одного только
   `BleRadioTransport`) — это переподключение, дедупликация ошибок, защита от «зомби»-сессий и гонок.
2. **Протокол обмена — polling, а не push.** Нода уведомляет через `FROMNUM` лишь о том, что *есть данные*;
   пакеты вычитываются циклическими read'ами `FROMRADIO`, пока не вернётся пустой массив.
3. **Порядок операций жёсткий**: bond → connect → service discovery → подписка на FROMNUM → **дождаться записи
   CCCD** → и только потом `want_config_id`. Нарушение порядка даёт «тихие» зависания handshake.
4. **Android BLE требует явных обходных манёвров**: MTU 512, `autoConnect=true` для bonded-устройств без
   свежей рекламы, обязательный `close()` GATT, лимит на частоту сканов, `refresh()` кэша сервисов через рефлексию.
5. **Два уровня состояния соединения** (транспортное и прикладное) — UI обязан смотреть только на прикладное.

**Источник исследования:** склонирован `git clone --depth 1 --branch v2.8.0`; все ссылки на строки актуальны для этого тега.
