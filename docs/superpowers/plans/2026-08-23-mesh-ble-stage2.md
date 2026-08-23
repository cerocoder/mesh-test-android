# Подключение к ноде по Bluetooth — этап 2

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Приложение подключается к реальной ноде Meshtastic по Bluetooth LE — сканирует эфир, спаривается, ведёт GATT-обмен и переживает разрывы, — не меняя ничего выше транспортного шва.

**Architecture:** `BleRadioTransport` реализует уже существующий `RadioTransport` и встаёт второй веткой фабрики. Внутри BLE разделён на два уровня: `MeshGattClient` — четыре операции над характеристиками, и `MeshRadioProfile` — протокол поверх них (drain-цикл). Разделение позволяет проверить самую хрупкую часть протокола обычными JVM-тестами через `FakeMeshGattClient`, оставив непроверяемым только тонкий адаптер поверх Nordic.

**Tech Stack:** Kotlin, Nordic Android BLE Library 2.11.0 (+ ble-ktx), Nordic Scanner 1.6.0, Wire-модели `org.meshtastic:protobufs`, kotlinx.coroutines.

**Spec:** `docs/superpowers/specs/2026-08-22-ble-emulation-design.md`

**Предшествующий этап:** `docs/superpowers/plans/2026-08-22-mesh-emulator-stage1.md` — реализован полностью, CI зелёный. Даёт транспортный шов, эмулятор, менеджер соединения с handshake и watchdog, контейнер зависимостей и два экрана.

## Global Constraints

- Пакет приложения и applicationId: `com.cerocoder.meshtest`. Исходники в `app/src/main/kotlin/...`, тесты в `app/src/test/kotlin/...`.
- Инструментальная связка этапа 1 **не меняется** и держится целиком: AGP `9.3.1`, Gradle `9.7.1`, Kotlin `2.4.10`, Compose BOM `2026.06.01`, JDK `21`, `minSdk = 26`, `compileSdk = 37`, `targetSdk = 36`. Менять элементы поодиночке нельзя: `protobufs` требует compileSdk 37, тот поддерживается только AGP 9, AGP 9 несёт встроенный Kotlin и отвергает плагин `kotlin.android`, а AGP 8 несовместим с Gradle 9.6+.
- Новые зависимости, проверенные на совместимость по метаданным опубликованных артефактов: `no.nordicsemi.android:ble:2.11.0`, `no.nordicsemi.android:ble-ktx:2.11.0`, `no.nordicsemi.android.support.v18:scanner:1.6.0`. Ни одна не ограничивает `compileSdk` и версию AGP.
- **Транспортный шов заморожен.** `RadioTransport`, `RadioTransportCallback`, `RadioTransportFactory` не меняются ни в сигнатурах, ни в семантике. Единственная правка выше шва разрешена в Task 1 и делается до всего остального.
- Адресация: BLE — `"x<MAC>"`, демо — `"m:<id>"`. Обе ветки живут в `RadioTransportFactoryImpl`.
- UUID сервиса и характеристик Meshtastic (сверены с прошивкой v2.7.26):
  - сервис `6ba1b218-15a8-461f-9fa8-5dcae273eafd`
  - `TORADIO` (write) `f75c76d2-129e-4dad-a1dd-7866124401e7`
  - `FROMRADIO` (read) `2c55e69e-4993-11ed-b878-0242ac120002`
  - `FROMNUM` (notify) `ed9da18c-a800-4f66-a670-aa7547e34453`
- Имя устройства Meshtastic: `^.*_([0-9a-fA-F]{4})$`. Максимальный размер кадра — 512 байт.
- Пакеты `transport/`, `emulator/`, `connection/`, `ble/protocol/` тестируются на JVM: никаких Android API, кроме `android.util.Log`. Android-специфика живёт в `ble/nordic/` и юнит-тестами не покрывается.
- **Верификация двухуровневая.** JVM-тесты идут в CI, как на этапе 1. Слой поверх Nordic, сканирование, бондинг и реальный GATT проверяются только вручную, на телефоне рядом с нодой; шаги собраны в Task 9.
- Реализатор не запускает Gradle и не заявляет о прохождении тестов: локально нет ни Android SDK, ни Gradle. Шаги `Run: gradle …` выполняет CI после пуша; их результат прикладывает контроллер.
- Коммиты на текущей ветке; сообщения на русском, префиксы `feat:` / `fix:` / `test:` / `chore:` / `ci:` / `docs:`.

## Проверенный API Nordic

Сигнатуры взяты из опубликованных исходников `ble-2.11.0-sources.jar` и `ble-ktx-2.11.0-sources.jar`. Не изобретайте другие — этих достаточно.

| Что | Сигнатура |
| :--- | :--- |
| Точки переопределения `BleManager` | `protected abstract boolean isRequiredServiceSupported(BluetoothGatt gatt)`, `protected void initialize()`, `protected abstract void onServicesInvalidated()` |
| Подключение | `connect(device): ConnectRequest`, далее `.useAutoConnect(Boolean)`, `.retry(count, delayMs)`, `.timeout(ms)` |
| MTU | `requestMtu(Int): MtuRequest` |
| Нотификации | `setNotificationCallback(characteristic): ValueChangedCallback`, `enableNotifications(characteristic): WriteRequest` |
| Чтение и запись | `readCharacteristic(characteristic): ReadRequest`, `writeCharacteristic(characteristic, ByteArray, writeType): WriteRequest` |
| Бондинг | `ensureBond(): Request` |
| Корутины (ble-ktx) | `Request.suspend()`, `TimeoutableRequest.suspend()`, `ReadRequest.suspend(): Data`, `WriteRequest.suspend(): Data`, `MtuRequest.suspend(): Int` |
| Поток нотификаций | `ValueChangedCallback.asFlow(): Flow<Data>` |

`Data` — тип Nordic; байты извлекаются как `data.value` (`ByteArray?`).

---

## Структура файлов

```
app/src/main/kotlin/com/cerocoder/meshtest/
├── connection/ConnectionState.kt          ИЗМЕНЯЕТСЯ: Disconnected получает причину
├── ble/
│   ├── protocol/                          чистый Kotlin, тестируется на JVM
│   │   ├── MeshGattClient.kt              контракт: четыре операции над характеристиками
│   │   ├── MeshRadioProfile.kt            drain-цикл поверх клиента
│   │   └── BleSession.kt                  открытая сессия: клиент плюс закрытие
│   ├── nordic/                            Android-специфика, юнит-тестами не покрывается
│   │   ├── MeshBleManager.kt              подкласс BleManager: сервис, характеристики, MTU
│   │   ├── NordicMeshGattClient.kt        MeshGattClient поверх MeshBleManager
│   │   └── BleScannerImpl.kt              сканер поверх Nordic Scanner
│   ├── BleScanner.kt                      контракт сканера
│   ├── BluetoothAvailability.kt           разрешения и адаптер как состояние, не исключение
│   ├── ReconnectPolicy.kt                 экспоненциальный backoff, чистый Kotlin
│   └── BleRadioTransport.kt               жизненный цикл: скан → бонд → коннект → профиль → реконнект
├── transport/RadioTransportFactoryImpl.kt ИЗМЕНЯЕТСЯ: вторая ветка вместо error(...)
├── service/MeshForegroundService.kt       удерживает процесс живым при активном соединении
├── AppContainer.kt                        ИЗМЕНЯЕТСЯ: сканер, доступность, реальные устройства
└── ui/DeviceListScreen.kt                 ИЗМЕНЯЕТСЯ: реальные устройства и состояния разрешений

app/src/test/kotlin/com/cerocoder/meshtest/ble/
├── protocol/FakeMeshGattClient.kt         управляемый двойник: очередь кадров, эмуляция notify
├── protocol/MeshRadioProfileTest.kt       drain-цикл, затравка, ожидание CCCD, ошибки
├── protocol/FakeBleSession.kt             сессия-двойник: сценарии отказов подключения
├── BleRadioTransportTest.kt               цикл переподключения, порядок, освобождение
└── ReconnectPolicyTest.kt                 backoff, порог стабильности, счётчик
```

