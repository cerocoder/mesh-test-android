# Эмуляция ноды Meshtastic — этап 1: шов, фейк, экраны

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android-приложение с транспортным швом и фейковым транспортом, который эмулирует ноду Meshtastic: отвечает на двухстадийный handshake из заранее заданного сценария, доводит приложение до состояния `Connected` и показывает поток разобранных пакетов — всё без Bluetooth и без физической ноды.

**Architecture:** Один Gradle-модуль, границы пакетами. Три интерфейса (`RadioTransport` / `RadioTransportCallback` / `RadioTransportFactory`) образуют шов; выше него `RadioConnectionManager` ведёт handshake и публикует состояние, ниже — `FakeRadioTransport`, кодирующий кадры из `MeshScenario`. Реализация BLE (этап 2) встанет второй веткой фабрики без изменений выше шва.

**Tech Stack:** Kotlin, Jetpack Compose, Wire-модели `org.meshtastic:protobufs`, kotlinx-coroutines, ручной DI.

**Spec:** `docs/superpowers/specs/2026-08-22-ble-emulation-design.md`

## Global Constraints

- Пакет приложения: `com.cerocoder.meshtest`. Application ID тот же.
- `minSdk = 26`, `compileSdk = 36`, `targetSdk = 36`.
- Инструменты: AGP `9.3.1`, Kotlin `2.4.10`, Compose BOM `2026.08.00`, JDK `21`.
- Протокол: `org.meshtastic:protobufs:2.7.26` (Wire-модели, пакет `org.meshtastic.proto`), явно добавить `com.squareup.wire:wire-runtime:6.4.5` в compile-классpath.
- Тесты: `org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0`.
- Нонсы handshake: `CONFIG_NONCE = 69420`, `NODE_INFO_NONCE = 69421`. Максимальный размер кадра — 512 байт.
- Адресация: демо — `"m:<id-сценария>"`, BLE — `"x<MAC>"` (в этапе 1 не реализуется).
- Демо-устройства доступны **только в debug-сборке**; в release список пуст.
- Пакеты `transport/`, `emulator/`, `connection/` тестируются на JVM: никаких Android API, кроме `android.util.Log`.
- В этапе 1 **нет**: BLE, foreground-сервиса, Hilt, Room, разрешений, инструментальных и Compose-тестов.
- Все коммиты — на текущей ветке; сообщения на русском, префиксы `feat:` / `test:` / `chore:`.

---

## Структура файлов

```
settings.gradle.kts                             корневые настройки Gradle
build.gradle.kts                                плагины верхнего уровня
gradle/libs.versions.toml                       каталог версий — единственное место с номерами версий
app/build.gradle.kts                            конфигурация модуля, зависимости, unitTests.isReturnDefaultValues
app/src/main/AndroidManifest.xml                манифест без разрешений
app/src/main/kotlin/com/cerocoder/meshtest/
  MeshTestApp.kt                                Application, создаёт AppContainer
  AppContainer.kt                               ручной DI: scope, фабрика, менеджер, список устройств
  MainActivity.kt                               Compose-хост, переключение между двумя экранами
  transport/
    RadioTransport.kt                           три интерфейса шва
    DeviceListEntry.kt                          модель пункта списка устройств + адрес
    MeshProtocol.kt                             нонсы, лимиты, разбор адреса
    FakeRadioTransport.kt                       эмулятор ноды поверх сценария
    RadioTransportFactoryImpl.kt                выбор реализации по префиксу адреса
  emulator/
    MeshScenario.kt                             данные сценария + сборка кадров стадий
    Scenarios.kt                                готовые наборы сценариев
  connection/
    ConnectionState.kt                          Disconnected / Connecting / Connected
    RadioConnectionManager.kt                   владелец транспорта, handshake, watchdog, лог пакетов
  ui/
    PacketFormatter.kt                          FromRadio -> строка (чистая функция)
    DeviceListScreen.kt                         список устройств и состояние соединения
    PacketLogScreen.kt                          лента принятых кадров
app/src/test/kotlin/com/cerocoder/meshtest/
  ProtobufSmokeTest.kt
  transport/MeshProtocolTest.kt
  transport/FakeRadioTransportTest.kt
  emulator/ScenariosTest.kt
  connection/RadioConnectionManagerTest.kt
  ui/PacketFormatterTest.kt
.github/workflows/build.yml                     сборка APK + прогон тестов
```

Разделение по ответственности, а не по слою: сценарии лежат рядом с эмулятором, который их проигрывает; разбор адреса — рядом с фабрикой, которая его использует.

---

### Task 1: Каркас проекта, зависимости и CI

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/com/cerocoder/meshtest/MainActivity.kt`
- Create: `.github/workflows/build.yml`, `.gitignore`
- Test: `app/src/test/kotlin/com/cerocoder/meshtest/ProtobufSmokeTest.kt`

**Interfaces:**
- Consumes: ничего.
- Produces: рабочий Gradle-проект; доступные в коде Wire-модели из пакета `org.meshtastic.proto`.

- [ ] **Step 1: Создать Gradle-проект**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "mesh-test-android"
include(":app")
```

`gradle/libs.versions.toml`:

```toml
[versions]
agp = "9.3.1"
kotlin = "2.4.10"
composeBom = "2026.08.00"
coroutines = "1.11.0"
protobufs = "2.7.26"
wire = "6.4.5"
activityCompose = "1.13.0"

[libraries]
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
meshtastic-protobufs = { module = "org.meshtastic:protobufs", version.ref = "protobufs" }
wire-runtime = { module = "com.squareup.wire:wire-runtime", version.ref = "wire" }
junit = { module = "junit:junit", version = "4.13.2" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

`build.gradle.kts` (корневой):

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
```

`.gitignore`:

```
*.iml
.gradle/
/local.properties
/.idea/
.DS_Store
/build
/app/build
/captures
.externalNativeBuild
.cxx
```

Сгенерировать Gradle wrapper: `gradle wrapper --gradle-version 8.14` (или версию, которую требует AGP 9.3.1 — если Gradle сообщит о несовместимости, поднять до указанной им).

- [ ] **Step 2: Написать падающий smoke-тест**

`app/src/test/kotlin/com/cerocoder/meshtest/ProtobufSmokeTest.kt`:

```kotlin
package com.cerocoder.meshtest

import org.junit.Assert.assertEquals
import org.junit.Test
import org.meshtastic.proto.ToRadio

class ProtobufSmokeTest {

    @Test
    fun `ToRadio кодируется и декодируется без потерь`() {
        val original = ToRadio(want_config_id = 69420)

        val decoded = ToRadio.ADAPTER.decode(original.encode())

        assertEquals(69420, decoded.want_config_id)
    }
}
```

- [ ] **Step 3: Запустить тест и убедиться, что он не компилируется**

Run: `./gradlew :app:testDebugUnitTest`
Expected: FAIL — `Unresolved reference: meshtastic` (зависимость ещё не добавлена).

- [ ] **Step 4: Добавить конфигурацию модуля и зависимости**

`app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.cerocoder.meshtest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cerocoder.meshtest"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Классы transport/, emulator/, connection/ пишут в android.util.Log.
    // Без этой строки любой вызов Log в JVM-тесте падает с "not mocked".
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)

    implementation(libs.meshtastic.protobufs)
    implementation(libs.wire.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
```

`app/src/main/AndroidManifest.xml` (разрешений в этапе 1 нет):

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="true"
        android:label="Mesh Test"
        android:supportsRtl="true"
        android:theme="@style/Theme.Material3.DayNight.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/kotlin/com/cerocoder/meshtest/MainActivity.kt` (временная заглушка, экраны появятся в Task 9):

```kotlin
package com.cerocoder.meshtest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Text("Mesh Test")
                }
            }
        }
    }
}
```

- [ ] **Step 5: Запустить тест — должен пройти**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, 1 тест.

- [ ] **Step 6: Проверить сборку APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL, файл `app/build/outputs/apk/debug/app-debug.apk` существует.

- [ ] **Step 7: Добавить CI**

`.github/workflows/build.yml`:

```yaml
name: build
on:
  push:
  pull_request:
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - name: Тесты
        run: ./gradlew :app:testDebugUnitTest
      - name: Сборка APK
        run: ./gradlew :app:assembleDebug
      - uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/*.apk
```

- [ ] **Step 8: Коммит**

```bash
git add settings.gradle.kts build.gradle.kts gradle app .github .gitignore gradlew gradlew.bat
git commit -m "chore: каркас Android-проекта, Wire-модели Meshtastic и CI"
```

---

