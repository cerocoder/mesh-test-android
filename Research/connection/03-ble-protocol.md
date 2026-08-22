# BLE-протокол Meshtastic — практическая шпаргалка

Выжимка из кода v2.8.0 — минимум, необходимый, чтобы написать собственного BLE-клиента ноды.

## 1. Идентификаторы

### Имя устройства

```
^.*_([0-9a-fA-F]{4})$          например: Meshtastic_a1b2
```
Последние 4 hex-символа — суффикс, совпадающий с хвостом ID ноды (`node.user.id`). Приложение по нему
сопоставляет BLE-устройство с записью в базе нод (второй вариант сопоставления — последние 8 символов MAC
без двоеточий).

### Сервис и характеристики (`MeshtasticBleConstants`)

| Назначение | UUID | Операции |
| :--- | :--- | :--- |
| **Сервис Meshtastic** | `6ba1b218-15a8-461f-9fa8-5dcae273eafd` | — |
| `TORADIO` — отправка на ноду | `f75c76d2-129e-4dad-a1dd-7866124401e7` | write (предпочтительно **without response**) |
| `FROMRADIO` — чтение с ноды | `2c55e69e-4993-11ed-b878-0242ac120002` | read |
| `FROMNUM` — «есть данные» | `ed9da18c-a800-4f66-a670-aa7547e34453` | notify (CCCD) |
| `LOGRADIO` — логи прошивки | `5a3d6e49-06e6-4423-9944-e9de8cdf9547` | notify, опционально |

### OTA-сервис (обновление прошивки, ESP32 Unified OTA)

| Назначение | UUID |
| :--- | :--- |
| OTA-сервис | `4FAFC201-1FB5-459E-8FCC-C5C9C331914B` |
| OTA write (команды + данные) | `62ec0272-3ec5-11eb-b378-0242ac130005` |
| OTA notify (статус/ACK) | `62ec0272-3ec5-11eb-b378-0242ac130003` |

Наличие характеристик проверяется перед использованием (`hasCharacteristic`) — у разных версий прошивки набор
отличается, `LOGRADIO` может отсутствовать.

## 2. Формат данных

* По BLE передаются **«сырые» protobuf-сообщения без обёртки кадра**: один `read(FROMRADIO)` = ровно одно
  сообщение `FromRadio`; одна `write(TORADIO)` = ровно одно `ToRadio`.
  (Рамочный заголовок `0x94 0xC3 <len_hi> <len_lo>` — это про **потоковые** транспорты, TCP и USB-Serial;
  в BLE его нет.)
* Пустой ответ (`ByteArray.isEmpty()`) на чтение `FROMRADIO` = очередь пуста, опрос прекращается.
* Верхний предел размера кадра, принятый в приложении: **512 байт** (`MAX_FRAME_BYTES`).
* Protobuf-схемы берутся из артефакта `org.meshtastic:protobufs` (`ToRadio`, `FromRadio`, `MeshPacket`,
  `Heartbeat`, `Telemetry`, …), т.е. из upstream-репозитория прошивки.

## 3. MTU

* Android по умолчанию даёт ATT MTU 23 → 20 байт полезной нагрузки.
* Приложение запрашивает `requestMtu(512)` сразу в `onServicesDiscovered`. Без этого пакеты Meshtastic
  (до 512 байт) будут дробиться/теряться.
* Полезная длина = MTU − 3 (ATT-заголовок). Константа на случай, если MTU не согласован:
  `DEFAULT_BLE_WRITE_VALUE_LENGTH = 20`.

## 4. Алгоритм обмена (обязательный порядок)