Разделение `ble/protocol/` и `ble/nordic/` — центральное решение плана. Всё, что можно проверить без железа, лежит слева от границы и покрывается тестами; справа остаётся адаптер, единственная задача которого — перевести вызовы Nordic в методы интерфейса.

---

### Task 1: Причина разрыва в состоянии соединения

Требование спеки §7 («состояние переходит в `Disconnected` с текстом ошибки»), отложенное на этапе 1. Делается первым: это изменение запечатанного типа **над** швом, и чем больше у него потребителей, тем дороже правка.

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshtest/connection/ConnectionState.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshtest/connection/RadioConnectionManager.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshtest/ui/DeviceListScreen.kt`
- Modify: `app/src/test/kotlin/com/cerocoder/meshtest/connection/RadioConnectionManagerTest.kt`

**Interfaces:**
- Consumes: существующий `ConnectionState` с объектами `Disconnected` / `Connecting` / `Connected`.
- Produces: `ConnectionState.Disconnected(val reason: String? = null)` как data-класс; `Connecting` и `Connected` остаются объектами.

- [ ] **Step 1: Изменить тип**

`ConnectionState.kt` целиком:

```kotlin
package com.cerocoder.meshtest.connection

/**
 * Прикладное состояние соединения.
 *
 * [Connecting] означает, что физическая связь есть, но конфигурация с ноды ещё
 * не выкачана. Пользователю нельзя показывать «подключено» до завершения второй
 * стадии handshake.
 */
sealed interface ConnectionState {

    /**
     * Связи нет.
     *
     * @param reason человекочитаемая причина, если разрыв произошёл не по воле
     *   пользователя: таймаут handshake, отказ в разрешении, выключенный адаптер.
     *   `null` означает намеренное отключение и не показывается как ошибка.
     */
    data class Disconnected(val reason: String? = null) : ConnectionState

    data object Connecting : ConnectionState

    data object Connected : ConnectionState
}
```

- [ ] **Step 2: Проставить причины в менеджере**

В `RadioConnectionManager.kt` заменить каждое использование `ConnectionState.Disconnected`:

- начальное значение `_connectionState` → `ConnectionState.Disconnected()`;
- ветка `catch (e: Throwable)` в `connect()` → `ConnectionState.Disconnected("не удалось создать транспорт: ${e.message}")`;
- в `disconnect()` → `ConnectionState.Disconnected()` — намеренный разрыв, причины нет;
- в `onDisconnect(isPermanent)` → `ConnectionState.Disconnected(if (isPermanent) "соединение разорвано" else null)`;
- в `startHandshakeWatchdog()` → `ConnectionState.Disconnected("нода не ответила на запрос конфигурации за $handshakeTimeout")`;
- в гейте идемпотентности `connect()` сравнение `_connectionState.value != ConnectionState.Disconnected` заменить на `_connectionState.value !is ConnectionState.Disconnected`.

Последняя правка обязательна: с data-классом сравнение на равенство с объектом не скомпилируется, а `is` проверяет именно нужное — «мы не в отключённом состоянии», независимо от причины.

- [ ] **Step 3: Показать причину в UI**

В `DeviceListScreen.kt` функция `stateLabel`:

```kotlin
private fun stateLabel(state: ConnectionState): String = when (state) {
    is ConnectionState.Disconnected ->
        state.reason?.let { "отключено: $it" } ?: "отключено"

    ConnectionState.Connecting -> "подключение (идёт handshake)"
    ConnectionState.Connected -> "подключено"
}
```

- [ ] **Step 4: Починить утверждения тестов**

В `RadioConnectionManagerTest.kt` каждое `assertEquals(ConnectionState.Disconnected, manager.connectionState.value)` заменить на проверку типа — причина в этих тестах не важна, и её появление не должно ломать утверждение:

```kotlin
        assertTrue(
            "ожидалось отключённое состояние, получено ${manager.connectionState.value}",
            manager.connectionState.value is ConnectionState.Disconnected,
        )
```

Ни одно другое утверждение не меняется. Если какой-то тест перестанет проходить по иной причине — сообщите, не подгоняйте его под код.

- [ ] **Step 5: Прогон в CI**

Run: `gradle :app:testDebugUnitTest`
Expected: PASS — все тесты этапа 1 остаются зелёными.

- [ ] **Step 6: Коммит**

```
git add app/src/main/kotlin/com/cerocoder/meshtest app/src/test/kotlin/com/cerocoder/meshtest
git commit -m "feat: причина разрыва в состоянии соединения"
```

---

### Task 2: Зависимости, разрешения и доступность Bluetooth

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/ble/BluetoothAvailability.kt`

**Interfaces:**
- Produces: `enum class BleReadiness { READY, PERMISSIONS_MISSING, ADAPTER_OFF, UNSUPPORTED }`; `class BluetoothAvailability(context: Context)` со свойством `requiredPermissions: Array<String>` и методом `check(): BleReadiness`.

- [ ] **Step 1: Добавить зависимости**

`gradle/libs.versions.toml`, в `[versions]`:

```toml
nordicBle = "2.11.0"
nordicScanner = "1.6.0"
```

в `[libraries]`:

```toml
nordic-ble = { module = "no.nordicsemi.android:ble", version.ref = "nordicBle" }
nordic-ble-ktx = { module = "no.nordicsemi.android:ble-ktx", version.ref = "nordicBle" }
nordic-scanner = { module = "no.nordicsemi.android.support.v18:scanner", version.ref = "nordicScanner" }
```

`app/build.gradle.kts`, в `dependencies`:

```kotlin
    implementation(libs.nordic.ble)
    implementation(libs.nordic.ble.ktx)
    implementation(libs.nordic.scanner)
```

- [ ] **Step 2: Разрешения в манифесте**

В `AndroidManifest.xml`, перед тегом `<application>`:

```xml
    <!-- Android 12 и новее -->
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

    <!-- До Android 12 сканирование требовало разрешения на геолокацию -->
    <uses-permission android:name="android.permission.BLUETOOTH"
        android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN"
        android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
        android:maxSdkVersion="30" />

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
```

Флаг `neverForLocation` обязателен: без него Android 12+ требует ещё и геолокацию, а координаты приложению не нужны.

- [ ] **Step 3: Написать проверку доступности**

`app/src/main/kotlin/com/cerocoder/meshtest/ble/BluetoothAvailability.kt`:

```kotlin
package com.cerocoder.meshtest.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Готовность подсистемы Bluetooth к работе. */
enum class BleReadiness {
    READY,
    PERMISSIONS_MISSING,
    ADAPTER_OFF,
    UNSUPPORTED,
}

/**
 * Отвечает на вопрос «можем ли мы сейчас сканировать и подключаться».
 *
 * Спека требует, чтобы отсутствие разрешений и выключенный адаптер были
 * состояниями, а не исключениями: пользователь должен увидеть понятную причину,
 * а не пустой список устройств.
 */
class BluetoothAvailability(private val context: Context) {

    /** Разрешения, которые нужно запросить на этой версии Android. */
    val requiredPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun check(): BleReadiness {
        val manager = context.getSystemService(BluetoothManager::class.java)
            ?: return BleReadiness.UNSUPPORTED
        val adapter = manager.adapter ?: return BleReadiness.UNSUPPORTED

        val granted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) return BleReadiness.PERMISSIONS_MISSING

        return if (adapter.isEnabled) BleReadiness.READY else BleReadiness.ADAPTER_OFF
    }
}
```

Если компиляция не найдёт `ContextCompat`, добавьте `implementation("androidx.core:core-ktx:1.15.0")` в зависимости и отметьте это в отчёте.

- [ ] **Step 4: Прогон в CI**

Run: `gradle :app:assembleDebug`
Expected: BUILD SUCCESSFUL — зависимости Nordic резолвятся, манифест валиден.

- [ ] **Step 5: Коммит**

```
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/kotlin/com/cerocoder/meshtest/ble
git commit -m "feat: зависимости Nordic, разрешения BLE и проверка доступности"
```

---