### Task 2: Константы протокола и адресация устройств

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/transport/MeshProtocol.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/transport/DeviceListEntry.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshtest/transport/MeshProtocolTest.kt`

**Interfaces:**
- Consumes: ничего.
- Produces: `MeshProtocol.CONFIG_NONCE: Int`, `MeshProtocol.NODE_INFO_NONCE: Int`, `MeshProtocol.MAX_FRAME_BYTES: Int`, `MeshProtocol.scenarioIdOrNull(address: String): String?`, `MeshProtocol.bleMacOrNull(address: String): String?`; `DeviceListEntry.Demo(scenarioId: String, name: String)`, `DeviceListEntry.Ble(name: String, mac: String, bonded: Boolean, rssi: Int?)`, свойство `DeviceListEntry.address: String`.

- [ ] **Step 1: Написать падающий тест**

`app/src/test/kotlin/com/cerocoder/meshtest/transport/MeshProtocolTest.kt`:

```kotlin
package com.cerocoder.meshtest.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeshProtocolTest {

    @Test
    fun `демо-адрес отдаёт идентификатор сценария`() {
        assertEquals("5nodes", MeshProtocol.scenarioIdOrNull("m:5nodes"))
    }

    @Test
    fun `BLE-адрес не считается демо-адресом`() {
        assertNull(MeshProtocol.scenarioIdOrNull("xAA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `демо-адрес без идентификатора отбрасывается`() {
        assertNull(MeshProtocol.scenarioIdOrNull("m:"))
    }

    @Test
    fun `BLE-адрес отдаёт MAC`() {
        assertEquals("AA:BB:CC:DD:EE:FF", MeshProtocol.bleMacOrNull("xAA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `демо-адрес не считается BLE-адресом`() {
        assertNull(MeshProtocol.bleMacOrNull("m:5nodes"))
    }

    @Test
    fun `адрес демо-устройства собирается из идентификатора сценария`() {
        assertEquals("m:5nodes", DeviceListEntry.Demo("5nodes", "Demo: 5 нод").address)
    }

    @Test
    fun `адрес BLE-устройства собирается из MAC`() {
        val entry = DeviceListEntry.Ble("Meshtastic_a1b2", "AA:BB:CC:DD:EE:FF", bonded = true, rssi = -60)
        assertEquals("xAA:BB:CC:DD:EE:FF", entry.address)
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

Run: `./gradlew :app:testDebugUnitTest --tests "*MeshProtocolTest*"`
Expected: FAIL — `Unresolved reference: MeshProtocol`.

- [ ] **Step 3: Реализовать**

`app/src/main/kotlin/com/cerocoder/meshtest/transport/MeshProtocol.kt`:

```kotlin
package com.cerocoder.meshtest.transport

/**
 * Константы протокола Meshtastic и разбор внутренних адресов устройств.
 *
 * Адрес — одна строка, первый символ задаёт транспорт: "m:" — демо-устройство,
 * "x" — BLE. Такая же конвенция используется в официальном приложении.
 */
object MeshProtocol {

    /** Нонс запроса конфигурации (стадия 1 handshake). */
    const val CONFIG_NONCE = 69420

    /** Нонс запроса базы нод (стадия 2 handshake). */
    const val NODE_INFO_NONCE = 69421

    /** Потолок размера кадра: всё, что больше, отбрасывается как мусор. */
    const val MAX_FRAME_BYTES = 512

    const val DEMO_PREFIX = "m:"
    const val BLE_PREFIX = "x"

    /** Идентификатор сценария из демо-адреса, или null если адрес не демо. */
    fun scenarioIdOrNull(address: String): String? =
        if (address.startsWith(DEMO_PREFIX)) {
            address.removePrefix(DEMO_PREFIX).takeIf { it.isNotEmpty() }
        } else {
            null
        }

    /** MAC-адрес из BLE-адреса, или null если адрес не BLE. */
    fun bleMacOrNull(address: String): String? =
        if (address.startsWith(BLE_PREFIX) && !address.startsWith(DEMO_PREFIX)) {
            address.removePrefix(BLE_PREFIX).takeIf { it.isNotEmpty() }
        } else {
            null
        }
}
```

`app/src/main/kotlin/com/cerocoder/meshtest/transport/DeviceListEntry.kt`:

```kotlin
package com.cerocoder.meshtest.transport

/** Пункт списка устройств. Адрес однозначно определяет транспорт. */
sealed class DeviceListEntry {

    abstract val name: String
    abstract val address: String

    /** Виртуальное устройство, проигрывающее сценарий. Только в debug-сборке. */
    data class Demo(val scenarioId: String, override val name: String) : DeviceListEntry() {
        override val address: String get() = "${MeshProtocol.DEMO_PREFIX}$scenarioId"
    }

    /** Реальная нода по Bluetooth. Транспорт появится на этапе 2. */
    data class Ble(
        override val name: String,
        val mac: String,
        val bonded: Boolean,
        val rssi: Int?,
    ) : DeviceListEntry() {
        override val address: String get() = "${MeshProtocol.BLE_PREFIX}$mac"
    }
}
```

- [ ] **Step 4: Запустить тесты — должны пройти**

Run: `./gradlew :app:testDebugUnitTest --tests "*MeshProtocolTest*"`
Expected: PASS, 7 тестов.

- [ ] **Step 5: Коммит**

```bash
git add app/src/main/kotlin/com/cerocoder/meshtest/transport app/src/test/kotlin/com/cerocoder/meshtest/transport
git commit -m "feat: константы протокола Meshtastic и адресация устройств"
```

---

### Task 3: Сценарии эмуляции

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/emulator/MeshScenario.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/emulator/Scenarios.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshtest/emulator/ScenariosTest.kt`

**Interfaces:**
- Consumes: `MeshProtocol.CONFIG_NONCE`, `MeshProtocol.NODE_INFO_NONCE` (Task 2).
- Produces: класс `MeshScenario` со свойствами `id`, `displayName`, `nodes` и методами `configStageFrames(nonce: Int): List<FromRadio>`, `nodeStageFrames(nonce: Int): List<FromRadio>`; фабрики `MeshScenario.of(id, displayName, myInfo, metadata, config, moduleConfig, channels, nodes)` и `MeshScenario.fromFrames(id, displayName, configStage, nodeStage)`; `Scenarios.all: List<MeshScenario>`, `Scenarios.byId(id: String): MeshScenario?`, константы `Scenarios.FIVE_NODES_ID`, `Scenarios.EMPTY_MESH_ID`, `Scenarios.LARGE_MESH_ID`, `Scenarios.HANDSHAKE_ONLY_ID`.

- [ ] **Step 1: Написать падающий тест**

`app/src/test/kotlin/com/cerocoder/meshtest/emulator/ScenariosTest.kt`:

```kotlin
package com.cerocoder.meshtest.emulator

import com.cerocoder.meshtest.transport.MeshProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.User

class ScenariosTest {

    private val scenario = requireNotNull(Scenarios.byId(Scenarios.FIVE_NODES_ID))

    @Test
    fun `стадия 1 начинается с MyNodeInfo`() {
        val frames = scenario.configStageFrames(MeshProtocol.CONFIG_NONCE)

        assertNotNull(frames.first().my_info)
    }

    @Test
    fun `стадия 1 заканчивается подтверждением с тем же нонсом`() {
        val frames = scenario.configStageFrames(MeshProtocol.CONFIG_NONCE)

        assertEquals(MeshProtocol.CONFIG_NONCE, frames.last().config_complete_id)
    }

    @Test
    fun `стадия 1 содержит метаданные, конфиг и каналы`() {
        val frames = scenario.configStageFrames(MeshProtocol.CONFIG_NONCE)

        assertEquals(1, frames.count { it.metadata != null })
        assertTrue(frames.count { it.config != null } > 0)
        assertTrue(frames.count { it.channel != null } > 0)
    }

    @Test
    fun `стадия 2 отдаёт все ноды сценария и подтверждение`() {
        val frames = scenario.nodeStageFrames(MeshProtocol.NODE_INFO_NONCE)

        assertEquals(5, frames.count { it.node_info != null })
        assertEquals(MeshProtocol.NODE_INFO_NONCE, frames.last().config_complete_id)
    }

    @Test
    fun `у каждой ноды заполнены имя и номер`() {
        scenario.nodes.forEach { node ->
            assertTrue(node.num != 0)
            assertTrue(requireNotNull(node.user).short_name.isNotEmpty())
        }
    }

    @Test
    fun `сценарии имеют уникальные идентификаторы`() {
        val ids = Scenarios.all.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `неизвестный идентификатор сценария даёт null`() {
        assertNull(Scenarios.byId("нет-такого"))
    }

    @Test
    fun `сценарий из готовых кадров проигрывает их как есть`() {
        val raw = MeshScenario.fromFrames(
            id = "raw",
            displayName = "Из дампа",
            configStage = listOf(FromRadio(my_info = MyNodeInfo(my_node_num = 42))),
            nodeStage = listOf(FromRadio(node_info = NodeInfo(num = 7, user = User(short_name = "Р01")))),
        )

        val configFrames = raw.configStageFrames(MeshProtocol.CONFIG_NONCE)
        val nodeFrames = raw.nodeStageFrames(MeshProtocol.NODE_INFO_NONCE)

        assertEquals(42, requireNotNull(configFrames.first().my_info).my_node_num)
        assertEquals(MeshProtocol.CONFIG_NONCE, configFrames.last().config_complete_id)
        assertEquals(1, raw.nodes.size)
        assertEquals(MeshProtocol.NODE_INFO_NONCE, nodeFrames.last().config_complete_id)
    }

    @Test
    fun `пустой меш не отдаёт ни одной ноды`() {
        val empty = requireNotNull(Scenarios.byId(Scenarios.EMPTY_MESH_ID))

        assertEquals(0, empty.nodeStageFrames(MeshProtocol.NODE_INFO_NONCE).count { it.node_info != null })
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

Run: `./gradlew :app:testDebugUnitTest --tests "*ScenariosTest*"`
Expected: FAIL — `Unresolved reference: Scenarios`.

- [ ] **Step 3: Реализовать модель сценария**

`app/src/main/kotlin/com/cerocoder/meshtest/emulator/MeshScenario.kt`:

```kotlin
package com.cerocoder.meshtest.emulator

import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo

/**
 * Описание эмулируемого меша: данные без поведения.
 *
 * Порядок кадров повторяет порядок настоящей прошивки: MyNodeInfo идёт первым,
 * потому что только после него известен номер локальной ноды.
 */
class MeshScenario private constructor(
    val id: String,
    val displayName: String,
    /** Ноды сценария — для отображения и проверок. */
    val nodes: List<NodeInfo>,
    private val configStage: List<FromRadio>,
    private val nodeStage: List<FromRadio>,
) {

    /** Кадры ответа на want_config_id стадии 1, с подтверждением в конце. */
    fun configStageFrames(nonce: Int): List<FromRadio> = configStage + FromRadio(config_complete_id = nonce)

    /** Кадры ответа на want_config_id стадии 2, с подтверждением в конце. */
    fun nodeStageFrames(nonce: Int): List<FromRadio> = nodeStage + FromRadio(config_complete_id = nonce)

    companion object {

        /**
         * Сценарий из структурированных данных.
         *
         * Порядок кадров повторяет порядок настоящей прошивки: MyNodeInfo идёт
         * первым, потому что только после него известен номер локальной ноды.
         */
        fun of(
            id: String,
            displayName: String,
            myInfo: MyNodeInfo,
            metadata: DeviceMetadata,
            config: List<Config>,
            moduleConfig: List<ModuleConfig>,
            channels: List<Channel>,
            nodes: List<NodeInfo>,
        ): MeshScenario = MeshScenario(
            id = id,
            displayName = displayName,
            nodes = nodes,
            configStage = buildList {
                add(FromRadio(my_info = myInfo))
                add(FromRadio(metadata = metadata))
                config.forEach { add(FromRadio(config = it)) }
                moduleConfig.forEach { add(FromRadio(moduleConfig = it)) }
                channels.forEach { add(FromRadio(channel = it)) }
            },
            nodeStage = nodes.map { FromRadio(node_info = it) },
        )

        /**
         * Сценарий из готовых кадров — например, из дампа трафика реальной ноды.
         *
         * Завершающие кадры `config_complete_id` добавляются автоматически, поэтому
         * из дампа их нужно исключить, иначе приложение получит подтверждение дважды.
         */
        fun fromFrames(
            id: String,
            displayName: String,
            configStage: List<FromRadio>,
            nodeStage: List<FromRadio>,
        ): MeshScenario = MeshScenario(
            id = id,
            displayName = displayName,
            nodes = nodeStage.mapNotNull { it.node_info },
            configStage = configStage,
            nodeStage = nodeStage,
        )
    }
}
```

- [ ] **Step 4: Реализовать готовые наборы**

`app/src/main/kotlin/com/cerocoder/meshtest/emulator/Scenarios.kt`:

```kotlin
package com.cerocoder.meshtest.emulator

import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.User

/** Готовые наборы данных для демо-устройств. */
object Scenarios {

    const val HANDSHAKE_ONLY_ID = "handshake"
    const val FIVE_NODES_ID = "5nodes"
    const val LARGE_MESH_ID = "200nodes"
    const val EMPTY_MESH_ID = "empty"

    private const val LOCAL_NODE_NUM = 0x11223344

    val all: List<MeshScenario> by lazy {
        listOf(
            scenario(FIVE_NODES_ID, "Demo: 5 нод", nodeCount = 5),
            scenario(LARGE_MESH_ID, "Demo: 200 нод", nodeCount = 200),
            scenario(EMPTY_MESH_ID, "Demo: пустой меш", nodeCount = 0),
            scenario(HANDSHAKE_ONLY_ID, "Demo: только handshake", nodeCount = 0, channelCount = 1),
        )
    }

    fun byId(id: String): MeshScenario? = all.firstOrNull { it.id == id }

    private fun scenario(
        id: String,
        displayName: String,
        nodeCount: Int,
        channelCount: Int = 3,
    ): MeshScenario = MeshScenario.of(
        id = id,
        displayName = displayName,
        myInfo = MyNodeInfo(
            my_node_num = LOCAL_NODE_NUM,
            reboot_count = 1,
            min_app_version = 30200,
            nodedb_count = nodeCount,
        ),
        metadata = DeviceMetadata(
            firmware_version = "2.7.26.demo",
            hasBluetooth = true,
            hasWifi = false,
        ),
        // Подмножество секций конфигурации: диагностическому экрану достаточно,
        // чтобы показать, что кадры конфига разбираются.
        config = listOf(
            Config(device = Config.DeviceConfig()),
            Config(position = Config.PositionConfig()),
            Config(lora = Config.LoRaConfig()),
            Config(bluetooth = Config.BluetoothConfig()),
        ),
        // Конфигурация модулей на этапе 1 не нужна: её никто не отображает.
        moduleConfig = emptyList(),
        channels = List(channelCount) { index ->
            Channel(
                index = index,
                role = if (index == 0) Channel.Role.PRIMARY else Channel.Role.SECONDARY,
                settings = ChannelSettings(name = if (index == 0) "LongFast" else "Канал $index"),
            )
        },
        nodes = List(nodeCount) { index -> node(index) },
    )

    private fun node(index: Int): NodeInfo {
        val num = LOCAL_NODE_NUM + index + 1
        return NodeInfo(
            num = num,
            user = User(
                id = "!%08x".format(num),
                long_name = "Демо-нода ${index + 1}",
                short_name = "Д%02d".format(index + 1),
            ),
            snr = 5.0f - index % 10,
            last_heard = 1_780_000_000 + index,
            channel = 0,
            hops_away = index % 3,
        )
    }
}
```

- [ ] **Step 5: Запустить тесты — должны пройти**

Run: `./gradlew :app:testDebugUnitTest --tests "*ScenariosTest*"`
Expected: PASS, 9 тестов.

- [ ] **Step 6: Коммит**

```bash
git add app/src/main/kotlin/com/cerocoder/meshtest/emulator app/src/test/kotlin/com/cerocoder/meshtest/emulator
git commit -m "feat: сценарии эмулируемого меша и сборка кадров handshake"
```

---

### Task 4: Шов транспорта и handshake фейкового транспорта

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/transport/RadioTransport.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/transport/FakeRadioTransport.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshtest/transport/FakeRadioTransportTest.kt`

**Interfaces:**
- Consumes: `MeshProtocol` (Task 2); `MeshScenario`, `Scenarios` (Task 3).
- Produces: `interface RadioTransport { fun start(); fun send(bytes: ByteArray); suspend fun close() }`; `interface RadioTransportCallback { fun onConnect(); fun onDisconnect(isPermanent: Boolean); fun onDataReceived(bytes: ByteArray) }`; `interface RadioTransportFactory { fun create(address: String, callback: RadioTransportCallback): RadioTransport }`; класс `FakeRadioTransport(scenario, callback, parentScope, connectDelay, frameDelay)`.

- [ ] **Step 1: Написать падающий тест**

`app/src/test/kotlin/com/cerocoder/meshtest/transport/FakeRadioTransportTest.kt`:

```kotlin
package com.cerocoder.meshtest.transport

import com.cerocoder.meshtest.emulator.Scenarios
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.ToRadio
import kotlin.time.Duration.Companion.ZERO

/** Записывает всё, что транспорт отдаёт наверх. */
private class RecordingCallback : RadioTransportCallback {
    var connected = false
    var disconnectedPermanently: Boolean? = null
    val frames = mutableListOf<FromRadio>()

    override fun onConnect() {
        connected = true
    }

    override fun onDisconnect(isPermanent: Boolean) {
        disconnectedPermanently = isPermanent
    }

    override fun onDataReceived(bytes: ByteArray) {
        frames += FromRadio.ADAPTER.decode(bytes)
    }
}

class FakeRadioTransportTest {

    private fun transport(callback: RadioTransportCallback, scope: kotlinx.coroutines.CoroutineScope) =
        FakeRadioTransport(
            scenario = requireNotNull(Scenarios.byId(Scenarios.FIVE_NODES_ID)),
            callback = callback,
            parentScope = scope,
            connectDelay = ZERO,
            frameDelay = ZERO,
        )

    @Test
    fun `после старта транспорт сообщает о подключении`() = runTest {
        val callback = RecordingCallback()

        transport(callback, backgroundScope).start()
        advanceUntilIdle()

        assertTrue(callback.connected)
    }

    @Test
    fun `на запрос конфигурации отдаёт стадию 1 в правильном порядке`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback, backgroundScope)

        subject.start()
        subject.send(ToRadio(want_config_id = MeshProtocol.CONFIG_NONCE).encode())
        advanceUntilIdle()

        assertNotNull(callback.frames.first().my_info)
        assertEquals(MeshProtocol.CONFIG_NONCE, callback.frames.last().config_complete_id)
    }

    @Test
    fun `на запрос базы нод отдаёт стадию 2`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback, backgroundScope)

        subject.start()
        subject.send(ToRadio(want_config_id = MeshProtocol.NODE_INFO_NONCE).encode())
        advanceUntilIdle()

        assertEquals(5, callback.frames.count { it.node_info != null })
        assertEquals(MeshProtocol.NODE_INFO_NONCE, callback.frames.last().config_complete_id)
    }

    @Test
    fun `кадрам присваиваются возрастающие идентификаторы`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback, backgroundScope)

        subject.start()
        subject.send(ToRadio(want_config_id = MeshProtocol.CONFIG_NONCE).encode())
        advanceUntilIdle()

        val ids = callback.frames.map { it.id }
        assertEquals(ids.sorted(), ids)
        assertEquals(ids.size, ids.toSet().size)
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

Run: `./gradlew :app:testDebugUnitTest --tests "*FakeRadioTransportTest*"`
Expected: FAIL — `Unresolved reference: RadioTransportCallback`.

- [ ] **Step 3: Объявить шов**

`app/src/main/kotlin/com/cerocoder/meshtest/transport/RadioTransport.kt`:

```kotlin
package com.cerocoder.meshtest.transport

/**
 * Транспорт до ноды: сырые байты в обе стороны.
 *
 * Реализации: [FakeRadioTransport] (демо-устройство) и BleRadioTransport (этап 2).
 * Ничто выше этого интерфейса не знает, что именно подключено.
 */
interface RadioTransport {

    /** Начать установление связи. Результат придёт через колбэк. */
    fun start()

    /** Отправить закодированный ToRadio. Вызов не блокирует. */
    fun send(bytes: ByteArray)

    /** Разорвать связь и освободить ресурсы. Повторный вызов безопасен. */
    suspend fun close()
}

/** Узкий колбэк транспорт -> приложение. */
interface RadioTransportCallback {

    fun onConnect()

    /**
     * @param isPermanent true — попытки подключения прекращены (пользователь отключился,
     *   устройство недоступно); false — связь может восстановиться сама.
     */
    fun onDisconnect(isPermanent: Boolean)

    /** Пришёл закодированный FromRadio. */
    fun onDataReceived(bytes: ByteArray)
}

/** Создаёт транспорт по внутреннему адресу устройства. */
interface RadioTransportFactory {
    fun create(address: String, callback: RadioTransportCallback): RadioTransport
}
```

- [ ] **Step 4: Реализовать фейковый транспорт**

`app/src/main/kotlin/com/cerocoder/meshtest/transport/FakeRadioTransport.kt`:

```kotlin
package com.cerocoder.meshtest.transport

import android.util.Log
import com.cerocoder.meshtest.emulator.MeshScenario
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.ToRadio
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Эмулятор ноды: разбирает входящие ToRadio и отвечает кадрами из [scenario].
 *
 * Кадры отдаются с паузой [frameDelay], а не одним взрывом: мгновенная выдача
 * скрыла бы гонки, которые проявятся на настоящем асинхронном транспорте.
 * В тестах обе задержки выставляются в ноль.
 */
class FakeRadioTransport(
    private val scenario: MeshScenario,
    private val callback: RadioTransportCallback,
    parentScope: CoroutineScope,
    private val connectDelay: Duration = 300.milliseconds,
    private val frameDelay: Duration = 25.milliseconds,
) : RadioTransport {

    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)

    private var nextFrameId = 1

    override fun start() {
        scope.launch {
            delay(connectDelay)
            callback.onConnect()
        }
    }

    override fun send(bytes: ByteArray) {
        val message = try {
            ToRadio.ADAPTER.decode(bytes)
        } catch (e: IOException) {
            Log.w(TAG, "не удалось разобрать ToRadio (${bytes.size} байт)", e)
            return
        }

        when {
            message.want_config_id == MeshProtocol.CONFIG_NONCE ->
                emit(scenario.configStageFrames(MeshProtocol.CONFIG_NONCE))

            message.want_config_id == MeshProtocol.NODE_INFO_NONCE ->
                emit(scenario.nodeStageFrames(MeshProtocol.NODE_INFO_NONCE))

            else -> Log.d(TAG, "проигнорирован ToRadio: $message")
        }
    }

    private fun emit(frames: List<FromRadio>) {
        scope.launch {
            frames.forEach { frame ->
                delay(frameDelay)
                callback.onDataReceived(frame.copy(id = nextFrameId++).encode())
            }
        }
    }

    override suspend fun close() {
        job.cancelAndJoin()
    }

    private companion object {
        const val TAG = "FakeRadioTransport"
    }
}
```

- [ ] **Step 5: Запустить тесты — должны пройти**

Run: `./gradlew :app:testDebugUnitTest --tests "*FakeRadioTransportTest*"`
Expected: PASS, 4 теста.

- [ ] **Step 6: Коммит**

```bash
git add app/src/main/kotlin/com/cerocoder/meshtest/transport app/src/test/kotlin/com/cerocoder/meshtest/transport
git commit -m "feat: шов транспорта и handshake фейкового транспорта"
```

---

### Task 5: Фейковый транспорт — heartbeat, отключение и мусорные пакеты

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshtest/transport/FakeRadioTransport.kt`
- Modify: `app/src/test/kotlin/com/cerocoder/meshtest/transport/FakeRadioTransportTest.kt`

**Interfaces:**
- Consumes: всё из Task 4.
- Produces: поведение `FakeRadioTransport` при `ToRadio(heartbeat = ...)` (ответ `FromRadio(queueStatus = ...)`), при `ToRadio(disconnect = true)` (вызов `onDisconnect(isPermanent = true)`) и при некорректных байтах (тишина без исключения).

- [ ] **Step 1: Дописать падающие тесты**

Добавить в `FakeRadioTransportTest` (импорты `org.meshtastic.proto.Heartbeat` и `org.junit.Assert.assertNull`):

```kotlin
    @Test
    fun `на heartbeat отвечает статусом очереди`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback, backgroundScope)

        subject.start()
        subject.send(ToRadio(heartbeat = Heartbeat(nonce = 7)).encode())
        advanceUntilIdle()

        assertEquals(1, callback.frames.count { it.queueStatus != null })
    }

    @Test
    fun `на прощальный пакет отвечает постоянным отключением`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback, backgroundScope)

        subject.start()
        subject.send(ToRadio(disconnect = true).encode())
        advanceUntilIdle()

        assertEquals(true, callback.disconnectedPermanently)
    }

    @Test
    fun `мусорные байты не роняют транспорт`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback, backgroundScope)

        subject.start()
        subject.send(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        advanceUntilIdle()

        assertTrue(callback.frames.isEmpty())
        assertNull(callback.disconnectedPermanently)
    }

    @Test
    fun `после close новые кадры не приходят`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback, backgroundScope)

        subject.start()
        subject.close()
        subject.send(ToRadio(want_config_id = MeshProtocol.CONFIG_NONCE).encode())
        advanceUntilIdle()

        assertTrue(callback.frames.isEmpty())
    }