```
1. connect (GATT)
2. service discovery
3. requestMtu(512)
4. subscribe(FROMNUM)                     ← подписка на нотификации
5. ДОЖДАТЬСЯ фактической записи CCCD      ← иначе нотификации не придут; таймаут ~5 с
6. (опц.) subscribe(LOGRADIO)
7. drain: while (true) { p = read(FROMRADIO); if (p.isEmpty()) break; handle(p) }
8. handshake:
      write TORADIO: ToRadio(heartbeat = Heartbeat(nonce = n++))
      пауза ~100 мс
      write TORADIO: ToRadio(want_config_id = 69420)   // Stage 1: конфиг + модули + каналы
      … принять поток FromRadio до config_complete_id …
      write TORADIO: ToRadio(want_config_id = 69421)   // Stage 2: база нод
      … принять NodeInfo … 
9. рабочий режим:
      notify FROMNUM  →  drain (см. п.7)
      write TORADIO   →  drain (ответ обычно уже готов)
      каждые 30 с     →  ToRadio(heartbeat), затем через 200 мс drain
10. завершение: write TORADIO: ToRadio(disconnect = true), пауза ~500 мс, disconnect + close
```

### Почему именно так

| Правило | Причина |
| :--- | :--- |
| Ждать CCCD перед handshake | Иначе `want_config_id` уйдёт раньше, чем включены нотификации, и ответ «повиснет» до случайного следующего FROMNUM |
| Затравочный drain до первой нотификации | Прошивка выдаёт FROMNUM только в состоянии `STATE_SEND_PACKETS`; во время handshake нужно опрашивать активно |
| Drain после каждой записи в TORADIO | Ответ обычно уже стоит в очереди, но FROMNUM на него может не прийти |
| Пауза 200 мс после heartbeat | ESP32 обрабатывает запись асинхронно (NimBLE → очередь FreeRTOS → `handleToRadio()`), ответ `queueStatus` появляется позже |
| Монотонный nonce у heartbeat | У прошивки per-connection фильтр дубликатов записи — одинаковые байты молча отбрасываются |
| Heartbeat перед `want_config_id` | Разбудить нодy из энергосберегающего состояния и «прогреть» NimBLE-контекст |
| **Не повторять `want_config_id` в той же сессии** | `handleStartConfig()` в прошивке не защищён от повторного входа — воспроизведён краш на T-Beam. Восстановление только через полный рестарт соединения |
| Пауза ≥ 3 с между disconnect и reconnect | Прошивке нужно время освободить свою GATT-сессию; при 1.5 с 3–4 попытки из 5 падают посреди handshake |

## 5. Что означают статусы разрыва

| GATT status | Значение | Реакция приложения |
| :--- | :--- | :--- |
| 8 (`GATT_CONN_TIMEOUT`) | supervision timeout — ушли из зоны | реконнект с backoff |
| **19** (`GATT_CONN_TERMINATE_PEER_USER`) | нода сама разорвала (ребут, выключение) — **самый частый** | реконнект |
| 22 (`GATT_CONN_LMP_TIMEOUT`) | зависание радио/прошивки | реконнект |
| 62 (`GATT_CONN_FAIL_ESTABLISH`) | не удалось установить связь | реконнект |
| 129 (`GATT_FAILURE`), 133 (`GATT_ERROR`) | «протухший» хендл, типичная Android-беда | полный teardown + реконнект |
| 5 | нет бонда / нужна аутентификация | сначала bond, затем коннект |

Все шесть перечисленных как фатальные (`FATAL_GATT_STATUSES`) означают: **сессию чинить бессмысленно,
нужен полный цикл disconnect → close → connect.**

## 6. Адресация (соглашение приложения)

Внутренний идентификатор устройства — строка с однобуквенным префиксом транспорта:

```
"x" + MAC     BLE          → "xAA:BB:CC:DD:EE:FF"
"t" + host    TCP/WiFi
"s" + key     USB-Serial
"m" / "r" / "n"             mock / replay / nop (только debug)
```

Полезно повторить: одна строка адреса определяет и транспорт, и устройство, поэтому «выбор способа
подключения» в UI и в бизнес-логике не требует отдельного поля.