### Task 3: Контракт GATT-клиента и его двойник

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/ble/protocol/MeshGattClient.kt`
- Create: `app/src/test/kotlin/com/cerocoder/meshtest/ble/protocol/FakeMeshGattClient.kt`

**Interfaces:**
- Produces: `interface BleSession` с членами `client: MeshGattClient` и `suspend fun close()`; `interface MeshGattClient` с членами `fromNumNotifications: Flow<Unit>`, `suspend fun awaitSubscriptionReady()`, `suspend fun readFromRadio(): ByteArray`, `suspend fun writeToRadio(bytes: ByteArray)`; тестовый двойник `FakeMeshGattClient` с методами `enqueue(vararg frames: ByteArray)`, `emitNotification()`, свойствами `writes: List<ByteArray>`, `reads: Int` и флагом `failNextRead: Boolean`.

- [ ] **Step 1: Объявить контракт**

```kotlin
package com.cerocoder.meshtest.ble.protocol

import kotlinx.coroutines.flow.Flow

/**
 * Нижний уровень BLE: ровно те операции над характеристиками Meshtastic,
 * которые нужны протоколу, и ничего больше.
 *
 * Реализаций две: [com.cerocoder.meshtest.ble.nordic.NordicMeshGattClient] поверх
 * настоящего GATT и тестовый двойник. Благодаря этому drain-цикл — самая хрупкая
 * часть протокола — проверяется обычными JVM-тестами без Android и без ноды.
 */
interface MeshGattClient {

    /**
     * Уведомления характеристики FROMNUM.
     *
     * Значение не несёт смысла: прошивка сообщает лишь «есть данные», а сами
     * пакеты вычитываются через [readFromRadio].
     */
    val fromNumNotifications: Flow<Unit>

    /**
     * Приостанавливается до фактической записи CCCD, то есть до момента, когда
     * нотификации действительно включены.
     *
     * Без этого ожидания запрос конфигурации уходит в пустоту: прошивка ответит
     * нотификацией, которую некому принять, и приложение зависнет в Connecting.
     */
    suspend fun awaitSubscriptionReady()

    /** Одно чтение FROMRADIO. Пустой массив означает, что очередь пуста. */
    suspend fun readFromRadio(): ByteArray

    /** Запись одного закодированного ToRadio в TORADIO. */
    suspend fun writeToRadio(bytes: ByteArray)
}
```

- [ ] **Step 2: Объявить сессию**

`app/src/main/kotlin/com/cerocoder/meshtest/ble/protocol/BleSession.kt`:

```kotlin
package com.cerocoder.meshtest.ble.protocol

/**
 * Открытая сессия с нодой: готовый к работе клиент и способ её закрыть.
 *
 * Существует ради проверяемости. Транспорт получает функцию открытия сессии
 * извне, поэтому его цикл переподключения, порядок операций и освобождение
 * ресурсов тестируются на JVM — без Android, без Nordic и без ноды.
 */
interface BleSession {

    /** Клиент, через который идёт протокол. Валиден до вызова [close]. */
    val client: MeshGattClient

    /** Закрыть сессию и освободить ресурсы. Повторный вызов безопасен. */
    suspend fun close()
}
```

- [ ] **Step 3: Написать двойник**

`app/src/test/kotlin/com/cerocoder/meshtest/ble/protocol/FakeMeshGattClient.kt`:

```kotlin
package com.cerocoder.meshtest.ble.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.IOException

/**
 * Управляемый двойник GATT-клиента.
 *
 * Очередь кадров имитирует буфер прошивки: [readFromRadio] отдаёт их по одному
 * и возвращает пустой массив, когда очередь исчерпана — ровно как настоящая нода.
 */
class FakeMeshGattClient : MeshGattClient {

    private val queue = ArrayDeque<ByteArray>()
    private val notifications = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    private val subscriptionReady = CompletableDeferred<Unit>()

    override val fromNumNotifications: SharedFlow<Unit> = notifications

    /** Записанные приложением кадры, в порядке отправки. */
    val writes = mutableListOf<ByteArray>()

    /** Сколько раз протокол обратился к FROMRADIO. */
    var reads = 0
        private set

    /** Если true, следующее чтение бросит IOException и сбросит флаг. */
    var failNextRead = false

    /** Положить кадры в очередь «прошивки». */
    fun enqueue(vararg frames: ByteArray) {
        queue.addAll(frames)
    }

    /** Сымитировать уведомление FROMNUM. */
    suspend fun emitNotification() {
        notifications.emit(Unit)
    }

    /** Разрешить протоколу продолжить после ожидания CCCD. */
    fun markSubscriptionReady() {
        if (!subscriptionReady.isCompleted) subscriptionReady.complete(Unit)
    }

    override suspend fun awaitSubscriptionReady() {
        subscriptionReady.await()
    }

    override suspend fun readFromRadio(): ByteArray {
        reads++
        if (failNextRead) {
            failNextRead = false
            throw IOException("сымитированный сбой чтения")
        }
        return queue.removeFirstOrNull() ?: ByteArray(0)
    }

    override suspend fun writeToRadio(bytes: ByteArray) {
        writes += bytes
    }
}
```

- [ ] **Step 4: Прогон в CI**

Run: `gradle :app:compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL — двойник компилируется, интерфейс реализован полностью.

- [ ] **Step 5: Коммит**

```
git add app/src/main/kotlin/com/cerocoder/meshtest/ble/protocol app/src/test/kotlin/com/cerocoder/meshtest/ble/protocol
git commit -m "feat: контракт GATT-клиента и сессии Meshtastic, управляемый двойник"
```

---

### Task 4: Протокол — drain-цикл

Самая ценная задача плана: здесь живёт логика, которую в этапе 1 невозможно было написать, а на живой ноде отлаживать дорого.

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/ble/protocol/MeshRadioProfile.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshtest/ble/protocol/MeshRadioProfileTest.kt`

**Interfaces:**
- Consumes: `MeshGattClient` и `FakeMeshGattClient` из Task 3.
- Produces: `class MeshRadioProfile(client: MeshGattClient)` с членами `val fromRadio: Flow<ByteArray>` и `suspend fun send(bytes: ByteArray)`.

- [ ] **Step 1: Написать падающие тесты**

```kotlin
package com.cerocoder.meshtest.ble.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRadioProfileTest {

    private fun frame(marker: Byte) = byteArrayOf(marker, 0x01, 0x02)

    @Test
    fun `подписка вычитывает очередь до пустого ответа`() = runTest {
        val client = FakeMeshGattClient()
        client.enqueue(frame(1), frame(2), frame(3))
        val profile = MeshRadioProfile(client)
        val received = mutableListOf<ByteArray>()

        val job = launch { profile.fromRadio.take(3).toList(received) }
        client.markSubscriptionReady()
        client.emitNotification()
        advanceUntilIdle()
        job.join()

        assertEquals(3, received.size)
        assertArrayEquals(frame(1), received[0])
        assertArrayEquals(frame(3), received[2])
        // Четыре чтения: три кадра плюс одно пустое, закрывающее цикл.
        assertEquals(4, client.reads)
    }

    @Test
    fun `затравочное чтение выполняется без единой нотификации`() = runTest {
        val client = FakeMeshGattClient()
        client.enqueue(frame(7))
        val profile = MeshRadioProfile(client)
        val received = mutableListOf<ByteArray>()

        val job = launch { profile.fromRadio.take(1).toList(received) }
        client.markSubscriptionReady()
        advanceUntilIdle()
        job.join()

        assertArrayEquals(
            "прошивка не шлёт FROMNUM до состояния отправки пакетов — цикл обязан стартовать сам",
            frame(7),
            received.single(),
        )
    }

    @Test
    fun `отправка в TORADIO триггерит вычитывание ответа`() = runTest {
        val client = FakeMeshGattClient()
        val profile = MeshRadioProfile(client)
        val received = mutableListOf<ByteArray>()
        val job = launch { profile.fromRadio.take(1).toList(received) }
        client.markSubscriptionReady()
        advanceUntilIdle()

        client.enqueue(frame(9))
        profile.send(frame(8))
        advanceUntilIdle()
        job.join()

        assertArrayEquals(frame(8), client.writes.single())
        assertArrayEquals(frame(9), received.single())
    }

    @Test
    fun `транзиентная ошибка чтения не завершает поток`() = runTest {
        val client = FakeMeshGattClient()
        val profile = MeshRadioProfile(client)
        val received = mutableListOf<ByteArray>()
        val job = launch { profile.fromRadio.take(1).toList(received) }
        client.markSubscriptionReady()
        advanceUntilIdle()

        client.failNextRead = true
        client.emitNotification()
        advanceUntilIdle()

        client.enqueue(frame(5))
        client.emitNotification()
        advanceUntilIdle()
        job.join()

        assertArrayEquals(
            "после сбоя чтения поток обязан продолжить работу на следующей нотификации",
            frame(5),
            received.single(),
        )
    }

    @Test
    fun `протокол ждёт готовности подписки перед первым чтением`() = runTest {
        val client = FakeMeshGattClient()
        client.enqueue(frame(4))
        val profile = MeshRadioProfile(client)

        val job = launch { profile.fromRadio.take(1).toList() }
        advanceUntilIdle()

        assertEquals("до записи CCCD читать нельзя", 0, client.reads)

        client.markSubscriptionReady()
        advanceUntilIdle()
        job.join()

        assertTrue("после готовности подписки чтение обязано начаться", client.reads > 0)
    }
}
```

- [ ] **Step 2: Прогон в CI — тесты должны упасть**

Run: `gradle :app:testDebugUnitTest --tests "*MeshRadioProfileTest*"`
Expected: FAIL — `Unresolved reference: MeshRadioProfile`.

- [ ] **Step 3: Реализовать протокол**

```kotlin
package com.cerocoder.meshtest.ble.protocol

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Протокол обмена с нодой поверх характеристик.
 *
 * Устройство FROMNUM — не канал данных, а «звонок в дверь»: оно лишь сообщает,
 * что данные есть. Сами кадры вычитываются циклическими чтениями FROMRADIO, пока
 * не вернётся пустой массив.
 *
 * Триггеров у цикла три: нотификация FROMNUM, старт подписки и каждая запись в
 * TORADIO. Затравочный триггер обязателен — прошивка не шлёт FROMNUM, пока не
 * перешла в состояние отправки пакетов, то есть ровно во время handshake
 * нотификаций нет и читать нужно самим.
 */