```

- [ ] **Step 2: Запустить тесты и убедиться, что новые падают**

Run: `./gradlew :app:testDebugUnitTest --tests "*FakeRadioTransportTest*"`
Expected: FAIL — `на heartbeat отвечает статусом очереди` и `на прощальный пакет отвечает постоянным отключением` не проходят (кадров нет, отключения нет).

- [ ] **Step 3: Дополнить реализацию**

В `FakeRadioTransport.send()` заменить блок `when` на:

```kotlin
        when {
            message.want_config_id == MeshProtocol.CONFIG_NONCE ->
                emit(scenario.configStageFrames(MeshProtocol.CONFIG_NONCE))

            message.want_config_id == MeshProtocol.NODE_INFO_NONCE ->
                emit(scenario.nodeStageFrames(MeshProtocol.NODE_INFO_NONCE))

            // Настоящая прошивка отвечает на heartbeat статусом очереди — это
            // доказывает, что связь жива. Без ответа демо вело бы себя не как нода.
            message.heartbeat != null ->
                emit(listOf(FromRadio(queueStatus = QueueStatus(res = 0, free = 16, maxlen = 16))))

            message.disconnect == true -> {
                Log.i(TAG, "получено прощание, закрываем сессию")
                callback.onDisconnect(isPermanent = true)
            }

            else -> Log.d(TAG, "проигнорирован ToRadio: $message")
        }