class MeshRadioProfile(private val client: MeshGattClient) {

    // Один слот с вытеснением старого: пачка триггеров схлопывается в один
    // проход, писатели никогда не блокируются, устаревшие запросы не копятся.
    private val drainTriggers = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val fromRadio: Flow<ByteArray> = channelFlow {
        client.awaitSubscriptionReady()

        launch {
            client.fromNumNotifications.collect { drainTriggers.tryEmit(Unit) }
        }

        drainTriggers.tryEmit(Unit)

        drainTriggers.collect {
            var keepReading = true
            while (keepReading) {
                val packet = try {
                    client.readFromRadio()
                } catch (e: IOException) {
                    // Транзиентный сбой: прекращаем текущий проход, но поток
                    // остаётся живым и продолжит со следующего триггера.
                    Log.w(TAG, "ошибка чтения FROMRADIO, ждём следующего триггера", e)
                    keepReading = false
                    continue
                }
                if (packet.isEmpty()) keepReading = false else send(packet)
            }
        }
    }

    /** Отправить кадр и сразу запросить вычитывание: ответ обычно уже готов. */
    suspend fun send(bytes: ByteArray) {
        client.writeToRadio(bytes)
        drainTriggers.tryEmit(Unit)
    }

    private companion object {
        const val TAG = "MeshRadioProfile"
    }
}
```

- [ ] **Step 4: Прогон в CI — тесты должны пройти**

Run: `gradle :app:testDebugUnitTest --tests "*MeshRadioProfileTest*"`
Expected: PASS, 5 тестов.

- [ ] **Step 5: Коммит**

```
git add app/src/main/kotlin/com/cerocoder/meshtest/ble/protocol app/src/test/kotlin/com/cerocoder/meshtest/ble/protocol
git commit -m "feat: drain-цикл протокола Meshtastic поверх характеристик"
```

---

### Task 5: Политика переподключения

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/ble/ReconnectPolicy.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshtest/ble/ReconnectPolicyTest.kt`

**Interfaces:**
- Produces: `class ReconnectPolicy(minStableConnection: Duration = 5.seconds)` с методами `fun backoffFor(consecutiveFailures: Int): Duration`, `fun onOutcome(wasStable: Boolean, wasIntentional: Boolean): Int` и свойством `val settleDelay: Duration`.

- [ ] **Step 1: Написать падающие тесты**

`ReconnectPolicyTest.kt`:

```kotlin
package com.cerocoder.meshtest.ble

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class ReconnectPolicyTest {

    private val policy = ReconnectPolicy()

    @Test
    fun `задержка растёт вдвое и упирается в потолок`() {
        assertEquals(5.seconds, policy.backoffFor(1))
        assertEquals(10.seconds, policy.backoffFor(2))
        assertEquals(20.seconds, policy.backoffFor(3))
        assertEquals(40.seconds, policy.backoffFor(4))
        assertEquals(60.seconds, policy.backoffFor(5))
        assertEquals("потолок держится и дальше", 60.seconds, policy.backoffFor(12))
    }

    @Test
    fun `стабильное соединение обнуляет счётчик неудач`() {
        policy.onOutcome(wasStable = false, wasIntentional = false)
        policy.onOutcome(wasStable = false, wasIntentional = false)

        assertEquals(0, policy.onOutcome(wasStable = true, wasIntentional = false))
    }

    @Test
    fun `намеренный разрыв обнуляет счётчик даже при коротком соединении`() {
        policy.onOutcome(wasStable = false, wasIntentional = false)

        assertEquals(0, policy.onOutcome(wasStable = false, wasIntentional = true))
    }

    @Test
    fun `нестабильные соединения накапливают счётчик`() {
        assertEquals(1, policy.onOutcome(wasStable = false, wasIntentional = false))
        assertEquals(2, policy.onOutcome(wasStable = false, wasIntentional = false))
    }

    @Test
    fun `пауза перед попыткой измерена, а не выдумана`() {
        assertEquals(
            "при 1,5 с прошивка не успевает освободить свою GATT-сессию",
            3.seconds,
            policy.settleDelay,
        )
    }
}
```

- [ ] **Step 2: Прогон в CI — тесты должны упасть**

Run: `gradle :app:testDebugUnitTest --tests "*ReconnectPolicyTest*"`
Expected: FAIL — `Unresolved reference: ReconnectPolicy`.

- [ ] **Step 3: Реализовать политику**

```kotlin
package com.cerocoder.meshtest.ble

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Политика переподключения с экспоненциальным откатом.
 *
 * Значения не подобраны на глаз: пауза в 3 секунды перед каждой попыткой нужна
 * потому, что при 1,5 секунды прошивка не успевает освободить свою GATT-сессию,
 * и подключение срывается уже посреди handshake.
 */
class ReconnectPolicy(
    /** Короче этого соединение считается неудачной попыткой, а не разрывом. */
    val minStableConnection: Duration = 5.seconds,
) {

    /** Пауза перед каждой попыткой, включая первую. */
    val settleDelay: Duration = 3.seconds

    var consecutiveFailures: Int = 0
        private set

    /** Учесть исход попытки и вернуть текущее число неудач подряд. */
    fun onOutcome(wasStable: Boolean, wasIntentional: Boolean): Int {
        consecutiveFailures = if (wasIntentional || wasStable) 0 else consecutiveFailures + 1
        return consecutiveFailures
    }

    /** Задержка перед следующей попыткой: 5, 10, 20, 40, далее 60 секунд. */
    fun backoffFor(consecutiveFailures: Int): Duration {
        if (consecutiveFailures <= 0) return BASE_DELAY
        val multiplier = 1 shl (consecutiveFailures - 1).coerceAtMost(MAX_EXPONENT)
        return minOf(BASE_DELAY * multiplier, MAX_DELAY)
    }

    private companion object {
        val BASE_DELAY = 5.seconds
        val MAX_DELAY = 60.seconds
        const val MAX_EXPONENT = 4
    }
}
```

- [ ] **Step 4: Прогон в CI — тесты должны пройти**

Run: `gradle :app:testDebugUnitTest --tests "*ReconnectPolicyTest*"`
Expected: PASS, 5 тестов.

- [ ] **Step 5: Коммит**