```

Добавить импорт `org.meshtastic.proto.QueueStatus`.

- [ ] **Step 4: Запустить тесты — должны пройти**

Run: `./gradlew :app:testDebugUnitTest --tests "*FakeRadioTransportTest*"`
Expected: PASS, 8 тестов.

- [ ] **Step 5: Коммит**

```bash
git add app/src/main/kotlin/com/cerocoder/meshtest/transport/FakeRadioTransport.kt app/src/test/kotlin/com/cerocoder/meshtest/transport/FakeRadioTransportTest.kt
git commit -m "feat: ответ на heartbeat, прощание и устойчивость фейка к мусору"
```

---

### Task 6: Менеджер соединения — состояния и handshake

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/connection/ConnectionState.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/connection/RadioConnectionManager.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshtest/connection/RadioConnectionManagerTest.kt`

**Interfaces:**
- Consumes: `RadioTransport`, `RadioTransportCallback`, `RadioTransportFactory`, `MeshProtocol` (Tasks 2, 4); `FakeRadioTransport` и `Scenarios` — только в тестах.
- Produces: `sealed interface ConnectionState` с объектами `Disconnected`, `Connecting`, `Connected`; класс `RadioConnectionManager(factory, scope, handshakeTimeout)` со свойствами `connectionState: StateFlow<ConnectionState>`, `packets: Flow<FromRadio>`, `packetLog: StateFlow<List<FromRadio>>`, `droppedFrames: Int` и методами `connect(address: String)`, `suspend fun disconnect()`.

- [ ] **Step 1: Написать падающий тест**

`app/src/test/kotlin/com/cerocoder/meshtest/connection/RadioConnectionManagerTest.kt`:

```kotlin
package com.cerocoder.meshtest.connection

import com.cerocoder.meshtest.emulator.Scenarios
import com.cerocoder.meshtest.transport.FakeRadioTransport
import com.cerocoder.meshtest.transport.MeshProtocol
import com.cerocoder.meshtest.transport.RadioTransport
import com.cerocoder.meshtest.transport.RadioTransportCallback
import com.cerocoder.meshtest.transport.RadioTransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.ZERO

/** Фабрика, отдающая фейковый транспорт без задержек. */
private class TestFactory(private val scope: CoroutineScope) : RadioTransportFactory {
    var createdCount = 0

    override fun create(address: String, callback: RadioTransportCallback): RadioTransport {
        createdCount++
        val scenarioId = requireNotNull(MeshProtocol.scenarioIdOrNull(address))
        return FakeRadioTransport(
            scenario = requireNotNull(Scenarios.byId(scenarioId)),
            callback = callback,
            parentScope = scope,
            connectDelay = ZERO,
            frameDelay = ZERO,
        )
    }
}

class RadioConnectionManagerTest {

    @Test
    fun `изначально соединение отсутствует`() = runTest {
        val manager = RadioConnectionManager(TestFactory(backgroundScope), backgroundScope)

        assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
    }

    @Test
    fun `полный handshake доводит состояние до Connected`() = runTest {
        val manager = RadioConnectionManager(TestFactory(backgroundScope), backgroundScope)

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        assertEquals(ConnectionState.Connected, manager.connectionState.value)
    }

    @Test
    fun `все ноды сценария попадают в лог пакетов`() = runTest {
        val manager = RadioConnectionManager(TestFactory(backgroundScope), backgroundScope)

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        assertEquals(5, manager.packetLog.value.count { it.node_info != null })
    }

    @Test
    fun `порядок кадров в логе совпадает с порядком отправки`() = runTest {
        val manager = RadioConnectionManager(TestFactory(backgroundScope), backgroundScope)

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        val ids = manager.packetLog.value.map { it.id }
        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun `первым в логе идёт MyNodeInfo`() = runTest {
        val manager = RadioConnectionManager(TestFactory(backgroundScope), backgroundScope)

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        assertTrue(manager.packetLog.value.first().my_info != null)
    }

    @Test
    fun `отключение возвращает состояние в Disconnected`() = runTest {
        val manager = RadioConnectionManager(TestFactory(backgroundScope), backgroundScope)
        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        manager.disconnect()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

Run: `./gradlew :app:testDebugUnitTest --tests "*RadioConnectionManagerTest*"`
Expected: FAIL — `Unresolved reference: RadioConnectionManager`.

- [ ] **Step 3: Объявить состояния**

`app/src/main/kotlin/com/cerocoder/meshtest/connection/ConnectionState.kt`:

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
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
}
```

- [ ] **Step 4: Реализовать менеджер**

`app/src/main/kotlin/com/cerocoder/meshtest/connection/RadioConnectionManager.kt`:

```kotlin
package com.cerocoder.meshtest.connection

import android.util.Log
import com.cerocoder.meshtest.transport.MeshProtocol
import com.cerocoder.meshtest.transport.RadioTransport
import com.cerocoder.meshtest.transport.RadioTransportCallback
import com.cerocoder.meshtest.transport.RadioTransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.ToRadio
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Единственный владелец активного транспорта.
 *
 * Ведёт двухстадийный handshake, публикует состояние соединения и поток
 * принятых кадров. Не знает, что под ним — демо-устройство или BLE.
 */
class RadioConnectionManager(
    private val factory: RadioTransportFactory,
    private val scope: CoroutineScope,
    private val handshakeTimeout: Duration = 30.seconds,
) : RadioTransportCallback {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Channel, а не SharedFlow: строгий FIFO обязателен — порядок кадров конфигурации
    // определяет корректность handshake. При переполнении отбрасывается новейший кадр,
    // чтобы уже принятые сохранили порядок.
    private val _packets = Channel<FromRadio>(capacity = PACKET_QUEUE_CAPACITY)
    val packets: Flow<FromRadio> = _packets.receiveAsFlow()

    // Отдельный накопитель для диагностического экрана: у Channel один потребитель,
    // и переподписка UI теряла бы кадры.
    private val _packetLog = MutableStateFlow<List<FromRadio>>(emptyList())
    val packetLog: StateFlow<List<FromRadio>> = _packetLog.asStateFlow()

    var droppedFrames: Int = 0
        private set

    private val transportMutex = Mutex()
    private var transport: RadioTransport? = null
    private var currentAddress: String? = null

    /** Подключиться к устройству по внутреннему адресу. */
    fun connect(address: String) {
        scope.launch {
            transportMutex.withLock {
                closeTransportLocked()
                _packetLog.value = emptyList()
                droppedFrames = 0
                currentAddress = address
                val created = factory.create(address, this@RadioConnectionManager)
                transport = created
                created.start()
            }
        }
    }

    /** Отключиться и освободить транспорт. */
    suspend fun disconnect() {
        transportMutex.withLock {
            transport?.let { active ->
                // Вежливое прощание: даём ноде понять, что разрыв намеренный.
                active.send(ToRadio(disconnect = true).encode())
            }
            closeTransportLocked()
            currentAddress = null
        }
        _connectionState.value = ConnectionState.Disconnected
    }

    private suspend fun closeTransportLocked() {
        transport?.let { active ->
            try {
                active.close()
            } catch (e: IOException) {
                Log.w(TAG, "ошибка при закрытии транспорта", e)
            }
        }
        transport = null
    }

    override fun onConnect() {
        _connectionState.value = ConnectionState.Connecting
        Log.i(TAG, "связь установлена, запускаем стадию 1 handshake")
        sendToRadio(ToRadio(want_config_id = MeshProtocol.CONFIG_NONCE))
    }

    override fun onDisconnect(isPermanent: Boolean) {
        Log.i(TAG, "связь потеряна (постоянно=$isPermanent)")
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun onDataReceived(bytes: ByteArray) {
        if (bytes.size > MeshProtocol.MAX_FRAME_BYTES) {
            Log.w(TAG, "кадр ${bytes.size} байт превышает лимит ${MeshProtocol.MAX_FRAME_BYTES}, отброшен")
            return
        }

        val frame = try {
            FromRadio.ADAPTER.decode(bytes)
        } catch (e: IOException) {
            // Один битый кадр не должен обрывать приём: это единственный канал,
            // по которому в приложение вообще попадают данные.
            Log.w(TAG, "не удалось разобрать FromRadio (${bytes.size} байт)", e)
            return
        }

        when (frame.config_complete_id) {
            MeshProtocol.CONFIG_NONCE -> {
                Log.i(TAG, "стадия 1 завершена, запрашиваем базу нод")
                sendToRadio(ToRadio(want_config_id = MeshProtocol.NODE_INFO_NONCE))
            }

            MeshProtocol.NODE_INFO_NONCE -> {
                Log.i(TAG, "handshake завершён")
                _connectionState.value = ConnectionState.Connected
            }
        }

        if (_packets.trySend(frame).isFailure) {
            droppedFrames++
        }
        _packetLog.update { log -> (log + frame).takeLast(PACKET_LOG_LIMIT) }
    }

    private fun sendToRadio(message: ToRadio) {
        val active = transport
        if (active == null) {
            Log.w(TAG, "нет активного транспорта, пакет отброшен")
            return
        }
        active.send(message.encode())
    }

    private companion object {
        const val TAG = "RadioConnectionManager"
        const val PACKET_QUEUE_CAPACITY = 256
        const val PACKET_LOG_LIMIT = 500
    }
}
```

Добавить импорт `kotlinx.coroutines.flow.update`.

- [ ] **Step 5: Запустить тесты — должны пройти**

Run: `./gradlew :app:testDebugUnitTest --tests "*RadioConnectionManagerTest*"`
Expected: PASS, 6 тестов.

- [ ] **Step 6: Коммит**

```bash
git add app/src/main/kotlin/com/cerocoder/meshtest/connection app/src/test/kotlin/com/cerocoder/meshtest/connection
git commit -m "feat: менеджер соединения с двухстадийным handshake"
```

---