```
git add app/src/main/kotlin/com/cerocoder/meshtest/ble app/src/test/kotlin/com/cerocoder/meshtest/ble
git commit -m "feat: политика переподключения с экспоненциальным откатом"
```

---

### Task 6: Адаптер поверх Nordic

Первая задача, которую нельзя проверить юнит-тестами: здесь начинается Android. Пишется по проверенным сигнатурам из раздела «Проверенный API Nordic».

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/ble/nordic/MeshBleManager.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/ble/nordic/NordicMeshGattClient.kt`

**Interfaces:**
- Consumes: `MeshGattClient` (Task 3).
- Produces: `suspend fun openNordicSession(context: Context, mac: String): BleSession`; `class MeshBleManager(context: Context) : BleManager(context)` с членами `suspend fun connectTo(device: BluetoothDevice, autoConnect: Boolean)`, `val notifications: Flow<Unit>`, `suspend fun awaitReady()`, `suspend fun read(): ByteArray`, `suspend fun write(bytes: ByteArray)`, `fun release()`; `class NordicMeshGattClient(manager: MeshBleManager) : MeshGattClient`.

- [ ] **Step 1: Написать менеджер**

```kotlin
package com.cerocoder.meshtest.ble.nordic

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.asFlow
import no.nordicsemi.android.ble.ktx.suspend
import java.util.UUID

/**
 * Подключение к ноде Meshtastic поверх Nordic BLE Library.
 *
 * Библиотека берёт на себя то, ради чего её и выбрали: очередь GATT-операций
 * (Android не переваривает параллельные запросы), ретраи, согласование MTU и
 * обходы ошибок отдельных производителей.
 */
class MeshBleManager(context: Context) : BleManager(context) {

    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    private var fromNum: BluetoothGattCharacteristic? = null

    private var subscriptionReady = CompletableDeferred<Unit>()

    /** Поток уведомлений FROMNUM: значение не важно, важен сам факт. */
    val notifications: Flow<Unit>
        get() = setNotificationCallback(fromNum).asFlow().map { }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(SERVICE_UUID) ?: return false
        toRadio = service.getCharacteristic(TORADIO_UUID)
        fromRadio = service.getCharacteristic(FROMRADIO_UUID)
        fromNum = service.getCharacteristic(FROMNUM_UUID)
        return toRadio != null && fromRadio != null && fromNum != null
    }

    override fun initialize() {
        // Android по умолчанию даёт ATT MTU 23, то есть 20 байт полезной нагрузки,
        // а кадры Meshtastic доходят до 512. Без этого запроса они не пролезут.
        requestMtu(MTU).enqueue()
        enableNotifications(fromNum)
            .done { subscriptionReady.complete(Unit) }
            .fail { _, status -> subscriptionReady.completeExceptionally(IllegalStateException("CCCD не записан, статус $status")) }
            .enqueue()
    }

    override fun onServicesInvalidated() {
        toRadio = null
        fromRadio = null
        fromNum = null
        subscriptionReady = CompletableDeferred()
    }

    /**
     * Подключиться к устройству.
     *
     * @param autoConnect для спаренного устройства без свежей рекламы обязателен:
     *   прямое подключение на Android часто отваливается со статусом 133, особенно
     *   если нода использует меняющийся адрес.
     */
    suspend fun connectTo(device: BluetoothDevice, autoConnect: Boolean) {
        connect(device)
            .useAutoConnect(autoConnect)
            .retry(CONNECT_RETRIES, CONNECT_RETRY_DELAY_MS)
            .timeout(CONNECT_TIMEOUT_MS)
            .suspend()
        ensureBond().suspend()
    }

    /** Приостановиться до фактической записи CCCD. */
    suspend fun awaitReady() {
        subscriptionReady.await()
    }

    suspend fun read(): ByteArray = readCharacteristic(fromRadio).suspend().value ?: ByteArray(0)

    suspend fun write(bytes: ByteArray) {
        writeCharacteristic(toRadio, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            .suspend()
    }

    /** Разорвать связь и освободить ресурсы библиотеки. */
    fun release() {
        try {
            close()
        } catch (e: Throwable) {
            Log.w(TAG, "ошибка при закрытии менеджера", e)
        }
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val TORADIO_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROMRADIO_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val FROMNUM_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")

        private const val MTU = 512
        private const val CONNECT_RETRIES = 3
        private const val CONNECT_RETRY_DELAY_MS = 200
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val TAG = "MeshBleManager"
    }
}
```

Если компилятор не примет `.done {}` / `.fail {}` на результате `enableNotifications(...)`, замените их на `.enqueue()` и вместо `subscriptionReady` дождитесь первой нотификации; отметьте отклонение в отчёте.

- [ ] **Step 2: Написать адаптер**

```kotlin
package com.cerocoder.meshtest.ble.nordic

import com.cerocoder.meshtest.ble.protocol.MeshGattClient
import kotlinx.coroutines.flow.Flow

/** [MeshGattClient] поверх настоящего GATT. Логики здесь нет — только перевод вызовов. */
class NordicMeshGattClient(private val manager: MeshBleManager) : MeshGattClient {

    override val fromNumNotifications: Flow<Unit> get() = manager.notifications

    override suspend fun awaitSubscriptionReady() = manager.awaitReady()

    override suspend fun readFromRadio(): ByteArray = manager.read()

    override suspend fun writeToRadio(bytes: ByteArray) = manager.write(bytes)
}
```

- [ ] **Step 3: Написать открыватель сессии**

`app/src/main/kotlin/com/cerocoder/meshtest/ble/nordic/NordicBleSession.kt`:

```kotlin
package com.cerocoder.meshtest.ble.nordic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.cerocoder.meshtest.ble.protocol.BleSession
import com.cerocoder.meshtest.ble.protocol.MeshGattClient

/** Сессия поверх [MeshBleManager]: держит менеджер и закрывает его. */
private class NordicBleSession(private val manager: MeshBleManager) : BleSession {

    override val client: MeshGattClient = NordicMeshGattClient(manager)

    override suspend fun close() = manager.release()
}

/**
 * Открыть сессию с нодой по MAC-адресу.
 *
 * Порядок здесь жёсткий: бондинг до подключения, затем GATT, затем ожидание
 * записи CCCD. Нарушение даёт либо статус 133, либо тихое зависание.
 */
@SuppressLint("MissingPermission")
suspend fun openNordicSession(context: Context, mac: String): BleSession {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        ?: error("Bluetooth недоступен на этом устройстве")
    val device: BluetoothDevice = adapter.getRemoteDevice(mac)
    val bonded = device.bondState == BluetoothDevice.BOND_BONDED

    val manager = MeshBleManager(context)
    try {
        // Спаренному устройству без свежей рекламы нужен терпеливый autoConnect.
        manager.connectTo(device, autoConnect = bonded)
        manager.awaitReady()
    } catch (e: Throwable) {
        manager.release()
        throw e
    }
    return NordicBleSession(manager)
}
```

- [ ] **Step 4: Прогон в CI**

Run: `gradle :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Юнит-тестов у этой задачи нет по устройству вещей: слой проверяется вручную в Task 10.

- [ ] **Step 5: Коммит**

```
git add app/src/main/kotlin/com/cerocoder/meshtest/ble/nordic
git commit -m "feat: адаптер GATT-клиента и открыватель сессии поверх Nordic"
```

---

### Task 7: Сканер эфира

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/ble/BleScanner.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/ble/nordic/BleScannerImpl.kt`

**Interfaces:**
- Consumes: `MeshBleManager.SERVICE_UUID` (Task 6); `DeviceListEntry.Ble(name, mac, bonded, rssi)` из этапа 1.
- Produces: `interface BleScanner { fun scan(): Flow<DeviceListEntry.Ble> }`; `class BleScannerImpl(context: Context) : BleScanner`.

- [ ] **Step 1: Объявить контракт**

```kotlin
package com.cerocoder.meshtest.ble

import com.cerocoder.meshtest.transport.DeviceListEntry
import kotlinx.coroutines.flow.Flow

/** Поиск нод Meshtastic в эфире. */
interface BleScanner {