### Task 7: Менеджер соединения — watchdog и идемпотентность

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshtest/connection/RadioConnectionManager.kt`
- Modify: `app/src/test/kotlin/com/cerocoder/meshtest/connection/RadioConnectionManagerTest.kt`

**Interfaces:**
- Consumes: всё из Task 6.
- Produces: поведение watchdog (переход в `Disconnected` при незавершённом handshake за `handshakeTimeout`) и идемпотентность `connect(address)` для того же адреса при живом соединении.

- [ ] **Step 1: Дописать падающие тесты**

Добавить в `RadioConnectionManagerTest` (импорты `kotlinx.coroutines.test.advanceTimeBy` и `kotlin.time.Duration.Companion.seconds`):

```kotlin
    /** Транспорт, который подключается, но никогда не отвечает на запросы. */
    private class SilentTransport(private val callback: RadioTransportCallback) : RadioTransport {
        override fun start() = callback.onConnect()
        override fun send(bytes: ByteArray) = Unit
        override suspend fun close() = Unit
    }

    private class SilentFactory : RadioTransportFactory {
        override fun create(address: String, callback: RadioTransportCallback): RadioTransport =
            SilentTransport(callback)
    }

    @Test
    fun `молчащая нода переводит соединение в Disconnected по таймауту`() = runTest {
        val manager = RadioConnectionManager(
            factory = SilentFactory(),
            scope = backgroundScope,
            handshakeTimeout = 30.seconds,
        )

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceTimeBy(31.seconds)
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
    }

    @Test
    fun `до истечения таймаута соединение остаётся в Connecting`() = runTest {
        val manager = RadioConnectionManager(
            factory = SilentFactory(),
            scope = backgroundScope,
            handshakeTimeout = 30.seconds,
        )

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceTimeBy(29.seconds)

        assertEquals(ConnectionState.Connecting, manager.connectionState.value)
    }

    @Test
    fun `повторное подключение к тому же адресу не пересоздаёт транспорт`() = runTest {
        val factory = TestFactory(backgroundScope)
        val manager = RadioConnectionManager(factory, backgroundScope)

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()
        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        assertEquals(1, factory.createdCount)
    }

    @Test
    fun `подключение к другому адресу пересоздаёт транспорт`() = runTest {
        val factory = TestFactory(backgroundScope)
        val manager = RadioConnectionManager(factory, backgroundScope)

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()
        manager.connect("m:${Scenarios.EMPTY_MESH_ID}")
        advanceUntilIdle()

        assertEquals(2, factory.createdCount)
    }

    @Test
    fun `слишком большой кадр отбрасывается`() = runTest {
        val manager = RadioConnectionManager(TestFactory(backgroundScope), backgroundScope)

        manager.onDataReceived(ByteArray(MeshProtocol.MAX_FRAME_BYTES + 1))
        advanceUntilIdle()

        assertTrue(manager.packetLog.value.isEmpty())
    }

    @Test
    fun `битый кадр не попадает в лог и не роняет менеджер`() = runTest {
        val manager = RadioConnectionManager(TestFactory(backgroundScope), backgroundScope)

        manager.onDataReceived(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        advanceUntilIdle()

        assertTrue(manager.packetLog.value.isEmpty())
    }
```

- [ ] **Step 2: Запустить тесты и убедиться, что новые падают**

Run: `./gradlew :app:testDebugUnitTest --tests "*RadioConnectionManagerTest*"`
Expected: FAIL — тесты про таймаут и про повторное подключение не проходят (watchdog отсутствует, транспорт пересоздаётся всегда).

- [ ] **Step 3: Добавить watchdog и проверку адреса**

В `RadioConnectionManager` добавить поле:

```kotlin
    private var watchdog: Job? = null
```

(импорты `kotlinx.coroutines.Job` и `kotlinx.coroutines.delay`)

Заменить тело `connect()` на:

```kotlin
    fun connect(address: String) {
        scope.launch {
            transportMutex.withLock {
                // Идемпотентность: повторный тап по уже подключённому устройству не
                // должен пересоздавать транспорт — иначе останутся два транспорта,
                // пишущих в один канал.
                if (address == currentAddress && _connectionState.value != ConnectionState.Disconnected) {
                    Log.d(TAG, "уже подключены к этому адресу, повтор игнорируем")
                    return@withLock
                }
                closeTransportLocked()
                _packetLog.value = emptyList()
                droppedFrames = 0
                currentAddress = address
                val created = factory.create(address, this@RadioConnectionManager)
                transport = created
                created.start()
            }
        }
    }
```

В `onConnect()` запустить watchdog:

```kotlin
    override fun onConnect() {
        _connectionState.value = ConnectionState.Connecting
        Log.i(TAG, "связь установлена, запускаем стадию 1 handshake")
        startHandshakeWatchdog()
        sendToRadio(ToRadio(want_config_id = MeshProtocol.CONFIG_NONCE))
    }
```

Добавить сам watchdog и отмену:

```kotlin
    /**
     * Страховка от «тихого» зависания: физическая связь есть, но нода не отвечает
     * на want_config_id. Без неё приложение осталось бы в Connecting навсегда.
     */
    private fun startHandshakeWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(handshakeTimeout)
            if (_connectionState.value == ConnectionState.Connecting) {
                Log.w(TAG, "handshake не завершился за $handshakeTimeout, разрываем связь")
                transportMutex.withLock { closeTransportLocked() }
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }
```

В ветке `NODE_INFO_NONCE` метода `onDataReceived()` отменить watchdog **до** смены состояния:

```kotlin
            MeshProtocol.NODE_INFO_NONCE -> {
                watchdog?.cancel()
                Log.i(TAG, "handshake завершён")
                _connectionState.value = ConnectionState.Connected
            }
```

В `onDisconnect()` и `disconnect()` также отменить watchdog:

```kotlin
    override fun onDisconnect(isPermanent: Boolean) {
        watchdog?.cancel()
        Log.i(TAG, "связь потеряна (постоянно=$isPermanent)")
        _connectionState.value = ConnectionState.Disconnected
    }
```

В `disconnect()` первой строкой внутри `transportMutex.withLock { ... }` добавить `watchdog?.cancel()`.

- [ ] **Step 4: Запустить тесты — должны пройти**

Run: `./gradlew :app:testDebugUnitTest --tests "*RadioConnectionManagerTest*"`
Expected: PASS, 12 тестов.

- [ ] **Step 5: Коммит**

```bash
git add app/src/main/kotlin/com/cerocoder/meshtest/connection app/src/test/kotlin/com/cerocoder/meshtest/connection
git commit -m "feat: watchdog handshake и идемпотентное подключение"
```

---

### Task 8: Фабрика транспортов и контейнер зависимостей

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/transport/RadioTransportFactoryImpl.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/AppContainer.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/MeshTestApp.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/kotlin/com/cerocoder/meshtest/transport/RadioTransportFactoryImplTest.kt`

**Interfaces:**
- Consumes: `RadioTransportFactory`, `FakeRadioTransport`, `MeshProtocol` (Tasks 2, 4); `Scenarios` (Task 3); `RadioConnectionManager` (Task 6).
- Produces: `RadioTransportFactoryImpl(scope: CoroutineScope, isDebugBuild: Boolean)`; `AppContainer(isDebugBuild: Boolean)` со свойствами `connectionManager: RadioConnectionManager` и `devices: List<DeviceListEntry>`; класс `MeshTestApp : Application` со свойством `container: AppContainer`.

- [ ] **Step 1: Написать падающий тест**

`app/src/test/kotlin/com/cerocoder/meshtest/transport/RadioTransportFactoryImplTest.kt`:

```kotlin
package com.cerocoder.meshtest.transport

import com.cerocoder.meshtest.emulator.Scenarios
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private object NoopCallback : RadioTransportCallback {
    override fun onConnect() = Unit
    override fun onDisconnect(isPermanent: Boolean) = Unit
    override fun onDataReceived(bytes: ByteArray) = Unit
}

class RadioTransportFactoryImplTest {

    @Test
    fun `демо-адрес отдаёт фейковый транспорт`() = runTest {
        val factory = RadioTransportFactoryImpl(backgroundScope, isDebugBuild = true)

        val transport = factory.create("m:${Scenarios.FIVE_NODES_ID}", NoopCallback)

        assertTrue(transport is FakeRadioTransport)
    }

    @Test
    fun `в release-сборке демо-устройство недоступно`() = runTest {
        val factory = RadioTransportFactoryImpl(backgroundScope, isDebugBuild = false)

        assertThrows(IllegalArgumentException::class.java) {
            factory.create("m:${Scenarios.FIVE_NODES_ID}", NoopCallback)
        }
    }

    @Test
    fun `неизвестный сценарий отвергается`() = runTest {
        val factory = RadioTransportFactoryImpl(backgroundScope, isDebugBuild = true)

        assertThrows(IllegalStateException::class.java) {
            factory.create("m:нет-такого", NoopCallback)
        }
    }

    @Test
    fun `BLE-адрес пока не поддерживается`() = runTest {
        val factory = RadioTransportFactoryImpl(backgroundScope, isDebugBuild = true)

        assertThrows(IllegalStateException::class.java) {
            factory.create("xAA:BB:CC:DD:EE:FF", NoopCallback)
        }
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

Run: `./gradlew :app:testDebugUnitTest --tests "*RadioTransportFactoryImplTest*"`
Expected: FAIL — `Unresolved reference: RadioTransportFactoryImpl`.

- [ ] **Step 3: Реализовать фабрику**

`app/src/main/kotlin/com/cerocoder/meshtest/transport/RadioTransportFactoryImpl.kt`:

```kotlin
package com.cerocoder.meshtest.transport

import com.cerocoder.meshtest.emulator.Scenarios
import kotlinx.coroutines.CoroutineScope

/**
 * Выбирает реализацию транспорта по префиксу адреса.
 *
 * Это единственная точка ветвления между способами подключения: на этапе 2
 * здесь появится вторая ветка для BLE, и больше нигде менять ничего не придётся.
 */
class RadioTransportFactoryImpl(
    private val scope: CoroutineScope,
    private val isDebugBuild: Boolean,
) : RadioTransportFactory {

    override fun create(address: String, callback: RadioTransportCallback): RadioTransport {
        MeshProtocol.scenarioIdOrNull(address)?.let { scenarioId ->
            // Демо-устройства не должны существовать в релизе: адрес приходит извне
            // (список устройств, сохранённые настройки), и релизная сборка обязана
            // отказать даже если такой адрес каким-то образом сохранился.
            require(isDebugBuild) { "демо-устройства доступны только в debug-сборке" }
            val scenario = checkNotNull(Scenarios.byId(scenarioId)) { "неизвестный сценарий: $scenarioId" }
            return FakeRadioTransport(scenario = scenario, callback = callback, parentScope = scope)
        }

        MeshProtocol.bleMacOrNull(address)?.let {
            error("BLE-транспорт появится на этапе 2")
        }

        error("неизвестный формат адреса: $address")
    }
}
```

- [ ] **Step 4: Запустить тесты — должны пройти**

Run: `./gradlew :app:testDebugUnitTest --tests "*RadioTransportFactoryImplTest*"`
Expected: PASS, 4 теста.

- [ ] **Step 5: Собрать контейнер зависимостей**

`app/src/main/kotlin/com/cerocoder/meshtest/AppContainer.kt`:

```kotlin
package com.cerocoder.meshtest

import com.cerocoder.meshtest.connection.RadioConnectionManager
import com.cerocoder.meshtest.emulator.Scenarios
import com.cerocoder.meshtest.transport.DeviceListEntry
import com.cerocoder.meshtest.transport.RadioTransportFactory
import com.cerocoder.meshtest.transport.RadioTransportFactoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Ручной контейнер зависимостей уровня приложения.
 *
 * Живёт столько же, сколько процесс: соединение переживает пересоздание Activity.
 */
class AppContainer(isDebugBuild: Boolean) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val factory: RadioTransportFactory = RadioTransportFactoryImpl(scope, isDebugBuild)

    val connectionManager = RadioConnectionManager(factory, scope)

    /** В release демо-устройств нет, а BLE-сканирование появится на этапе 2. */
    val devices: List<DeviceListEntry> =
        if (isDebugBuild) {
            Scenarios.all.map { DeviceListEntry.Demo(it.id, it.displayName) }
        } else {
            emptyList()
        }
}
```

`app/src/main/kotlin/com/cerocoder/meshtest/MeshTestApp.kt`:

```kotlin
package com.cerocoder.meshtest

import android.app.Application

class MeshTestApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(isDebugBuild = BuildConfig.DEBUG)
    }
}
```

В `app/src/main/AndroidManifest.xml` добавить в тег `<application>` атрибут:

```xml
        android:name=".MeshTestApp"
```

- [ ] **Step 6: Проверить сборку**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, все тесты проходят.

- [ ] **Step 7: Коммит**

```bash
git add app/src/main app/src/test
git commit -m "feat: фабрика транспортов и контейнер зависимостей"
```

---

### Task 9: Экраны — список устройств и лента пакетов

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/ui/PacketFormatter.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/ui/DeviceListScreen.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshtest/ui/PacketLogScreen.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshtest/MainActivity.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshtest/ui/PacketFormatterTest.kt`

**Interfaces:**
- Consumes: `AppContainer`, `ConnectionState`, `RadioConnectionManager`, `DeviceListEntry`, `Scenarios`.
- Produces: `formatPacket(frame: FromRadio): String`; Composable-функции `DeviceListScreen(devices, state, onSelect, onDisconnect, onOpenLog)` и `PacketLogScreen(packets, onBack)`.

- [ ] **Step 1: Написать падающий тест форматтера**

`app/src/test/kotlin/com/cerocoder/meshtest/ui/PacketFormatterTest.kt`:

```kotlin
package com.cerocoder.meshtest.ui

import com.cerocoder.meshtest.transport.MeshProtocol
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.QueueStatus
import org.meshtastic.proto.User

class PacketFormatterTest {

    @Test
    fun `MyNodeInfo показывает номер локальной ноды`() {
        val text = formatPacket(FromRadio(my_info = MyNodeInfo(my_node_num = 0x11223344)))

        assertTrue(text.contains("MyNodeInfo"))
        assertTrue(text.contains("287454020"))
    }

    @Test
    fun `NodeInfo показывает короткое имя`() {
        val frame = FromRadio(node_info = NodeInfo(num = 7, user = User(short_name = "Д01")))

        val text = formatPacket(frame)

        assertTrue(text.contains("NodeInfo"))
        assertTrue(text.contains("Д01"))
    }

    @Test
    fun `подтверждение конфигурации показывает нонс`() {
        val text = formatPacket(FromRadio(config_complete_id = MeshProtocol.CONFIG_NONCE))

        assertTrue(text.contains("69420"))
    }

    @Test
    fun `статус очереди распознаётся`() {
        val text = formatPacket(FromRadio(queueStatus = QueueStatus(free = 16)))

        assertTrue(text.contains("QueueStatus"))
    }

    @Test
    fun `неизвестный кадр не приводит к пустой строке`() {
        val text = formatPacket(FromRadio(id = 42))

        assertTrue(text.isNotBlank())
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

Run: `./gradlew :app:testDebugUnitTest --tests "*PacketFormatterTest*"`
Expected: FAIL — `Unresolved reference: formatPacket`.

- [ ] **Step 3: Реализовать форматтер**

`app/src/main/kotlin/com/cerocoder/meshtest/ui/PacketFormatter.kt`:

```kotlin
package com.cerocoder.meshtest.ui

import org.meshtastic.proto.FromRadio

/** Краткое человекочитаемое описание кадра для диагностической ленты. */
fun formatPacket(frame: FromRadio): String = when {
    frame.my_info != null ->
        "MyNodeInfo: узел ${frame.my_info.my_node_num}, нод в базе ${frame.my_info.nodedb_count}"

    frame.metadata != null ->
        "Metadata: прошивка ${frame.metadata.firmware_version}"

    frame.config != null ->
        "Config: ${configKind(frame)}"

    frame.moduleConfig != null ->
        "ModuleConfig"

    frame.channel != null ->
        "Channel[${frame.channel.index}] ${frame.channel.settings?.name.orEmpty()}"

    frame.node_info != null ->
        "NodeInfo: ${frame.node_info.user?.short_name.orEmpty()} (${frame.node_info.num})"

    frame.config_complete_id != null ->
        "ConfigComplete: нонс ${frame.config_complete_id}"

    frame.queueStatus != null ->
        "QueueStatus: свободно ${frame.queueStatus.free}"

    frame.packet != null ->
        "MeshPacket от ${frame.packet.from}"

    else -> "Кадр #${frame.id}"
}

private fun configKind(frame: FromRadio): String {
    val config = frame.config ?: return "?"
    return when {
        config.device != null -> "device"
        config.position != null -> "position"
        config.power != null -> "power"
        config.network != null -> "network"
        config.display != null -> "display"
        config.lora != null -> "lora"
        config.bluetooth != null -> "bluetooth"
        else -> "прочее"
    }
}
```

- [ ] **Step 4: Запустить тесты — должны пройти**

Run: `./gradlew :app:testDebugUnitTest --tests "*PacketFormatterTest*"`
Expected: PASS, 5 тестов.

- [ ] **Step 5: Написать экран списка устройств**

`app/src/main/kotlin/com/cerocoder/meshtest/ui/DeviceListScreen.kt`:

```kotlin
package com.cerocoder.meshtest.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cerocoder.meshtest.connection.ConnectionState
import com.cerocoder.meshtest.transport.DeviceListEntry

@Composable
fun DeviceListScreen(
    devices: List<DeviceListEntry>,
    state: ConnectionState,
    onSelect: (DeviceListEntry) -> Unit,
    onDisconnect: () -> Unit,
    onOpenLog: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Состояние: ${stateLabel(state)}", style = MaterialTheme.typography.titleMedium)

        Button(onClick = onOpenLog, modifier = Modifier.padding(top = 8.dp)) {
            Text("Лента пакетов")
        }

        Button(onClick = onDisconnect, modifier = Modifier.padding(top = 8.dp)) {
            Text("Отключиться")
        }

        if (devices.isEmpty()) {
            Text(
                "Устройств нет. Демо-устройства доступны только в debug-сборке, " +
                    "BLE-сканирование появится на этапе 2.",
                modifier = Modifier.padding(top = 16.dp),
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
            items(devices) { device ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(device) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(device.name, style = MaterialTheme.typography.bodyLarge)
                    Text(device.address, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}

private fun stateLabel(state: ConnectionState): String = when (state) {
    ConnectionState.Disconnected -> "отключено"
    ConnectionState.Connecting -> "подключение (идёт handshake)"
    ConnectionState.Connected -> "подключено"
}
```

- [ ] **Step 6: Написать экран ленты пакетов**

`app/src/main/kotlin/com/cerocoder/meshtest/ui/PacketLogScreen.kt`:

```kotlin
package com.cerocoder.meshtest.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.meshtastic.proto.FromRadio

@Composable
fun PacketLogScreen(packets: List<FromRadio>, onBack: () -> Unit) {
    val listState = rememberLazyListState()

    LaunchedEffect(packets.size) {
        if (packets.isNotEmpty()) {
            listState.animateScrollToItem(packets.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = onBack) {
            Text("К списку устройств")
        }

        Text(
            "Принято кадров: ${packets.size}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )

        LazyColumn(state = listState, modifier = Modifier.padding(top = 8.dp)) {
            items(packets) { frame ->
                Text(
                    formatPacket(frame),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        }
    }
}
```

- [ ] **Step 7: Связать экраны в MainActivity**

Заменить содержимое `app/src/main/kotlin/com/cerocoder/meshtest/MainActivity.kt`:

```kotlin
package com.cerocoder.meshtest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.cerocoder.meshtest.ui.DeviceListScreen
import com.cerocoder.meshtest.ui.PacketLogScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MeshTestApp).container

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier) {
                    var showLog by remember { mutableStateOf(false) }
                    val state by container.connectionManager.connectionState.collectAsState()
                    val packets by container.connectionManager.packetLog.collectAsState()
                    val scope = rememberCoroutineScope()

                    if (showLog) {
                        PacketLogScreen(packets = packets, onBack = { showLog = false })
                    } else {
                        DeviceListScreen(
                            devices = container.devices,
                            state = state,
                            onSelect = { device -> container.connectionManager.connect(device.address) },
                            onDisconnect = { scope.launch { container.connectionManager.disconnect() } },
                            onOpenLog = { showLog = true },
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 8: Полная проверка сборки и тестов**

Run: `./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, все тесты проходят (46 тестов: 1 + 7 + 9 + 8 + 12 + 4 + 5).

- [ ] **Step 9: Ручная приёмка на эмуляторе**

Установить debug-сборку на эмулятор Android (Bluetooth не нужен) и проверить:

1. в списке видны четыре демо-устройства: «Demo: 5 нод», «Demo: 200 нод», «Demo: пустой меш», «Demo: только handshake»;
2. тап по «Demo: 5 нод» переводит состояние `отключено` → `подключение (идёт handshake)` → `подключено`;
3. на экране ленты первым кадром идёт `MyNodeInfo`, далее `Metadata`, `Config`, `Channel`, затем пять `NodeInfo` и два `ConfigComplete`;
4. «Demo: 200 нод» доводит соединение до `подключено`, лента содержит 200 кадров `NodeInfo`;
5. «Отключиться» возвращает состояние в `отключено`.

Проверить release-сборку: `./gradlew :app:installRelease` (или собрать APK и поставить вручную) — список устройств пуст, показывается пояснение.

- [ ] **Step 10: Коммит**

```bash
git add app/src/main/kotlin/com/cerocoder/meshtest/ui app/src/main/kotlin/com/cerocoder/meshtest/MainActivity.kt app/src/test/kotlin/com/cerocoder/meshtest/ui
git commit -m "feat: экран устройств и диагностическая лента пакетов"
```

---

## Что дальше

После завершения этапа 1 составляется отдельный план этапа 2 (BLE): разрешения, `BleScanner`, `MeshGattClient` / `MeshRadioProfile`, `NordicMeshGattClient`, `BleRadioTransport`, foreground-сервис. Точная форма API `BleManager 2.11` пиннится в тот момент по актуальной документации Nordic — сейчас она умышленно не фиксируется, чтобы план не содержал непроверенных вызовов.