    /**
     * Найденные устройства. Поток живёт, пока на него подписаны, и повторяет
     * устройство при каждом новом объявлении — потребитель обязан
     * дедуплицировать по адресу.
     */
    fun scan(): Flow<DeviceListEntry.Ble>
}
```

- [ ] **Step 2: Реализовать поверх Nordic Scanner**

```kotlin
package com.cerocoder.meshtest.ble.nordic

import android.annotation.SuppressLint
import android.content.Context
import com.cerocoder.meshtest.ble.BleScanner
import com.cerocoder.meshtest.transport.DeviceListEntry
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanFilter
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings
import android.os.ParcelUuid

/**
 * Сканер с аппаратным фильтром по сервису Meshtastic.
 *
 * Фильтр задаётся на уровне ОС, а не в коде: так радиомодуль не будит процесс
 * на каждое чужое объявление, а список не засоряется наушниками и часами.
 */
class BleScannerImpl(private val context: Context) : BleScanner {

    @SuppressLint("MissingPermission")
    override fun scan(): Flow<DeviceListEntry.Ble> = callbackFlow {
        val scanner = BluetoothLeScannerCompat.getScanner()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .setUseHardwareFilteringIfSupported(true)
            .build()
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(MeshBleManager.SERVICE_UUID))
                .build(),
        )

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result.toEntry())
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { trySend(it.toEntry()) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("сканирование не запустилось, код $errorCode"))
            }
        }

        scanner.startScan(filters, settings, callback)
        awaitClose { scanner.stopScan(callback) }
    }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toEntry(): DeviceListEntry.Ble = DeviceListEntry.Ble(
        name = device.name ?: "неизвестная нода",
        mac = device.address,
        bonded = device.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED,
        rssi = rssi,
    )
}
```

- [ ] **Step 3: Прогон в CI**

Run: `gradle :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Коммит**

```
git add app/src/main/kotlin/com/cerocoder/meshtest/ble
git commit -m "feat: сканер нод Meshtastic с аппаратным фильтром"
```

---

### Task 8: Транспорт BLE и его подключение к фабрике

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/ble/BleRadioTransport.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshtest/transport/RadioTransportFactoryImpl.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshtest/AppContainer.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshtest/ui/DeviceListScreen.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshtest/MainActivity.kt`

**Interfaces:**
- Consumes: `RadioTransport`, `RadioTransportCallback` (этап 1); `BleSession`, `MeshGattClient` (Task 3); `MeshRadioProfile` (Task 4); `ReconnectPolicy` (Task 5); `openNordicSession` (Task 6); `BleScanner` (Task 7); `BluetoothAvailability` (Task 2).
- Produces: `class BleRadioTransport(mac: String, callback: RadioTransportCallback, parentScope: CoroutineScope, policy: ReconnectPolicy = ReconnectPolicy(), now: () -> Long = { System.currentTimeMillis() }, openSession: suspend (mac: String) -> BleSession) : RadioTransport` — открытие сессии передаётся снаружи, ради проверяемости цикла переподключения; в `AppContainer` — `val scanner: BleScanner`, `val availability: BluetoothAvailability`.

- [ ] **Step 1: Написать падающие тесты цикла подключения**

`app/src/test/kotlin/com/cerocoder/meshtest/ble/protocol/FakeBleSession.kt`:

```kotlin
package com.cerocoder.meshtest.ble.protocol

/** Сессия-двойник: отдаёт управляемый клиент и запоминает факт закрытия. */
class FakeBleSession(override val client: FakeMeshGattClient = FakeMeshGattClient()) : BleSession {

    var closed = false
        private set

    override suspend fun close() {
        closed = true
    }
}
```

`app/src/test/kotlin/com/cerocoder/meshtest/ble/BleRadioTransportTest.kt`:

```kotlin
package com.cerocoder.meshtest.ble

import com.cerocoder.meshtest.ble.protocol.FakeBleSession
import com.cerocoder.meshtest.transport.RadioTransportCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private class RecordingCallback : RadioTransportCallback {
    var connects = 0
    var disconnects = 0
    val frames = mutableListOf<ByteArray>()

    override fun onConnect() {
        connects++
    }

    override fun onDisconnect(isPermanent: Boolean) {
        disconnects++
    }

    override fun onDataReceived(bytes: ByteArray) {
        frames += bytes
    }
}

class BleRadioTransportTest {

    private fun TestScope.scope(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `открытая сессия доводит кадры до коллбэка`() = runTest {
        val callback = RecordingCallback()
        val session = FakeBleSession()
        session.client.enqueue(byteArrayOf(1), byteArrayOf(2))
        val transport = BleRadioTransport(
            mac = "AA:BB:CC:DD:EE:FF",
            callback = callback,
            parentScope = scope(),
            now = { currentTime },
            openSession = { session },
        )

        transport.start()
        session.client.markSubscriptionReady()
        advanceTimeBy(4.seconds)
        advanceUntilIdle()

        assertEquals(1, callback.connects)
        assertEquals(2, callback.frames.size)
    }

    @Test
    fun `отказ открытия повторяется с откатом`() = runTest {
        val callback = RecordingCallback()
        var attempts = 0
        val transport = BleRadioTransport(
            mac = "AA:BB:CC:DD:EE:FF",
            callback = callback,
            parentScope = scope(),
            now = { currentTime },
            openSession = {
                attempts++
                throw IllegalStateException("нода недоступна")
            },
        )

        transport.start()
        advanceTimeBy(30.seconds)
        advanceUntilIdle()

        assertTrue("после отказа обязаны быть новые попытки, было $attempts", attempts >= 2)
        assertTrue("каждая неудача сообщается наверх", callback.disconnects >= 2)
    }

    @Test
    fun `сессия закрывается при завершении работы транспорта`() = runTest {
        val callback = RecordingCallback()
        val session = FakeBleSession()
        val transport = BleRadioTransport(
            mac = "AA:BB:CC:DD:EE:FF",
            callback = callback,
            parentScope = scope(),
            now = { currentTime },
            openSession = { session },
        )

        transport.start()
        session.client.markSubscriptionReady()
        advanceTimeBy(4.seconds)
        advanceUntilIdle()
        transport.close()
        advanceUntilIdle()

        assertTrue("незакрытая сессия — это утёкшее GATT-соединение", session.closed)
    }

    @Test
    fun `после close переподключение прекращается`() = runTest {
        val callback = RecordingCallback()
        var attempts = 0
        val transport = BleRadioTransport(
            mac = "AA:BB:CC:DD:EE:FF",
            callback = callback,
            parentScope = scope(),
            now = { currentTime },
            openSession = {
                attempts++
                FakeBleSession()
            },
        )

        transport.start()
        advanceTimeBy(10.seconds)
        val afterClose = attempts
        transport.close()

        advanceTimeBy(120.seconds)
        advanceUntilIdle()

        assertEquals("закрытый транспорт не имеет права оживать", afterClose, attempts)
    }
}
```

- [ ] **Step 2: Прогон в CI — тесты должны упасть**

Run: `gradle :app:testDebugUnitTest --tests "*BleRadioTransportTest*"`
Expected: FAIL — `Unresolved reference: BleRadioTransport`.

- [ ] **Step 3: Написать транспорт**

```kotlin
package com.cerocoder.meshtest.ble

import android.util.Log
import com.cerocoder.meshtest.ble.protocol.BleSession
import com.cerocoder.meshtest.ble.protocol.MeshRadioProfile
import com.cerocoder.meshtest.transport.RadioTransport
import com.cerocoder.meshtest.transport.RadioTransportCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Транспорт до реальной ноды по Bluetooth LE.
 *
 * Открытие сессии передаётся снаружи ([openSession]) — благодаря этому цикл
 * переподключения, порядок операций и освобождение ресурсов проверяются
 * обычными JVM-тестами, а всё, что знает про Android, живёт в `ble/nordic/`.
 */
class BleRadioTransport(
    private val mac: String,
    private val callback: RadioTransportCallback,
    parentScope: CoroutineScope,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val now: () -> Long = { System.currentTimeMillis() },
    private val openSession: suspend (mac: String) -> BleSession,
) : RadioTransport {

    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)

    @Volatile
    private var profile: MeshRadioProfile? = null

    override fun start() {
        scope.launch {
            while (isActive) {
                // Пауза перед каждой попыткой, включая первую: прошивке нужно время
                // освободить свою GATT-сессию, иначе подключение срывается посреди
                // handshake.
                delay(policy.settleDelay)
                val startedAt = now()
                runSession()
                val uptime = now() - startedAt
                val stable = uptime >= policy.minStableConnection.inWholeMilliseconds
                val failures = policy.onOutcome(wasStable = stable, wasIntentional = false)
                callback.onDisconnect(isPermanent = false)
                delay(policy.backoffFor(failures))
            }
        }
    }

    /** Одна попытка «подключиться и жить до разрыва». */
    private suspend fun runSession() {
        val session = try {
            openSession(mac)
        } catch (e: Throwable) {
            Log.w(TAG, "не удалось открыть сессию с $mac", e)
            return
        }

        try {
            val radio = MeshRadioProfile(session.client)
            profile = radio
            callback.onConnect()
            radio.fromRadio.collect { callback.onDataReceived(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "сессия завершилась ошибкой", e)
        } finally {
            profile = null
            // Незакрытая сессия — это утёкшее GATT-соединение и статус 133 при
            // следующей попытке. Закрытие не должно срываться отменой.
            withContext(NonCancellable) { session.close() }
        }
    }

    override fun send(bytes: ByteArray) {
        val radio = profile
        if (radio == null) {
            Log.w(TAG, "нет активного профиля, кадр отброшен")
            return
        }
        scope.launch {
            try {
                radio.send(bytes)
            } catch (e: Throwable) {
                Log.w(TAG, "запись в TORADIO не удалась", e)
            }
        }
    }

    override suspend fun close() {
        withContext(NonCancellable) { job.cancelAndJoin() }
    }

    private companion object {
        const val TAG = "BleRadioTransport"
    }
}
```

- [ ] **Step 4: Прогон в CI — тесты должны пройти**

Run: `gradle :app:testDebugUnitTest --tests "*BleRadioTransportTest*"`
Expected: PASS, 4 теста.

- [ ] **Step 5: Подключить к фабрике**

В `RadioTransportFactoryImpl.kt` заменить ветку, бросавшую ошибку. Конструктору фабрики добавляется `context: Context`:

```kotlin
        MeshProtocol.bleMacOrNull(address)?.let { mac ->
            return BleRadioTransport(
                mac = mac,
                callback = callback,
                parentScope = scope,
                openSession = { address -> openNordicSession(context, address) },
            )
        }
```

- [ ] **Step 6: Прокинуть контекст и сканер в контейнер**

`AppContainer.kt` — добавить контекст, доступность и сканер, сохранив всё существующее:

```kotlin
class AppContainer(
    private val context: Context,
    isDebugBuild: Boolean,
) {

    private val errors = CoroutineExceptionHandler { _, e ->
        Log.e("AppContainer", "необработанное исключение в области приложения", e)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errors)

    /** Состояние разрешений и адаптера — для экрана устройств. */
    val availability = BluetoothAvailability(context)

    /** Поиск нод в эфире. */
    val scanner: BleScanner = BleScannerImpl(context)

    private val factory: RadioTransportFactory =
        RadioTransportFactoryImpl(scope, isDebugBuild, context)

    val connectionManager = RadioConnectionManager(factory, scope)

    /** Демо-устройства только в debug; реальные приходят из сканера. */
    val devices: List<DeviceListEntry> =
        if (isDebugBuild) {
            Scenarios.all.map { DeviceListEntry.Demo(it.id, it.displayName) }
        } else {
            emptyList()
        }
}
```

`MeshTestApp.kt` — передать контекст приложения:

```kotlin
        container = AppContainer(applicationContext, BuildConfig.DEBUG)
```

- [ ] **Step 7: Показать реальные устройства**

В `MainActivity.kt`, внутри `setContent` рядом с существующим состоянием, добавить сканирование и запрос разрешений:

```kotlin
                    val context = LocalContext.current
                    val found = remember { mutableStateMapOf<String, DeviceListEntry.Ble>() }
                    var readiness by remember { mutableStateOf(container.availability.check()) }

                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions(),
                    ) { readiness = container.availability.check() }

                    LaunchedEffect(readiness) {
                        when (readiness) {
                            BleReadiness.PERMISSIONS_MISSING ->
                                permissionLauncher.launch(container.availability.requiredPermissions)

                            // Сканируем, пока экран жив. Дедупликация по адресу: устройство
                            // повторяется при каждом объявлении.
                            BleReadiness.READY ->
                                container.scanner.scan().collect { found[it.mac] = it }

                            BleReadiness.ADAPTER_OFF, BleReadiness.UNSUPPORTED -> Unit
                        }
                    }

                    val allDevices = container.devices + found.values.sortedBy { it.name }
```

и передать в экран `devices = allDevices` плюс новый параметр `readiness = readiness`.

В `DeviceListScreen.kt` добавить параметр `readiness: BleReadiness` и объяснение вместо пустого списка:

```kotlin
        val explanation = when (readiness) {
            BleReadiness.PERMISSIONS_MISSING -> "Нет разрешений Bluetooth — выдайте их, чтобы искать ноды."
            BleReadiness.ADAPTER_OFF -> "Bluetooth выключен — включите его, чтобы искать ноды."
            BleReadiness.UNSUPPORTED -> "Это устройство не поддерживает Bluetooth LE."
            BleReadiness.READY -> if (devices.isEmpty()) "Ноды поблизости не найдены. Поиск продолжается." else null
        }
        if (explanation != null) {
            Text(explanation, modifier = Modifier.padding(top = 16.dp))
        }
```

Причина отдельного объяснения на каждое состояние: пустой список сам по себе не отличает «нод рядом нет» от «мы не имеем права искать».

- [ ] **Step 8: Прогон в CI**

Run: `gradle :app:testDebugUnitTest`
Expected: PASS — четыре новых теста транспорта плюс всё, что было раньше.

Run: `gradle :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Коммит**

```
git add app/src/main/kotlin/com/cerocoder/meshtest app/src/test/kotlin/com/cerocoder/meshtest
git commit -m "feat: транспорт BLE и реальные устройства в списке"
```

---

### Task 9: Heartbeat и детектор тишины

Требование спеки §7. Функционально не опционально: прошивка держит собственный таймер простоя и рвёт связь, если приложение молчит. Детектор тишины закрывает вторую беду BLE — «зомби»-сессию, когда стек Android не сообщил о разрыве, а данных давно нет.

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshtest/connection/RadioConnectionManager.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshtest/connection/RadioConnectionManagerTest.kt`

**Interfaces:**
- Consumes: существующий `RadioConnectionManager`.
- Produces: конструктор получает два новых параметра со значениями по умолчанию — `heartbeatInterval: Duration = 30.seconds`, `silenceTimeout: Duration = 60.seconds`. Публичная поверхность класса не меняется.

- [ ] **Step 1: Написать падающие тесты**

Добавить в `RadioConnectionManagerTest.kt`, не трогая существующие тесты:

```kotlin
    @Test
    fun `после подключения heartbeat уходит по расписанию`() = runTest {
        val factory = TestFactory(scope())
        val manager = RadioConnectionManager(factory, scope())

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()
        val before = manager.packetLog.value.count { it.queueStatus != null }

        advanceTimeBy(31.seconds)
        advanceUntilIdle()

        assertTrue(
            "нода отвечает на heartbeat статусом очереди — значит он был отправлен",
            manager.packetLog.value.count { it.queueStatus != null } > before,
        )
    }

    @Test
    fun `молчание дольше таймаута разрывает зомби-сессию`() = runTest {
        val manager = RadioConnectionManager(
            factory = SilentAfterConnectFactory(),
            scope = scope(),
            silenceTimeout = 60.seconds,
        )

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()
        advanceTimeBy(61.seconds)
        advanceUntilIdle()

        assertTrue(
            "при тишине связь считается мёртвой, даже если стек молчит о разрыве",
            manager.connectionState.value is ConnectionState.Disconnected,
        )
    }
```

Плюс двойник, доводящий handshake до конца и затем замолкающий:

```kotlin
    /** Транспорт, который завершает handshake и после этого не шлёт ничего. */
    private class SilentAfterConnectTransport(private val callback: RadioTransportCallback) : RadioTransport {
        override fun start() {
            callback.onConnect()
        }

        override fun send(bytes: ByteArray) {
            val message = ToRadio.ADAPTER.decode(bytes)
            when (message.want_config_id) {
                MeshProtocol.CONFIG_NONCE ->
                    callback.onDataReceived(FromRadio(config_complete_id = MeshProtocol.CONFIG_NONCE).encode())

                MeshProtocol.NODE_INFO_NONCE ->
                    callback.onDataReceived(FromRadio(config_complete_id = MeshProtocol.NODE_INFO_NONCE).encode())
            }
        }

        override suspend fun close() = Unit
    }

    private class SilentAfterConnectFactory : RadioTransportFactory {
        override fun create(address: String, callback: RadioTransportCallback): RadioTransport =
            SilentAfterConnectTransport(callback)
    }
```

- [ ] **Step 2: Прогон в CI — тесты должны упасть**

Run: `gradle :app:testDebugUnitTest --tests "*RadioConnectionManagerTest*"`
Expected: FAIL — параметра `silenceTimeout` не существует, heartbeat не отправляется.

- [ ] **Step 3: Реализовать**

В конструктор добавить параметры:

```kotlin
    private val heartbeatInterval: Duration = 30.seconds,
    private val silenceTimeout: Duration = 60.seconds,
```

Поля и запуск наблюдения:

```kotlin
    private var keepAlive: Job? = null

    @Volatile
    private var lastFrameAt: Long = 0

    private val heartbeatNonce = AtomicInteger(0)

    /**
     * Поддержание связи и обнаружение «зомби»-сессии.
     *
     * Нонс обязан расти: у прошивки есть фильтр повторов на одинаковые записи,
     * и одинаковые байты она молча отбросит, а мы решим, что связь жива.
     */
    private fun startKeepAlive() {
        keepAlive?.cancel()
        lastFrameAt = System.currentTimeMillis()
        keepAlive = scope.launch {
            while (isActive) {
                delay(heartbeatInterval)
                sendToRadio(ToRadio(heartbeat = Heartbeat(nonce = heartbeatNonce.incrementAndGet())))
                val silence = System.currentTimeMillis() - lastFrameAt
                if (silence > silenceTimeout.inWholeMilliseconds) {
                    Log.w(TAG, "нет кадров $silence мс — считаем сессию мёртвой")
                    onDisconnect(isPermanent = false)
                    return@launch
                }
            }
        }
    }
```

Вызвать `startKeepAlive()` в ветке `NODE_INFO_NONCE` метода `onDataReceived()` сразу после перевода состояния в `Connected`; в `onDataReceived()` в самом начале, после проверки размера, обновлять `lastFrameAt = System.currentTimeMillis()`; отменять `keepAlive?.cancel()` там же, где отменяется `watchdog`, — в `disconnect()`, в `onDisconnect()` и в начале `connect()`.

Импорты: `kotlinx.coroutines.isActive`, `org.meshtastic.proto.Heartbeat`.

Замечание о времени: тесты используют виртуальное время `runTest`, а `System.currentTimeMillis()` — реальное. Чтобы тест тишины работал, замените источник времени на параметр конструктора `now: () -> Long = { System.currentTimeMillis() }` и используйте `now()` в обоих местах; в тесте тишины передайте `now = { currentTime }`, где `currentTime` — виртуальные часы `TestScope`. Без этой замены второй тест не сможет отличить тишину от мгновенного прохода.

- [ ] **Step 4: Прогон в CI — тесты должны пройти**

Run: `gradle :app:testDebugUnitTest`
Expected: PASS, все тесты включая два новых.

- [ ] **Step 5: Коммит**

```
git add app/src/main/kotlin/com/cerocoder/meshtest/connection app/src/test/kotlin/com/cerocoder/meshtest/connection
git commit -m "feat: heartbeat и обнаружение зомби-сессии"
```

### Task 10: Foreground-сервис и ручная приёмка

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/service/MeshForegroundService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/kotlin/com/cerocoder/meshtest/MainActivity.kt`

**Interfaces:**
- Consumes: `AppContainer.connectionManager` (этап 1).
- Produces: `class MeshForegroundService : Service()` с методами компаньона `fun start(context: Context)` и `fun stop(context: Context)`.

- [ ] **Step 1: Написать сервис**

Сервис не владеет соединением — оно живёт в контейнере уровня приложения. Его единственная задача: пока соединение активно, не дать системе убить процесс.

```kotlin
package com.cerocoder.meshtest.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Удерживает процесс живым, пока держится соединение с нодой.
 *
 * Само соединение принадлежит контейнеру уровня приложения: сервис — якорь
 * жизненного цикла, а не владелец. Без него Android усыпляет процесс, и связь
 * рвётся, как только пользователь уходит из приложения.
 */
class MeshForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Mesh Test")
            .setContentText("Соединение с нодой активно")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Соединение", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "mesh_connection"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MeshForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshForegroundService::class.java))
        }
    }
}
```

- [ ] **Step 2: Объявить сервис в манифесте**

Внутри `<application>`:

```xml
        <service
            android:name=".service.MeshForegroundService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />
```

- [ ] **Step 3: Связать с жизненным циклом соединения**

В `MainActivity.kt`, рядом с остальными эффектами:

```kotlin
                    LaunchedEffect(state) {
                        when (state) {
                            ConnectionState.Connecting,
                            ConnectionState.Connected,
                            -> MeshForegroundService.start(context)

                            is ConnectionState.Disconnected -> MeshForegroundService.stop(context)
                        }
                    }
```

Сервис поднимается уже на стадии подключения, а не после её завершения: handshake занимает секунды, и всё это время процесс должен быть защищён от усыпления.

- [ ] **Step 4: Прогон в CI**

Run: `gradle :app:testDebugUnitTest`
Expected: PASS.

Run: `gradle :app:assembleDebug` и `gradle :app:assembleRelease`
Expected: BUILD SUCCESSFUL для обеих.

- [ ] **Step 5: Коммит**

```
git add app/src/main/kotlin/com/cerocoder/meshtest/service app/src/main/AndroidManifest.xml app/src/main/kotlin/com/cerocoder/meshtest/MainActivity.kt
git commit -m "feat: foreground-сервис для удержания соединения"
```

- [ ] **Step 6: Ручная приёмка — выполняется человеком с телефоном и нодой**

Этот шаг не может выполнить ни агент, ни CI. Порядок проверки:

1. Установить debug-сборку на телефон, включить ноду рядом.
2. Открыть экран устройств. Приложение запрашивает разрешения Bluetooth — согласиться. В списке появляется нода с именем вида `Meshtastic_xxxx` и уровнем сигнала.
3. Выключить Bluetooth в системе: экран показывает «адаптер выключен», а не пустой список. Включить обратно — устройство возвращается.
4. Выбрать ноду. При первом подключении система показывает диалог спаривания — подтвердить. Состояние проходит `подключение (идёт handshake)` и доходит до `подключено`.
5. Открыть ленту пакетов: видны `MyNodeInfo`, `Metadata`, несколько `Config` и `Channel`, затем `NodeInfo` по числу известных ноде узлов и два подтверждения стадий.
6. Свернуть приложение на минуту — в шторке висит уведомление сервиса, соединение сохраняется, лента продолжает пополняться.
7. Унести ноду из зоны действия либо выключить её: состояние уходит в `отключено: соединение разорвано`, затем приложение само повторяет попытки. Вернуть ноду — соединение восстанавливается без участия пользователя.
8. Нажать «Отключиться»: состояние `отключено` без текста причины, уведомление сервиса исчезает.
9. Собрать release-вариант: демо-устройств в списке нет, реальная нода по-прежнему находится и подключается.

Любое расхождение с этим сценарием фиксируется как находка и разбирается до вливания ветки.

---

## Что остаётся за рамками этапа 2

Осознанно не входит и требует отдельного решения: обновление прошивки по воздуху, MQTT-шлюз, карта, чат и база нод в Room.

Пороги heartbeat (30 с) и тишины (60 с) взяты из эталонной реализации и вынесены в параметры конструктора именно потому, что проверить их можно только на живой ноде. Если приёмка покажет ложные срабатывания, правится значение, а не логика.
