# План реализации: разбор кадров и детальный вид

> **Для исполнителей-агентов:** ОБЯЗАТЕЛЬНЫЙ ПОД-НАВЫК: используйте
> superpowers:subagent-driven-development (рекомендуется) либо
> superpowers:executing-plans для выполнения задача за задачей. Шаги помечены
> чекбоксами (`- [ ]`).

**Цель:** превратить ленту кадров в инструмент разбора — кадр открывается по
щелчку и показывает своё содержимое целиком, именами полей из схемы протокола.

**Архитектура:** подписи и порядок полей берутся из аннотации `@WireField` во
время выполнения, а не переписываются руками; поверх обхода лежит таблица
подсказок (единицы, масштабы, идентификаторы нод, эпохи) и таблица «portnum →
как читать нагрузку». Разбор целиком отделён от Compose и проверяется обычными
JVM-тестами.

**Технологии:** Kotlin, Jetpack Compose, Square Wire 6.4.5 (через
`org.meshtastic:protobufs:2.7.26`), JUnit 4, kotlinx-coroutines-test.

**Спека:** `docs/superpowers/specs/2026-08-24-frame-inspector-design.md` —
читать вместе с планом; план из неё выводится, и спорные места решаются в её
пользу.

## Глобальные ограничения

- AGP 9.3.1, Gradle 9.7.1, Kotlin 2.4.10 (встроен в AGP 9), JDK 21.
- `compileSdk = 37`, `targetSdk = 36`, `minSdk = 26`. Compose BOM 2026.06.01.
- `org.meshtastic:protobufs:2.7.26`.
- **Новых зависимостей не добавляется.** В частности, `kotlin-reflect` под
  запретом: обход полей делается Java-рефлексией.
- `PACKET_LOG_LIMIT = 500` — не менять.
- Формат идентификатора ноды: `!` + восемь строчных 16-ричных цифр.
- **Подписи полей — английские, именами из схемы.** Русский допустим только в
  рамке приложения: заголовки экранов, кнопки, счётчики. Значение поля никогда
  не переводится.
- Разбор нагрузки не имеет права бросать исключение наружу.
- Комментарии в коде — по-русски, как во всём проекте: объясняют «почему», а не
  «что».

## Как запускаются тесты

Локально в этой среде **нет ни Gradle, ни Kotlin-компилятора, ни Android SDK**,
а установленный JDK — 17, тогда как артефакт протобуфов собран под Java 21.
Поэтому прогон тестов означает: закоммитить, запушить ветку и прочитать вердикт
CI. Команда, которую выполняет CI:

```
gradle :app:testDebugUnitTest
```

Отдельный класс тестов:

```
gradle :app:testDebugUnitTest --tests "com.cerocoder.meshtest.frame.FieldWalkerTest"
```

При падении CI публикует хвост вывода Gradle комментарием к коммиту — читать
надо **конец** комментария, там сообщение компилятора или теста.

Из-за стоимости круга (около четырёх минут) внутри одной задачи допускается
писать тест и реализацию, а затем пушить один раз: «красный» шаг обосновывается
рассуждением в теле задачи, а не отдельным прогоном. Порядок при этом
сохраняется — тест пишется первым.

---

## Задача 1: обход полей по схеме

Первой идёт самая рискованная часть. Весь дизайн держится на том, что имя и
значение поля читаются во время выполнения. Аннотация точно доступна (проверено
по исходникам `wire-runtime` 6.4.5: `@Retention(RUNTIME)`), а вот чтение
значения приватного backing-поля на JDK 21 и на устройстве не проверялось:
локальная проба не запустилась, потому что jar протобуфов собран под Java 21, а
локально доступны только JDK 11 и 17. Поэтому проверка — здесь и настоящим
тестом.

**Файлы:**
- Создать: `app/src/main/kotlin/com/cerocoder/meshtest/frame/FrameDetail.kt`
- Создать: `app/src/main/kotlin/com/cerocoder/meshtest/frame/FieldWalker.kt`
- Тест: `app/src/test/kotlin/com/cerocoder/meshtest/frame/FieldWalkerTest.kt`

**Интерфейсы:**
- Потребляет: ничего из предыдущих задач.
- Отдаёт:
  - `data class DetailField(val name: String, val value: String)`
  - `data class Section(val title: String, val fields: List<DetailField>)`
  - `data class FrameDetail(val title: String, val common: List<DetailField>, val sections: List<Section>)`
  - `data class RawField(val name: String, val order: Int, val value: Any)`
  - `FieldWalker.rawFields(message: Any, includeDefaults: Boolean = false): List<RawField>`
  - `FieldWalker.unknownFields(message: Any): ByteString?`

- [ ] **Шаг 1: написать падающий тест**

`app/src/test/kotlin/com/cerocoder/meshtest/frame/FieldWalkerTest.kt`:

```kotlin
package com.cerocoder.meshtest.frame

import okio.ByteString.Companion.decodeHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.Compressed
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.RouteDiscovery

class FieldWalkerTest {

    @Test
    fun `значение приватного поля читается рефлексией`() {
        val fields = FieldWalker.rawFields(MeshPacket(from = 5))

        assertEquals(1, fields.size)
        assertEquals("from", fields[0].name)
        assertEquals(5, fields[0].value)
    }

    @Test
    fun `имя берётся из схемы, а не из свойства Kotlin`() {
        // В .proto поле называется `data`, Wire переименовал свойство в `data_`
        // и положил исходное имя в declaredName. Показывать надо имя из схемы:
        // по нему человек сверяется с документацией протокола.
        val fields = FieldWalker.rawFields(
            Compressed(portnum = PortNum.TEXT_MESSAGE_APP, data_ = "01020304".decodeHex()),
        )

        assertTrue(fields.any { it.name == "data" })
        assertTrue(fields.none { it.name == "data_" })
    }

    @Test
    fun `поля идут в порядке объявления в схеме`() {
        val packet = MeshPacket(from = 1, rx_time = 1_700_000_000, rx_snr = 6.25f)

        val names = FieldWalker.rawFields(packet).map { it.name }

        assertEquals(listOf("from", "rx_time", "rx_snr"), names)
    }

    @Test
    fun `значения по умолчанию скрыты`() {
        // Без этого ModuleConfig покажет 140 нулей и утопит содержательное.
        val fields = FieldWalker.rawFields(MeshPacket(from = 0, to = 7, want_ack = false))

        assertEquals(listOf("to"), fields.map { it.name })
    }

    @Test
    fun `значения по умолчанию возвращаются по требованию`() {
        val fields = FieldWalker.rawFields(MeshPacket(to = 7), includeDefaults = true)

        assertTrue(fields.any { it.name == "from" })
        assertTrue(fields.any { it.name == "want_ack" })
    }

    @Test
    fun `перечисление в нулевом значении считается умолчанием`() {
        val fields = FieldWalker.rawFields(MyNodeInfo(my_node_num = 7))

        assertEquals(listOf("my_node_num"), fields.map { it.name })
    }

    @Test
    fun `присутствующий ноль в поле с явным присутствием виден`() {
        // hops_away = 0 означает «нода в прямой видимости». Спрятать его как
        // умолчание — значит показать «неизвестно» вместо «напрямую».
        val fields = FieldWalker.rawFields(NodeInfo(num = 7, hops_away = 0))

        assertTrue(fields.any { it.name == "hops_away" && it.value == 0 })
    }

    @Test
    fun `отсутствующее поле с явным присутствием скрыто`() {
        val fields = FieldWalker.rawFields(NodeInfo(num = 7))

        assertEquals(listOf("num"), fields.map { it.name })
    }

    @Test
    fun `пустое повторяющееся поле скрыто`() {
        // У повторяющихся полей явного присутствия нет: пустой список и
        // отсутствие неотличимы, как и ноль у обычного скаляра.
        val fields = FieldWalker.rawFields(RouteDiscovery())

        assertTrue(fields.isEmpty())
    }

    @Test
    fun `неизвестные схеме поля видны`() {
        // FromRadio { id = 42; неизвестный тег 900 = 42 }. Непустое значение —
        // прямой признак, что прошивка ноды новее протобуфов приложения.
        val bytes = byteArrayOf(0x08, 0x2A, 0xA0.toByte(), 0x38, 0x2A)

        val frame = FromRadio.ADAPTER.decode(bytes)

        assertEquals(42, frame.id)
        assertTrue(FieldWalker.unknownFields(frame)!!.size > 0)
    }

    @Test
    fun `у чистого сообщения неизвестных полей нет`() {
        assertNull(FieldWalker.unknownFields(MyNodeInfo(my_node_num = 7)))
    }
}
```

- [ ] **Шаг 2: убедиться, что тест падает**

Ожидание: ошибка компиляции — `Unresolved reference 'FieldWalker'`.

- [ ] **Шаг 3: написать модель отображения**

`app/src/main/kotlin/com/cerocoder/meshtest/frame/FrameDetail.kt`:

```kotlin
package com.cerocoder.meshtest.frame

/**
 * Одна строка детального вида: имя поля из схемы и его значение.
 *
 * Имя намеренно английское, из `.proto`. Подпись, придуманная приложением,
 * разошлась бы с документацией протокола — а сверяться человек будет именно с
 * ней, и расхождение стоило бы дороже перевода.
 *
 * Класс называется `DetailField`, а не `Field`, чтобы в обходчике не
 * сталкиваться с `java.lang.reflect.Field`.
 */
data class DetailField(val name: String, val value: String)

/**
 * Группа строк. Заголовок несёт путь: `packet`, `packet.decoded`,
 * `packet.decoded.payload (Position)`.
 *
 * Список секций плоский, вложенных секций нет: на узком экране отступы,
 * растущие вправо, съедают ширину быстрее, чем добавляют ясности.
 */
data class Section(val title: String, val fields: List<DetailField>)

/** Готовое к отрисовке содержимое кадра. */
data class FrameDetail(
    val title: String,
    val common: List<DetailField>,
    val sections: List<Section>,
)
```

- [ ] **Шаг 4: написать обходчик**

`app/src/main/kotlin/com/cerocoder/meshtest/frame/FieldWalker.kt`:

```kotlin
package com.cerocoder.meshtest.frame

import com.squareup.wire.Message
import com.squareup.wire.WireEnum
import com.squareup.wire.WireField
import okio.ByteString

/** Поле сообщения protobuf, прочитанное по схеме. */
data class RawField(val name: String, val order: Int, val value: Any)

/**
 * Обход полей сообщения Wire через аннотацию `@WireField`.
 *
 * Подписи и порядок берутся из схемы во время выполнения. Альтернатива —
 * завести вручную порядка шестисот подписей на восемнадцать вариантов кадра и
 * все portnum, и поддерживать их при каждом обновлении протобуфов. Аннотация
 * это снимает: у неё retention RUNTIME, а `declaredName` несёт то самое имя,
 * что стоит в `.proto`.
 *
 * Цена решения: обход держится на именах полей и аннотациях, поэтому при
 * включении `minify` в release понадобятся keep-правила на
 * `org.meshtastic.proto.**`. Сейчас `isMinifyEnabled = false`.
 */
object FieldWalker {

    fun rawFields(message: Any, includeDefaults: Boolean = false): List<RawField> {
        val result = mutableListOf<RawField>()
        for (field in message.javaClass.declaredFields) {
            val wire = field.getAnnotation(WireField::class.java) ?: continue
            // Wire ставит аннотацию на backing-поле, а оно приватное.
            field.isAccessible = true
            val value = field.get(message) ?: continue
            // Явное присутствие Wire выражает nullable-типом, и проверка на null
            // выше его уже разобрала. Ноль в таком поле — настоящее значение:
            // NodeInfo.hops_away = 0 означает «нода в прямой видимости», и
            // спрятать его значит подменить смысл на «неизвестно».
            if (!includeDefaults && !hasExplicitPresence(wire.label) && isIdentity(value)) continue
            val name = wire.declaredName.ifEmpty { field.name }
            // schemaIndex — позиция в объявлении. Порядок declaredFields не
            // определён спецификацией JVM, поэтому сортировка обязательна:
            // без неё поля кадра выстроятся произвольно и по-разному на разных
            // машинах. Запасной ключ — номер тега, он есть всегда.
            val order = if (wire.schemaIndex >= 0) wire.schemaIndex else wire.tag
            result += RawField(name, order, value)
        }
        result.sortBy { it.order }
        return result
    }

    /**
     * Байты, которых нет в схеме приложения.
     *
     * Wire их сохраняет при разборе. Непустое значение означает, что прошивка
     * ноды знает поля, которых не знают протобуфы приложения, — сведение,
     * которое иначе увидеть негде.
     */
    fun unknownFields(message: Any): ByteString? =
        (message as? Message<*, *>)?.unknownFields?.takeIf { it.size > 0 }

    /**
     * У поля есть явное присутствие: proto3 `optional` и члены `oneof`.
     *
     * Wire помечает и то и другое меткой OPTIONAL — отдельной метки для oneof в
     * аннотации нет, там проставляется oneofName. ONE_OF в этой схеме не
     * встречается, но перечисление его содержит, и по смыслу он тоже про
     * присутствие.
     */
    private fun hasExplicitPresence(label: WireField.Label): Boolean =
        label == WireField.Label.OPTIONAL || label == WireField.Label.ONE_OF

    /**
     * Значение неотличимо от «поле не задано».
     *
     * В proto3 у полей без явного присутствия ноль и отсутствие — одно и то же,
     * различить их невозможно в принципе. Поэтому такие поля скрываются: иначе
     * `ModuleConfig` выдаёт 140 нулей, среди которых теряется заданное.
     *
     * К полям с явным присутствием это правило не применяется — там ноль
     * значащий; см. `hasExplicitPresence`.
     */
    private fun isIdentity(value: Any): Boolean = when (value) {
        is Int -> value == 0
        is Long -> value == 0L
        is Float -> value == 0f
        is Double -> value == 0.0
        is Boolean -> !value
        is String -> value.isEmpty()
        is ByteString -> value.size == 0
        is Collection<*> -> value.isEmpty()
        is Map<*, *> -> value.isEmpty()
        is WireEnum -> value.value == 0
        else -> false
    }
}
```

- [ ] **Шаг 5: прогнать тесты**

Ожидание: одиннадцать тестов `FieldWalkerTest` проходят. **Если падает чтение
значения** — переходить на запасной путь из спеки §13: публичные геттеры
Kotlin-свойств (`getBattery_level()`), сопоставляемые с аннотированным полем по
имени. Сообщить об этом в отчёте: меняется реализация `rawFields`, но не её
сигнатура и не остальные задачи.

- [ ] **Шаг 6: коммит**

```bash
git add app/src/main/kotlin/com/cerocoder/meshtest/frame/ app/src/test/kotlin/com/cerocoder/meshtest/frame/
git commit -m "feat: обход полей protobuf по схеме через @WireField"
```

---

## Задача 2: форматирование значений

**Файлы:**
- Создать: `app/src/main/kotlin/com/cerocoder/meshtest/frame/FieldHints.kt`
- Тест: `app/src/test/kotlin/com/cerocoder/meshtest/frame/FieldHintsTest.kt`

**Интерфейсы:**
- Потребляет: ничего (работает со значениями, не с обходчиком).
- Отдаёт:
  - `class FieldHints(zone: ZoneId = ZoneId.systemDefault())`
  - `FieldHints.format(message: String, field: String, value: Any): String`
  - `FieldHints.Companion.nodeId(num: Int): String`
  - `FieldHints.Companion.hex(bytes: ByteString): String`

- [ ] **Шаг 1: написать падающий тест**

`app/src/test/kotlin/com/cerocoder/meshtest/frame/FieldHintsTest.kt`:

```kotlin
package com.cerocoder.meshtest.frame

import java.time.ZoneId
import okio.ByteString.Companion.decodeHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.PortNum

class FieldHintsTest {

    private val hints = FieldHints(ZoneId.of("UTC"))

    @Test
    fun `номер ноды печатается 16-рично`() {
        assertEquals("!a1b2c3d4", FieldHints.nodeId(0xA1B2C3D4.toInt()))
    }

    @Test
    fun `широковещательный адрес помечается`() {
        val text = FieldHints.nodeId(0xFFFFFFFF.toInt())

        assertTrue(text.startsWith("!ffffffff"))
        assertTrue(text.length > "!ffffffff".length)
    }

    @Test
    fun `нулевой адрес не выдаётся за ноду`() {
        val text = FieldHints.nodeId(0)

        assertTrue(text.contains("!00000000"))
        assertTrue(text.length > "!00000000".length)
    }

    @Test
    fun `поле from показывается как номер ноды`() {
        assertEquals("!a1b2c3d4", hints.format("MeshPacket", "from", 0xA1B2C3D4.toInt()))
    }

    @Test
    fun `next_hop показывается байтом, а не номером ноды`() {
        // По схеме next_hop и relay_node — последний байт номера ноды.
        // Показать его как !000000ab значило бы соврать.
        val text = hints.format("MeshPacket", "next_hop", 0xAB)

        assertEquals("0xab", text)
    }

    @Test
    fun `эпоха печатается датой`() {
        val text = hints.format("MeshPacket", "rx_time", 1_700_000_000)

        assertEquals("2023-11-14 22:13:20", text)
    }

    @Test
    fun `нулевая эпоха не выдаётся за 1970 год`() {
        val text = hints.format("MeshPacket", "rx_time", 0)

        assertTrue(text.contains("не задано"))
        assertTrue(!text.contains("1970"))
    }

    @Test
    fun `координата масштабируется`() {
        val text = hints.format("Position", "latitude_i", 556_012_340)

        assertTrue(text.startsWith("55.6012"))
    }

    @Test
    fun `единица измерения берётся из схемы`() {
        assertEquals("3.98 V", hints.format("DeviceMetrics", "voltage", 3.98f))
        assertEquals("87 %", hints.format("DeviceMetrics", "battery_level", 87))
    }

    @Test
    fun `байты печатаются 16-рично независимо от таблицы`() {
        val text = hints.format("User", "public_key", "0a1b2c".decodeHex())

        assertTrue(text.contains("0a1b2c"))
        assertTrue(text.contains("3"))
    }

    @Test
    fun `перечисление печатается именем из схемы`() {
        assertEquals("TELEMETRY_APP", hints.format("Data", "portnum", PortNum.TELEMETRY_APP))
    }

    @Test
    fun `неизвестное поле печатается как есть`() {
        assertEquals("17", hints.format("Неведомое", "поле", 17))
    }
}
```

- [ ] **Шаг 2: убедиться, что тест падает**

Ожидание: `Unresolved reference 'FieldHints'`.

- [ ] **Шаг 3: написать таблицу**

`app/src/main/kotlin/com/cerocoder/meshtest/frame/FieldHints.kt`:

```kotlin
package com.cerocoder.meshtest.frame

import com.squareup.wire.WireEnum
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import okio.ByteString

/**
 * Как показать значение поля.
 *
 * Обходчик знает имя и тип, но не знает смысла: что `latitude_i` — это градусы
 * ×1e-7, что `rx_time` — секунды эпохи, а `from` — номер ноды, который принято
 * писать 16-рично. Таблица добавляет ровно это знание — порядка восьмидесяти
 * записей вместо шестисот ручных подписей.
 *
 * Часовой пояс — параметр, а не `systemDefault()` внутри: иначе тест на
 * форматирование даты зависел бы от машины, на которой запущен.
 */
class FieldHints(private val zone: ZoneId = ZoneId.systemDefault()) {

    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun format(message: String, field: String, value: Any): String {
        // Тип важнее таблицы: байты и перечисления показываются одинаково
        // всюду, где встретятся, и заводить на них записи бессмысленно.
        if (value is ByteString) return hex(value)
        if (value is WireEnum) return value.toString()
        if (value is Collection<*>) {
            return value.joinToString(", ") { item ->
                if (item == null) "null" else format(message, field, item)
            }
        }

        return when (hints["$message.$field"]?.render ?: Render.PLAIN) {
            Render.NODE_ID -> nodeId(value as Int)
            Render.NODE_ID_LAST_BYTE -> "0x%02x".format(value as Int)
            Render.EPOCH_SECONDS -> epoch(value as Int)
            Render.SCALED_1E7 -> "%.7f".format((value as Int) / 1e7)
            Render.PLAIN -> {
                val unit = hints["$message.$field"]?.unit
                if (unit == null) value.toString() else "$value $unit"
            }
        }
    }

    private fun epoch(seconds: Int): String =
        // Ноль здесь означает «нода времени не знает»: у неё может не быть ни
        // GPS, ни связи с сетью. Показать 1970 год — сбить с толку.
        if (seconds == 0) {
            "0 (не задано)"
        } else {
            time.format(Instant.ofEpochSecond(seconds.toLong()).atZone(zone))
        }

    private enum class Render { PLAIN, NODE_ID, NODE_ID_LAST_BYTE, EPOCH_SECONDS, SCALED_1E7 }

    private data class Hint(val render: Render = Render.PLAIN, val unit: String? = null)

    private val hints: Map<String, Hint> = mapOf(
        // Номера нод.
        "MeshPacket.from" to Hint(Render.NODE_ID),
        "MeshPacket.to" to Hint(Render.NODE_ID),
        "Data.dest" to Hint(Render.NODE_ID),
        "Data.source" to Hint(Render.NODE_ID),
        "MyNodeInfo.my_node_num" to Hint(Render.NODE_ID),
        "NodeInfo.num" to Hint(Render.NODE_ID),
        "NodeInfoLite.num" to Hint(Render.NODE_ID),
        "NeighborInfo.node_id" to Hint(Render.NODE_ID),
        "NeighborInfo.last_sent_by_id" to Hint(Render.NODE_ID),
        "Neighbor.node_id" to Hint(Render.NODE_ID),
        "RouteDiscovery.route" to Hint(Render.NODE_ID),
        "RouteDiscovery.route_back" to Hint(Render.NODE_ID),
        "MapReport.node_id" to Hint(Render.NODE_ID),
        "StoreForwardPlusPlus.encapsulated_to" to Hint(Render.NODE_ID),
        "StoreForwardPlusPlus.encapsulated_from" to Hint(Render.NODE_ID),

        // Последний байт номера, а не номер.
        "MeshPacket.next_hop" to Hint(Render.NODE_ID_LAST_BYTE),
        "MeshPacket.relay_node" to Hint(Render.NODE_ID_LAST_BYTE),

        // Секунды эпохи.
        "MeshPacket.rx_time" to Hint(Render.EPOCH_SECONDS),
        "NodeInfo.last_heard" to Hint(Render.EPOCH_SECONDS),
        "NodeInfoLite.last_heard" to Hint(Render.EPOCH_SECONDS),
        "Position.time" to Hint(Render.EPOCH_SECONDS),
        "Position.timestamp" to Hint(Render.EPOCH_SECONDS),
        "Telemetry.time" to Hint(Render.EPOCH_SECONDS),
        "LogRecord.time" to Hint(Render.EPOCH_SECONDS),
        "Waypoint.expire" to Hint(Render.EPOCH_SECONDS),
        "StoreForwardPlusPlus.encapsulated_rxtime" to Hint(Render.EPOCH_SECONDS),

        // Градусы ×1e-7.
        "Position.latitude_i" to Hint(Render.SCALED_1E7),
        "Position.longitude_i" to Hint(Render.SCALED_1E7),
        "PositionLite.latitude_i" to Hint(Render.SCALED_1E7),
        "PositionLite.longitude_i" to Hint(Render.SCALED_1E7),
        "MapReport.latitude_i" to Hint(Render.SCALED_1E7),
        "MapReport.longitude_i" to Hint(Render.SCALED_1E7),
        "TAKPacket.latitude_i" to Hint(Render.SCALED_1E7),
        "TAKPacketV2.latitude_i" to Hint(Render.SCALED_1E7),
        "TAKPacketV2.longitude_i" to Hint(Render.SCALED_1E7),
        "Waypoint.latitude_i" to Hint(Render.SCALED_1E7),
        "Waypoint.longitude_i" to Hint(Render.SCALED_1E7),

        // Единицы измерения из комментариев схемы.
        "MeshPacket.rx_snr" to Hint(unit = "dB"),
        "MeshPacket.rx_rssi" to Hint(unit = "dBm"),
        "NodeInfo.snr" to Hint(unit = "dB"),
        "Neighbor.snr" to Hint(unit = "dB"),
        "DeviceMetrics.battery_level" to Hint(unit = "%"),
        "DeviceMetrics.voltage" to Hint(unit = "V"),
        "DeviceMetrics.channel_utilization" to Hint(unit = "%"),
        "DeviceMetrics.air_util_tx" to Hint(unit = "%"),
        "DeviceMetrics.uptime_seconds" to Hint(unit = "s"),
        "Position.altitude" to Hint(unit = "m"),
        "Position.altitude_hae" to Hint(unit = "m"),
        "Position.altitude_geoidal_separation" to Hint(unit = "m"),
        "Position.ground_speed" to Hint(unit = "m/s"),
        "Position.ground_track" to Hint(unit = "deg"),
        "EnvironmentMetrics.temperature" to Hint(unit = "°C"),
        "EnvironmentMetrics.relative_humidity" to Hint(unit = "%"),
        "EnvironmentMetrics.barometric_pressure" to Hint(unit = "hPa"),
        "EnvironmentMetrics.voltage" to Hint(unit = "V"),
        "EnvironmentMetrics.current" to Hint(unit = "mA"),
        "EnvironmentMetrics.wind_speed" to Hint(unit = "m/s"),
        "EnvironmentMetrics.wind_direction" to Hint(unit = "deg"),
        "PowerMetrics.ch1_voltage" to Hint(unit = "V"),
        "PowerMetrics.ch1_current" to Hint(unit = "mA"),
        "PowerMetrics.ch2_voltage" to Hint(unit = "V"),
        "PowerMetrics.ch2_current" to Hint(unit = "mA"),
        "PowerMetrics.ch3_voltage" to Hint(unit = "V"),
        "PowerMetrics.ch3_current" to Hint(unit = "mA"),
        "LocalStats.uptime_seconds" to Hint(unit = "s"),
        "LocalStats.channel_utilization" to Hint(unit = "%"),
        "LocalStats.air_util_tx" to Hint(unit = "%"),
        "HostMetrics.uptime_seconds" to Hint(unit = "s"),
        "Paxcount.uptime" to Hint(unit = "s"),
    )

    companion object {

        /**
         * Номер ноды в принятом у Meshtastic виде: `!` и восемь строчных
         * 16-ричных цифр. Десятичный номер в протоколе нигде не встречается —
         * ни в интерфейсе ноды, ни в документации, — поэтому показывать его
         * десятичным значит заставлять человека переводить вручную.
         */
        fun nodeId(num: Int): String {
            val hex = "!%08x".format(num)
            return when (num) {
                0xFFFFFFFF.toInt() -> "$hex (broadcast)"
                0 -> "$hex (не задан)"
                else -> hex
            }
        }

        fun hex(bytes: ByteString): String = "${bytes.hex()} (${bytes.size} B)"
    }
}
```

Таблица выше — законченная работа, а не заготовка: поле, которого в ней нет,
печатается как есть, и экран от этого не ломается. Единицы у метрик, не
попавших в список, добавляются по мере того, как они встретятся на живом
трафике; выдумывать их из головы не нужно — единица берётся из комментария к
полю в `.proto`, и только оттуда.

- [ ] **Шаг 4: прогнать тесты**

Ожидание: двенадцать тестов `FieldHintsTest` проходят.

- [ ] **Шаг 5: коммит**

```bash
git add app/src/main/kotlin/com/cerocoder/meshtest/frame/FieldHints.kt app/src/test/kotlin/com/cerocoder/meshtest/frame/FieldHintsTest.kt
git commit -m "feat: единицы, масштабы и 16-ричные идентификаторы нод"
```

---

## Задача 3: чтение нагрузки по portnum

**Файлы:**
- Создать: `app/src/main/kotlin/com/cerocoder/meshtest/frame/PayloadCodecs.kt`
- Тест: `app/src/test/kotlin/com/cerocoder/meshtest/frame/PayloadCodecsTest.kt`

**Интерфейсы:**
- Потребляет: `DetailField` из задачи 1.
- Отдаёт:
  - `sealed interface Payload` с вариантами `Decoded(typeName, message)`,
    `Text(text)`, `Raw(note, fields, bytes)`, `Failed(reason, bytes)`
  - `PayloadCodecs.read(portnum: PortNum, payload: ByteString): Payload`

- [ ] **Шаг 1: написать падающий тест**

`app/src/test/kotlin/com/cerocoder/meshtest/frame/PayloadCodecsTest.kt`:

```kotlin
package com.cerocoder.meshtest.frame

import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.User

class PayloadCodecsTest {

    @Test
    fun `текстовое сообщение читается текстом`() {
        val result = PayloadCodecs.read(PortNum.TEXT_MESSAGE_APP, "привет".encodeUtf8())

        assertEquals(Payload.Text("привет"), result)
    }

    @Test
    fun `позиция разбирается протобуфом`() {
        val bytes = Position(latitude_i = 556_012_340, altitude = 12).encodeByteString()

        val result = PayloadCodecs.read(PortNum.POSITION_APP, bytes)

        assertTrue(result is Payload.Decoded)
        assertEquals("Position", (result as Payload.Decoded).typeName)
        assertEquals(556_012_340, (result.message as Position).latitude_i)
    }

    @Test
    fun `телеметрия разбирается протобуфом`() {
        val bytes = Telemetry(device_metrics = DeviceMetrics(battery_level = 87)).encodeByteString()

        val result = PayloadCodecs.read(PortNum.TELEMETRY_APP, bytes)

        assertTrue(result is Payload.Decoded)
        assertEquals("Telemetry", (result as Payload.Decoded).typeName)
    }

    @Test
    fun `сведения о ноде разбираются как User`() {
        val bytes = User(id = "!a1b2c3d4", short_name = "Дом").encodeByteString()

        val result = PayloadCodecs.read(PortNum.NODEINFO_APP, bytes)

        assertEquals("User", (result as Payload.Decoded).typeName)
    }

    @Test
    fun `непротобуфный порт отдаётся байтами с подписью формата`() {
        val result = PayloadCodecs.read(PortNum.IP_TUNNEL_APP, "45000028".decodeHex())

        assertTrue(result is Payload.Raw)
        assertTrue((result as Payload.Raw).note.isNotBlank())
    }

    @Test
    fun `у codec2 выделяется заголовок`() {
        val result = PayloadCodecs.read(PortNum.AUDIO_APP, "c0dec2030102".decodeHex())

        assertTrue(result is Payload.Raw)
        val names = (result as Payload.Raw).fields.map { it.name }
        assertTrue(names.contains("codec2_header"))
        assertTrue(names.contains("bitrate_marker"))
    }

    @Test
    fun `у моста LoRaWAN метаданные отделены от PHY`() {
        val bytes = "000102030405060708090a0b0c".decodeHex()

        val result = PayloadCodecs.read(PortNum.LORAWAN_BRIDGE, bytes)

        val names = (result as Payload.Raw).fields.map { it.name }
        assertTrue(names.contains("rf_metadata"))
        assertTrue(names.contains("phy_payload"))
    }

    @Test
    fun `битая нагрузка не бросает, а объясняет`() {
        // Мусор под знакомым portnum будет: обрезанный пакет, чужой формат,
        // прошивка новее протобуфов. Экран от этого падать не имеет права.
        val result = PayloadCodecs.read(PortNum.POSITION_APP, "ffffffffffffffff".decodeHex())

        assertTrue(result is Payload.Failed || result is Payload.Decoded)
    }

    @Test
    fun `обрезанный протобуф даёт отказ, а не исключение`() {
        // Заголовок поля есть, тела нет — разбор обязан оборваться.
        val result = PayloadCodecs.read(PortNum.POSITION_APP, "0a".decodeHex())

        assertTrue(result is Payload.Failed)
        assertTrue((result as Payload.Failed).reason.isNotBlank())
    }

    @Test
    fun `неизвестный порт не теряет байты`() {
        val result = PayloadCodecs.read(PortNum.PRIVATE_APP, "0102".decodeHex())

        assertTrue(result is Payload.Raw)
        assertEquals("0102".decodeHex(), (result as Payload.Raw).bytes)
    }

    @Test
    fun `пустая нагрузка не считается отказом`() {
        val result = PayloadCodecs.read(PortNum.TEXT_MESSAGE_APP, okio.ByteString.EMPTY)

        assertEquals(Payload.Text(""), result)
    }
}
```

- [ ] **Шаг 2: убедиться, что тест падает**

Ожидание: `Unresolved reference 'PayloadCodecs'`.

- [ ] **Шаг 3: написать таблицу кодеков**

`app/src/main/kotlin/com/cerocoder/meshtest/frame/PayloadCodecs.kt`:

```kotlin
package com.cerocoder.meshtest.frame

import com.squareup.wire.ProtoAdapter
import okio.ByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Compressed
import org.meshtastic.proto.HardwareMessage
import org.meshtastic.proto.KeyVerification
import org.meshtastic.proto.MapReport
import org.meshtastic.proto.MeshBeacon
import org.meshtastic.proto.NeighborInfo
import org.meshtastic.proto.Paxcount
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.PowerStressMessage
import org.meshtastic.proto.RemoteShell
import org.meshtastic.proto.RouteDiscovery
import org.meshtastic.proto.Routing
import org.meshtastic.proto.StatusMessage
import org.meshtastic.proto.StoreAndForward
import org.meshtastic.proto.StoreForwardPlusPlus
import org.meshtastic.proto.TAKPacket
import org.meshtastic.proto.TAKPacketV2
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.User
import org.meshtastic.proto.Waypoint

/** Что удалось сделать с нагрузкой пакета. */
sealed interface Payload {

    /** Разобрано протобуфом. */
    data class Decoded(val typeName: String, val message: Any) : Payload

    /** По схеме это текст. */
    data class Text(val text: String) : Payload

    /**
     * По схеме это не протобуф. `note` называет объявленный формат, `fields` —
     * то, что удалось выделить из него без полноценного декодера.
     */
    data class Raw(
        val note: String,
        val fields: List<DetailField>,
        val bytes: ByteString,
    ) : Payload

    /** Схема обещала протобуф, но разбор не удался. */
    data class Failed(val reason: String, val bytes: ByteString) : Payload
}

/**
 * Как читать `Data.payload` для каждого portnum.
 *
 * Нагрузка приходит байтами, её тип задан числом. Без этой таблицы детальный
 * вид показывал бы «TELEMETRY_APP, 34 байта» и ничего больше.
 *
 * Не у всех портов протобуфный тип: часть несёт текст, часть — кадры codec2,
 * IP-пакет или Cayenne LPP. Для них честное отображение — назвать формат и
 * показать байты, а не притворяться, что разобрано.
 */
object PayloadCodecs {

    fun read(portnum: PortNum, payload: ByteString): Payload = when (portnum) {
        PortNum.TEXT_MESSAGE_APP,
        PortNum.DETECTION_SENSOR_APP,
        PortNum.ALERT_APP,
        PortNum.REPLY_APP,
        PortNum.RANGE_TEST_APP,
        -> Payload.Text(payload.utf8())

        PortNum.REMOTE_HARDWARE_APP -> decode("HardwareMessage", HardwareMessage.ADAPTER, payload)
        PortNum.POSITION_APP -> decode("Position", Position.ADAPTER, payload)
        PortNum.NODEINFO_APP -> decode("User", User.ADAPTER, payload)
        PortNum.ROUTING_APP -> decode("Routing", Routing.ADAPTER, payload)
        PortNum.ADMIN_APP -> decode("AdminMessage", AdminMessage.ADAPTER, payload)
        PortNum.WAYPOINT_APP -> decode("Waypoint", Waypoint.ADAPTER, payload)
        PortNum.KEY_VERIFICATION_APP -> decode("KeyVerification", KeyVerification.ADAPTER, payload)
        PortNum.REMOTE_SHELL_APP -> decode("RemoteShell", RemoteShell.ADAPTER, payload)
        PortNum.PAXCOUNTER_APP -> decode("Paxcount", Paxcount.ADAPTER, payload)
        PortNum.STORE_FORWARD_PLUSPLUS_APP ->
            decode("StoreForwardPlusPlus", StoreForwardPlusPlus.ADAPTER, payload)
        PortNum.NODE_STATUS_APP -> decode("StatusMessage", StatusMessage.ADAPTER, payload)
        PortNum.MESH_BEACON_APP -> decode("MeshBeacon", MeshBeacon.ADAPTER, payload)
        PortNum.STORE_FORWARD_APP -> decode("StoreAndForward", StoreAndForward.ADAPTER, payload)
        PortNum.TELEMETRY_APP -> decode("Telemetry", Telemetry.ADAPTER, payload)
        PortNum.TRACEROUTE_APP -> decode("RouteDiscovery", RouteDiscovery.ADAPTER, payload)
        PortNum.NEIGHBORINFO_APP -> decode("NeighborInfo", NeighborInfo.ADAPTER, payload)
        PortNum.ATAK_PLUGIN -> decode("TAKPacket", TAKPacket.ADAPTER, payload)
        PortNum.MAP_REPORT_APP -> decode("MapReport", MapReport.ADAPTER, payload)
        PortNum.POWERSTRESS_APP -> decode("PowerStressMessage", PowerStressMessage.ADAPTER, payload)

        // Точное представление в эфире по одной схеме не устанавливается:
        // пробуем объявленный тип, при отказе показываем байты.
        PortNum.TEXT_MESSAGE_COMPRESSED_APP,
        PortNum.SIMULATOR_APP,
        -> attempt("Compressed", Compressed.ADAPTER, payload, "Unishox2-compressed")
        PortNum.ATAK_PLUGIN_V2 ->
            attempt("TAKPacketV2", TAKPacketV2.ADAPTER, payload, "zstd dictionary compressed")

        PortNum.AUDIO_APP -> codec2(payload)
        PortNum.LORAWAN_BRIDGE -> loraWan(payload)

        PortNum.UNKNOWN_APP -> raw("opaque, not understood by the mesh", payload)
        PortNum.IP_TUNNEL_APP -> raw("IP packet", payload)
        PortNum.SERIAL_APP -> raw("serial bytes", payload)
        PortNum.ZPS_APP -> raw("arrays of int64", payload)
        PortNum.RETICULUM_TUNNEL_APP -> raw("fragmented RNS packet", payload)
        PortNum.CAYENNE_APP -> raw("Cayenne LPP", payload)
        PortNum.LORA_OTA_APP -> raw("ota-common transport frames", payload)
        PortNum.GROUPALARM_APP -> raw("GroupAlarm message", payload)
        PortNum.PRIVATE_APP -> raw("private application", payload)
        PortNum.ATAK_FORWARDER -> raw("libcotshrink", payload)
        PortNum.MAX -> raw("port range limit, not a real port", payload)
    }

    private fun <T : Any> decode(name: String, adapter: ProtoAdapter<T>, bytes: ByteString): Payload =
        try {
            Payload.Decoded(name, adapter.decode(bytes))
        } catch (e: Exception) {
            // Ловим Exception, а не IOException: адаптер на мусоре способен
            // бросить и IllegalStateException, и ошибку выхода за границы.
            // Уронить здесь — значит потерять весь кадр из-за одного поля.
            Payload.Failed("$name: ${e.message ?: e.javaClass.simpleName}", bytes)
        }

    private fun <T : Any> attempt(
        name: String,
        adapter: ProtoAdapter<T>,
        bytes: ByteString,
        note: String,
    ): Payload = when (val result = decode(name, adapter, bytes)) {
        is Payload.Failed -> raw(note, bytes)
        else -> result
    }

    private fun codec2(bytes: ByteString): Payload {
        // По схеме кадр начинается с c0 de c2 и байта-маркера битрейта.
        val fields = if (bytes.size >= 4) {
            listOf(
                DetailField("codec2_header", bytes.substring(0, 3).hex()),
                DetailField("bitrate_marker", "0x%02x".format(bytes[3])),
            )
        } else {
            emptyList()
        }
        return Payload.Raw("codec2 audio frames", fields, bytes)
    }

    private fun loraWan(bytes: ByteString): Payload {
        // По схеме: 10 байт метаданных РЧ, дальше PHY-нагрузка.
        val fields = if (bytes.size > 10) {
            listOf(
                DetailField("rf_metadata", bytes.substring(0, 10).hex()),
                DetailField("phy_payload", bytes.substring(10).hex()),
            )
        } else {
            emptyList()
        }
        return Payload.Raw("LoRaWAN uplink", fields, bytes)
    }

    private fun raw(note: String, bytes: ByteString): Payload =
        Payload.Raw(note, emptyList(), bytes)
}
```

Если компилятор пожалуется на неполноту `when` — значит в схеме есть portnum,
которого нет в таблице. Это не повод ставить `else`: добавить недостающий с
подписью его формата из комментария в `.proto`. Ветка `else` тихо проглотила бы
новые порты при обновлении протобуфов, а исчерпывающий `when` о них сообщит.

- [ ] **Шаг 4: прогнать тесты**

Ожидание: одиннадцать тестов `PayloadCodecsTest` проходят.

- [ ] **Шаг 5: коммит**

```bash
git add app/src/main/kotlin/com/cerocoder/meshtest/frame/PayloadCodecs.kt app/src/test/kotlin/com/cerocoder/meshtest/frame/PayloadCodecsTest.kt
git commit -m "feat: чтение нагрузки пакета по portnum"
```

---

## Задача 4: сборка детального вида

**Файлы:**
- Создать: `app/src/main/kotlin/com/cerocoder/meshtest/frame/FrameRecord.kt`
- Создать: `app/src/main/kotlin/com/cerocoder/meshtest/frame/FrameDecoder.kt`
- Тест: `app/src/test/kotlin/com/cerocoder/meshtest/frame/FrameDecoderTest.kt`

**Интерфейсы:**
- Потребляет: `FieldWalker`, `RawField`, `DetailField`, `Section`,
  `FrameDetail` (задача 1); `FieldHints` (задача 2); `PayloadCodecs`, `Payload`
  (задача 3).
- Отдаёт:
  - `data class FrameRecord(seq: Long, receivedAtMillis: Long, sourceAddress: String, sizeBytes: Int, frame: FromRadio)`
  - `frameRecordToList(record: FrameRecord): List<Any>`
  - `frameRecordFromList(saved: List<Any>): FrameRecord?`
  - `class FrameDecoder(hints: FieldHints = FieldHints(), zone: ZoneId = ZoneId.systemDefault())`
  - `FrameDecoder.decode(record: FrameRecord, includeDefaults: Boolean = false): FrameDetail`

`FrameRecord` создаётся здесь, а не в задаче 5, потому что декодер — его первый
потребитель и без него нечего тестировать.

- [ ] **Шаг 1: написать падающий тест**

`app/src/test/kotlin/com/cerocoder/meshtest/frame/FrameDecoderTest.kt`:

```kotlin
package com.cerocoder.meshtest.frame

import java.time.ZoneId
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.QueueStatus
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.User

class FrameDecoderTest {

    private val decoder = FrameDecoder(FieldHints(ZoneId.of("UTC")), ZoneId.of("UTC"))

    private fun record(frame: FromRadio, seq: Long = 1) = FrameRecord(
        seq = seq,
        receivedAtMillis = 1_700_000_009_000,
        sourceAddress = "x00:11:22:33:44:55",
        sizeBytes = frame.encode().size,
        frame = frame,
    )

    /**
     * Все варианты FromRadio, какие есть в схеме. Тест обязан падать при росте
     * схемы: иначе новый вариант молча остался бы без отображения.
     */
    private val allVariants: List<Pair<String, FromRadio>> = listOf(
        "packet" to FromRadio(packet = MeshPacket(from = 7)),
        "my_info" to FromRadio(my_info = MyNodeInfo(my_node_num = 7)),
        "node_info" to FromRadio(node_info = NodeInfo(num = 7)),
        "config" to FromRadio(config = Config(device = Config.DeviceConfig())),
        "log_record" to FromRadio(log_record = org.meshtastic.proto.LogRecord(message = "x")),
        "config_complete_id" to FromRadio(config_complete_id = 69420),
        "rebooted" to FromRadio(rebooted = true),
        "moduleConfig" to FromRadio(moduleConfig = org.meshtastic.proto.ModuleConfig()),
        "channel" to FromRadio(channel = Channel(index = 1)),
        "queueStatus" to FromRadio(queueStatus = QueueStatus(free = 16)),
        "xmodemPacket" to FromRadio(xmodemPacket = org.meshtastic.proto.XModem()),
        "metadata" to FromRadio(metadata = DeviceMetadata(firmware_version = "2.7.0")),
        "mqttClientProxyMessage" to
            FromRadio(mqttClientProxyMessage = org.meshtastic.proto.MqttClientProxyMessage()),
        "fileInfo" to FromRadio(fileInfo = org.meshtastic.proto.FileInfo(file_name = "a")),
        "clientNotification" to
            FromRadio(clientNotification = org.meshtastic.proto.ClientNotification()),
        "deviceuiConfig" to FromRadio(deviceuiConfig = org.meshtastic.proto.DeviceUIConfig()),
        "lockdown_status" to FromRadio(lockdown_status = org.meshtastic.proto.LockdownStatus()),
        "region_presets" to FromRadio(region_presets = org.meshtastic.proto.LoRaRegionPresetMap()),
    )

    @Test
    fun `у каждого варианта кадра есть заголовок и общая часть`() {
        for ((name, frame) in allVariants) {
            val detail = decoder.decode(record(frame))

            assertEquals("вариант $name", name, detail.title)
            val names = detail.common.map { it.name }
            assertTrue("вариант $name: нет номера", names.any { it.contains("Номер") })
            assertTrue("вариант $name: нет размера", names.any { it.contains("Размер") })
            assertTrue("вариант $name: нет часов телефона", names.any { it.contains("телефон") })
            assertTrue("вариант $name: нет источника", names.any { it.contains("Источник") })
        }
    }

    @Test
    fun `в схеме не появилось нового варианта кадра`() {
        // Число вариантов oneof плюс поле id. Расходится — значит протобуфы
        // обновились, и allVariants надо дополнить, иначе новый вариант
        // останется без разбора.
        val declared = FromRadio::class.java.declaredFields.count {
            it.getAnnotation(com.squareup.wire.WireField::class.java) != null
        }

        assertEquals(allVariants.size + 1, declared)
    }

    @Test
    fun `часы ноды показываются там, где нода их прислала`() {
        val frame = FromRadio(packet = MeshPacket(from = 7, rx_time = 1_700_000_000))

        val detail = decoder.decode(record(frame))

        val node = detail.common.first { it.name.contains("ноды") }
        assertEquals("2023-11-14 22:13:20", node.value)
    }

    @Test
    fun `без метки ноды показываются только часы телефона`() {
        val detail = decoder.decode(record(FromRadio(queueStatus = QueueStatus(free = 16))))

        assertTrue(detail.common.none { it.name.contains("ноды") })
        assertTrue(detail.common.any { it.name.contains("телефон") })
    }

    @Test
    fun `номер ноды в секции показан 16-рично`() {
        val frame = FromRadio(packet = MeshPacket(from = 0xA1B2C3D4.toInt()))

        val detail = decoder.decode(record(frame))

        val from = detail.sections.flatMap { it.fields }.first { it.name == "from" }
        assertEquals("!a1b2c3d4", from.value)
    }

    @Test
    fun `вложенное сообщение даёт секцию с путём`() {
        val frame = FromRadio(channel = Channel(index = 1, settings = ChannelSettings(name = "LongFast")))

        val detail = decoder.decode(record(frame))

        assertNotNull(detail.sections.firstOrNull { it.title == "channel.settings" })
    }

    @Test
    fun `нагрузка пакета разбирается и попадает в секцию`() {
        val telemetry = Telemetry(device_metrics = DeviceMetrics(battery_level = 87))
        val frame = FromRadio(
            packet = MeshPacket(
                from = 7,
                decoded = Data(portnum = PortNum.TELEMETRY_APP, payload = telemetry.encodeByteString()),
            ),
        )

        val detail = decoder.decode(record(frame))

        val section = detail.sections.first { it.title.contains("payload") }
        assertTrue(section.title.contains("Telemetry"))
        assertTrue(
            detail.sections.flatMap { it.fields }.any { it.name == "battery_level" && it.value == "87 %" },
        )
    }

    @Test
    fun `текстовое сообщение показывается текстом`() {
        val frame = FromRadio(
            packet = MeshPacket(
                from = 7,
                decoded = Data(portnum = PortNum.TEXT_MESSAGE_APP, payload = "привет".encodeUtf8()),
            ),
        )

        val detail = decoder.decode(record(frame))

        assertTrue(detail.sections.flatMap { it.fields }.any { it.value.contains("привет") })
    }

    @Test
    fun `зашифрованный пакет объявлен, а не потерян`() {
        val frame = FromRadio(
            packet = MeshPacket(from = 7, encrypted = "0a1b2c".encodeUtf8()),
        )

        val detail = decoder.decode(record(frame))

        assertTrue(detail.sections.flatMap { it.fields }.any { it.name == "encrypted" })
    }

    @Test
    fun `битая нагрузка не роняет разбор кадра`() {
        val frame = FromRadio(
            packet = MeshPacket(
                from = 7,
                decoded = Data(portnum = PortNum.POSITION_APP, payload = "0a".encodeUtf8()),
            ),
        )

        val detail = decoder.decode(record(frame))

        assertEquals("packet", detail.title)
        assertTrue(detail.sections.isNotEmpty())
    }

    @Test
    fun `запись переживает сохранение и восстановление`() {
        val original = record(FromRadio(my_info = MyNodeInfo(my_node_num = 0x11223344)), seq = 42)

        val restored = frameRecordFromList(frameRecordToList(original))

        assertEquals(original, restored)
    }

    @Test
    fun `пустой снимок восстанавливается как отсутствие выбора`() {
        assertEquals(null, frameRecordFromList(emptyList()))
    }
}
```

- [ ] **Шаг 2: убедиться, что тест падает**

Ожидание: `Unresolved reference 'FrameDecoder'`, `'FrameRecord'`.

- [ ] **Шаг 3: написать запись ленты**

`app/src/main/kotlin/com/cerocoder/meshtest/frame/FrameRecord.kt`:

```kotlin
package com.cerocoder.meshtest.frame

import org.meshtastic.proto.FromRadio

/**
 * Кадр вместе с тем, что известно о его приёме.
 *
 * Лента хранит записи, а не голые кадры: у самого `FromRadio` нет ни времени
 * приёма, ни размера, ни источника, а показать их надо.
 */
data class FrameRecord(
    /** Номер внутри подключения, с единицы. Обнуляется вместе с лентой. */
    val seq: Long,
    /** Часы телефона, момент приёма. */
    val receivedAtMillis: Long,
    /**
     * Адрес устройства, от которого пришёл кадр.
     *
     * Внутри одного подключения он у всех кадров одинаков, и ленте не нужен.
     * Нужен снимку: открытый детальный вид переживает разрыв и повторное
     * подключение, которое ленту очищает, — а к этому моменту приложение может
     * быть подключено уже к другому устройству.
     */
    val sourceAddress: String,
    /** Длина кадра, как он пришёл по эфиру. */
    val sizeBytes: Int,
    val frame: FromRadio,
)

/**
 * Разбор записи на значения, которые умеет хранить Bundle.
 *
 * Вынесено отдельными функциями, а не спрятано в `Saver`, чтобы проверяться
 * обычным JVM-тестом: `Saver` требует окружения Compose, а формат снимка
 * ошибиться может и без него.
 */
fun frameRecordToList(record: FrameRecord): List<Any> = listOf(
    record.seq,
    record.receivedAtMillis,
    record.sourceAddress,
    record.sizeBytes,
    record.frame.encode(),
)

fun frameRecordFromList(saved: List<Any>): FrameRecord? {
    if (saved.size != 5) return null
    return FrameRecord(
        seq = saved[0] as Long,
        receivedAtMillis = saved[1] as Long,
        sourceAddress = saved[2] as String,
        sizeBytes = saved[3] as Int,
        frame = FromRadio.ADAPTER.decode(saved[4] as ByteArray),
    )
}
```

- [ ] **Шаг 4: написать декодер**

`app/src/main/kotlin/com/cerocoder/meshtest/frame/FrameDecoder.kt`:

```kotlin
package com.cerocoder.meshtest.frame

import com.squareup.wire.Message
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio

/**
 * Сборка содержимого кадра для показа.
 *
 * Общая часть одинакова у всех вариантов, специфическая строится обходом полей.
 * Подписи общей части — русские: это строки самого приложения, а не поля
 * протокола. Там, где значение приходит из поля схемы, имя поля названо в
 * скобках, чтобы было видно, откуда взято.
 */
class FrameDecoder(
    private val hints: FieldHints = FieldHints(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun decode(record: FrameRecord, includeDefaults: Boolean = false): FrameDetail {
        val fields = FieldWalker.rawFields(record.frame, includeDefaults)
        // id не входит в oneof и вариантом кадра не является.
        val variant = fields.firstOrNull { it.name != "id" }
        val title = variant?.name ?: "пустой кадр"

        val sections = mutableListOf<Section>()
        when (val value = variant?.value) {
            null -> Unit
            is Message<*, *> -> collect(variant.name, value, includeDefaults, sections)
            else -> sections += Section(
                variant.name,
                listOf(DetailField(variant.name, hints.format("FromRadio", variant.name, value))),
            )
        }

        return FrameDetail(title, common(record, fields), sections)
    }

    private fun common(record: FrameRecord, fields: List<RawField>): List<DetailField> {
        val rows = mutableListOf(
            DetailField("Номер в ленте", "#${record.seq}"),
            DetailField("Размер", "${record.sizeBytes} B"),
        )
        fields.firstOrNull { it.name == "id" }?.let {
            rows += DetailField("Идентификатор кадра (id)", it.value.toString())
        }
        nodeClock(record.frame)?.let { (field, seconds) ->
            rows += DetailField(
                "Часы ноды ($field)",
                time.format(Instant.ofEpochSecond(seconds.toLong()).atZone(zone)),
            )
        }
        rows += DetailField(
            "Часы телефона",
            time.format(Instant.ofEpochMilli(record.receivedAtMillis).atZone(zone)),
        )
        rows += DetailField("Источник", record.sourceAddress)
        return rows
    }

    /**
     * Метка времени по часам ноды, если кадр её несёт.
     *
     * Несут только три варианта из восемнадцати. Подставлять вместо неё часы
     * телефона нельзя: это разные часы, расходящиеся на неизвестную величину,
     * и выдача одних за другие сделала бы ленту непригодной для разбора
     * задержек — того, ради чего время и показывается.
     */
    private fun nodeClock(frame: FromRadio): Pair<String, Int>? {
        frame.packet?.rx_time?.takeIf { it != 0 }?.let { return "rx_time" to it }
        frame.node_info?.last_heard?.takeIf { it != 0 }?.let { return "last_heard" to it }
        frame.log_record?.time?.takeIf { it != 0 }?.let { return "time" to it }
        return null
    }

    private fun collect(
        path: String,
        message: Any,
        includeDefaults: Boolean,
        out: MutableList<Section>,
    ) {
        val plain = mutableListOf<DetailField>()
        val nested = mutableListOf<Pair<String, Any>>()
        val type = message.javaClass.simpleName

        for (field in FieldWalker.rawFields(message, includeDefaults)) {
            val value = field.value
            when {
                value is Message<*, *> -> nested += "$path.${field.name}" to value
                value is List<*> && value.any { it is Message<*, *> } ->
                    value.forEachIndexed { i, item ->
                        if (item != null) nested += "$path.${field.name}[$i]" to item
                    }
                else -> plain += DetailField(field.name, hints.format(type, field.name, value))
            }
        }

        FieldWalker.unknownFields(message)?.let {
            plain += DetailField("unknown_fields", FieldHints.hex(it))
        }

        out += Section(path, plain)

        if (message is Data) payload(path, message, includeDefaults, out)
        for ((childPath, child) in nested) collect(childPath, child, includeDefaults, out)
    }

    private fun payload(
        path: String,
        data: Data,
        includeDefaults: Boolean,
        out: MutableList<Section>,
    ) {
        if (data.payload.size == 0) return
        when (val payload = PayloadCodecs.read(data.portnum, data.payload)) {
            is Payload.Decoded -> collect("$path.payload (${payload.typeName})", payload.message, includeDefaults, out)
            is Payload.Text -> out += Section(
                "$path.payload (text)",
                listOf(DetailField("text", payload.text)),
            )
            is Payload.Raw -> out += Section(
                "$path.payload (${payload.note})",
                payload.fields + DetailField("bytes", FieldHints.hex(payload.bytes)),
            )
            is Payload.Failed -> out += Section(
                "$path.payload (не разобрано)",
                listOf(
                    DetailField("reason", payload.reason),
                    DetailField("bytes", FieldHints.hex(payload.bytes)),
                ),
            )
        }
    }
}
```

- [ ] **Шаг 5: прогнать тесты**

Ожидание: тринадцать тестов `FrameDecoderTest` проходят. Если падает
`в схеме не появилось нового варианта кадра` — протобуфы обновились: дополнить
`allVariants` новым вариантом и убедиться, что он отображается.

- [ ] **Шаг 6: коммит**

```bash
git add app/src/main/kotlin/com/cerocoder/meshtest/frame/ app/src/test/kotlin/com/cerocoder/meshtest/frame/
git commit -m "feat: сборка детального вида кадра"
```

---

## Задача 5: лента хранит записи

**Файлы:**
- Изменить: `app/src/main/kotlin/com/cerocoder/meshtest/connection/RadioConnectionManager.kt`
- Тест: `app/src/test/kotlin/com/cerocoder/meshtest/connection/RadioConnectionManagerTest.kt`

**Интерфейсы:**
- Потребляет: `FrameRecord` (задача 4).
- Отдаёт: `RadioConnectionManager.packetLog: StateFlow<List<FrameRecord>>`.

Поведение ленты не меняется: она по-прежнему очищается при подключении и режется
до 500. Меняется только то, что в ней лежит.

Отдельного счётчика принятого не заводится: номер последней записи и есть число
принятых за подключение, потому что `seq` растёт на каждом кадре и обнуляется
вместе с лентой. Лишнее состояние, которое можно рассинхронизировать, не
появляется.

- [ ] **Шаг 1: написать падающий тест**

Дописать в существующий `RadioConnectionManagerTest.kt`. Обвязка в нём уже
есть, новой заводить не нужно:

- `RadioConnectionManager(SilentFactory(), scope())` — `SilentFactory` объявлен
  в том же файле и отдаёт транспорт, который подключается и молчит. Это здесь
  и нужно: `TestFactory` подставляет `FakeRadioTransport`, а тот сам сыплет
  кадры сценария, и лента перестала бы быть чистой.
- `SilentFactory` игнорирует адрес, поэтому годится любой; `TestFactory`
  потребовал бы настоящий идентификатор сценария.
- `manager.connect(address)` — публичный вход один, с одним параметром.
- После `connect` вызывать `runCurrent()`, а **не** `advanceUntilIdle()`. В
  этом файле `advanceUntilIdle()` сам по себе безопасен — `scope()` построен
  поверх `backgroundScope`, и почему это так, разобрано в комментарии над ним.
  Но здесь он не нужен и вреден: handshake с молчащим транспортом не
  завершается, и прокрутка времени вперёд выстрелит сторожевым таймером,
  переведёт состояние в `Disconnected` и запустит восстановление — всё это к
  проверяемому отношения не имеет и только сделает тест хрупким. `runCurrent()`
  доводит `connect` до конца, не двигая часов.

```kotlin
    @Test
    fun `лента хранит запись с размером и источником`() = runTest {
        val manager = RadioConnectionManager(SilentFactory(), scope())
        manager.connect("m:тест")
        runCurrent()
        val bytes = FromRadio(my_info = MyNodeInfo(my_node_num = 7)).encode()

        manager.onDataReceived(bytes)

        val record = manager.packetLog.value.single()
        assertEquals(1L, record.seq)
        assertEquals(bytes.size, record.sizeBytes)
        assertEquals("m:тест", record.sourceAddress)
        assertEquals(7, record.frame.my_info?.my_node_num)
    }

    @Test
    fun `время приёма берётся из часов, переданных менеджеру`() = runTest {
        val manager = RadioConnectionManager(
            SilentFactory(),
            scope(),
            now = { 1_700_000_009_000 },
        )
        manager.connect("m:тест")
        runCurrent()

        manager.onDataReceived(FromRadio(id = 1).encode())

        assertEquals(1_700_000_009_000, manager.packetLog.value.single().receivedAtMillis)
    }

    @Test
    fun `номер записи растёт и после того, как лента упёрлась в потолок`() = runTest {
        // Размер ленты насыщается на 500, а число принятых — нет. Именно из
        // номера последней записи экран берёт «принято за подключение»: сам
        // размер после насыщения перестал бы расти, и человек решил бы, что
        // приём встал.
        val manager = RadioConnectionManager(SilentFactory(), scope())
        manager.connect("m:тест")
        runCurrent()

        repeat(600) { manager.onDataReceived(FromRadio(id = it + 1).encode()) }

        assertEquals(500, manager.packetLog.value.size)
        assertEquals(600L, manager.packetLog.value.last().seq)
    }

    @Test
    fun `подключение очищает ленту и обнуляет номер`() = runTest {
        val manager = RadioConnectionManager(SilentFactory(), scope())
        manager.connect("m:первый")
        runCurrent()
        manager.onDataReceived(FromRadio(id = 1).encode())

        manager.connect("m:второй")
        runCurrent()

        assertTrue(manager.packetLog.value.isEmpty())

        manager.onDataReceived(FromRadio(id = 2).encode())
        assertEquals(1L, manager.packetLog.value.single().seq)
    }

    @Test
    fun `открытая запись не меняется после вытеснения`() = runTest {
        // Так проверяется требование «снимок»: экран держит запись, а не
        // позицию в списке. Держал бы позицию — после вытеснения тот же индекс
        // указал бы на другой кадр, и открытый вид молча подменился бы.
        val manager = RadioConnectionManager(SilentFactory(), scope())
        manager.connect("m:тест")
        runCurrent()
        manager.onDataReceived(FromRadio(my_info = MyNodeInfo(my_node_num = 7)).encode())
        val opened = manager.packetLog.value.single()

        repeat(600) { manager.onDataReceived(FromRadio(id = it + 1).encode()) }

        assertEquals(1L, opened.seq)
        assertEquals(7, opened.frame.my_info?.my_node_num)
        assertTrue(manager.packetLog.value.none { it.seq == 1L })
    }

    @Test
    fun `открытая запись не меняется после очистки ленты`() = runTest {
        val manager = RadioConnectionManager(SilentFactory(), scope())
        manager.connect("m:первый")
        runCurrent()
        manager.onDataReceived(FromRadio(my_info = MyNodeInfo(my_node_num = 7)).encode())
        val opened = manager.packetLog.value.single()

        manager.connect("m:второй")
        runCurrent()

        assertTrue(manager.packetLog.value.isEmpty())
        assertEquals(7, opened.frame.my_info?.my_node_num)
        assertEquals("m:первый", opened.sourceAddress)
    }
```

Добавить импорт `org.meshtastic.proto.MyNodeInfo` — остальное в файле уже есть.

- [ ] **Шаг 2: убедиться, что тест падает**

Ожидание: несоответствие типов — `packetLog` отдаёт `List<FromRadio>`, а тест
ждёт записи с полем `seq`.

- [ ] **Шаг 3: изменить менеджер**

В `RadioConnectionManager.kt`:

1. Импорты: добавить `com.cerocoder.meshtest.frame.FrameRecord` и
   `java.util.concurrent.atomic.AtomicLong`.

2. Заменить объявление ленты:

```kotlin
    private val _packetLog = MutableStateFlow<List<FrameRecord>>(emptyList())
    val packetLog: StateFlow<List<FrameRecord>> = _packetLog.asStateFlow()

    /**
     * Номер кадра внутри подключения.
     *
     * Atomic, а не обычное поле: увеличивается с потока транспорта, а
     * обнуляется под замком с другого. Сквозным его делать нельзя — лента
     * очищается при подключении, и номер `#1043` в пустой ленте читался бы
     * как сбой.
     */
    private val frameSeq = AtomicLong(0)
```

3. В `connect`, рядом с `_packetLog.value = emptyList()`, добавить:

```kotlin
                frameSeq.set(0)
```

4. В `onDataReceived`, заменить последнюю строку добавления в ленту:

```kotlin
        // Запись строится ДО update, а не внутри его лямбды. Лямбда update
        // перевыполняется целиком при неудачном compareAndSet, то есть обязана
        // быть чистой. Побочные эффекты внутри неё тратили бы лишний номер и
        // перечитывали часы: номер последней записи объявлен числом принятых
        // за подключение, и разрывы в нумерации сделали бы это утверждение
        // ложным.
        val record = FrameRecord(
            seq = frameSeq.incrementAndGet(),
            receivedAtMillis = now(),
            sourceAddress = currentAddress.orEmpty(),
            sizeBytes = bytes.size,
            frame = frame,
        )
        _packetLog.update { log -> (log + record).takeLast(PACKET_LOG_LIMIT) }
```

Канал `_packets` оставить как есть: он несёт `FromRadio` в handshake, и
подмешивать в него запись значило бы расширять шов без нужды.

- [ ] **Шаг 4: прогнать тесты**

Ожидание: шесть новых тестов проходят, старые тесты менеджера — тоже.
Компиляция `MainActivity`/`PacketLogScreen` на этом шаге сломается: их правит
задача 6. Чтобы задача осталась самостоятельной, временно привести вызов в
`MainActivity` к новому типу минимальной правкой — заменить
`PacketLogScreen(packets = packets, …)` на
`PacketLogScreen(packets = packets.map { it.frame }, …)`. Задача 6 эту
времянку снимает.

- [ ] **Шаг 5: коммит**

```bash
git add app/src/main/kotlin/com/cerocoder/meshtest/ app/src/test/kotlin/com/cerocoder/meshtest/connection/
git commit -m "feat: лента хранит записи с временем, размером и источником"
```

---

## Задача 6: строка ленты и нажатие

**Файлы:**
- Создать: `app/src/main/kotlin/com/cerocoder/meshtest/frame/FrameSummary.kt`
- Изменить: `app/src/main/kotlin/com/cerocoder/meshtest/ui/PacketLogScreen.kt`
- Изменить: `app/src/main/kotlin/com/cerocoder/meshtest/MainActivity.kt` (снять
  времянку из задачи 5)
- Удалить: `app/src/main/kotlin/com/cerocoder/meshtest/ui/PacketFormatter.kt`
- Удалить: `app/src/test/kotlin/com/cerocoder/meshtest/ui/PacketFormatterTest.kt`
- Тест: `app/src/test/kotlin/com/cerocoder/meshtest/frame/FrameSummaryTest.kt`

**Интерфейсы:**
- Потребляет: `FrameRecord` (задача 4), `FieldHints.nodeId` (задача 2).
- Отдаёт:
  - `frameSummary(record: FrameRecord, zone: ZoneId = ZoneId.systemDefault()): String`
  - `PacketLogScreen(packets: List<FrameRecord>, onSelect: (FrameRecord) -> Unit, onBack: () -> Unit)`

- [ ] **Шаг 1: написать падающий тест**

`app/src/test/kotlin/com/cerocoder/meshtest/frame/FrameSummaryTest.kt`:

```kotlin
package com.cerocoder.meshtest.frame

import java.time.ZoneId
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.User

class FrameSummaryTest {

    private val utc = ZoneId.of("UTC")

    private fun record(frame: FromRadio) = FrameRecord(
        seq = 1042,
        receivedAtMillis = 1_700_000_009_000,
        sourceAddress = "x00:11",
        sizeBytes = frame.encode().size,
        frame = frame,
    )

    @Test
    fun `строка пакета содержит номер, время, адреса и порт`() {
        val frame = FromRadio(
            packet = MeshPacket(
                from = 0xA1B2C3D4.toInt(),
                to = 0xFFFFFFFF.toInt(),
                decoded = Data(portnum = PortNum.TELEMETRY_APP),
            ),
        )

        val text = frameSummary(record(frame), utc)

        assertTrue(text.contains("#1042"))
        assertTrue(text.contains("22:13:29"))
        assertTrue(text.contains("!a1b2c3d4"))
        assertTrue(text.contains("!ffffffff"))
        assertTrue(text.contains("TELEMETRY_APP"))
    }

    @Test
    fun `зашифрованный пакет назван зашифрованным, а не пустым`() {
        val frame = FromRadio(packet = MeshPacket(from = 7, encrypted = "ab".encodeUtf8()))

        val text = frameSummary(record(frame), utc)

        assertTrue(text.contains("encrypted"))
    }

    @Test
    fun `сведения о ноде показывают её имя`() {
        val frame = FromRadio(node_info = NodeInfo(num = 7, user = User(short_name = "Дом")))

        val text = frameSummary(record(frame), utc)

        assertTrue(text.contains("node_info"))
        assertTrue(text.contains("Дом"))
    }

    @Test
    fun `канал показывает номер и имя`() {
        val frame = FromRadio(channel = Channel(index = 1, settings = ChannelSettings(name = "LongFast")))

        val text = frameSummary(record(frame), utc)

        assertTrue(text.contains("channel"))
        assertTrue(text.contains("LongFast"))
    }

    @Test
    fun `локальная нода показана 16-рично`() {
        val frame = FromRadio(my_info = MyNodeInfo(my_node_num = 0xA1B2C3D4.toInt()))

        val text = frameSummary(record(frame), utc)

        assertTrue(text.contains("!a1b2c3d4"))
    }

    @Test
    fun `неизвестный вариант не даёт пустой строки`() {
        assertTrue(frameSummary(record(FromRadio(id = 42)), utc).isNotBlank())
    }
}
```

- [ ] **Шаг 2: убедиться, что тест падает**

Ожидание: `Unresolved reference 'frameSummary'`.

- [ ] **Шаг 3: написать строку ленты**

`app/src/main/kotlin/com/cerocoder/meshtest/frame/FrameSummary.kt`:

```kotlin
package com.cerocoder.meshtest.frame

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.meshtastic.proto.FromRadio

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * Одна строка ленты.
 *
 * Время здесь — часы телефона: они есть у каждого кадра, а часы ноды несут
 * только три варианта из восемнадцати, и лента с дырами в колонке времени
 * читалась бы хуже. Часы ноды показываются в детальном виде, где им есть где
 * быть подписанными.
 */
fun frameSummary(record: FrameRecord, zone: ZoneId = ZoneId.systemDefault()): String {
    val clock = CLOCK.format(Instant.ofEpochMilli(record.receivedAtMillis).atZone(zone))
    return "#${record.seq}  $clock  ${describe(record.frame)}"
}

private fun describe(frame: FromRadio): String {
    frame.packet?.let { packet ->
        val what = packet.decoded?.portnum?.toString()
            ?: if (packet.encrypted != null) "encrypted" else "empty"
        return "packet  ${FieldHints.nodeId(packet.from)} → ${FieldHints.nodeId(packet.to)}  $what"
    }
    frame.my_info?.let { return "my_info  ${FieldHints.nodeId(it.my_node_num)}" }
    frame.node_info?.let {
        return "node_info  ${FieldHints.nodeId(it.num)}  ${it.user?.short_name.orEmpty()}".trimEnd()
    }
    frame.metadata?.let { return "metadata  ${it.firmware_version}" }
    frame.channel?.let { return "channel[${it.index}]  ${it.settings?.name.orEmpty()}".trimEnd() }
    frame.config?.let { return "config" }
    frame.moduleConfig?.let { return "moduleConfig" }
    frame.queueStatus?.let { return "queueStatus  free=${it.free}" }
    frame.config_complete_id?.let { return "config_complete_id  $it" }
    frame.log_record?.let { return "log_record  ${it.message}" }
    frame.rebooted?.let { return "rebooted  $it" }
    frame.xmodemPacket?.let { return "xmodemPacket" }
    frame.mqttClientProxyMessage?.let { return "mqttClientProxyMessage" }
    frame.fileInfo?.let { return "fileInfo  ${it.file_name}" }
    frame.clientNotification?.let { return "clientNotification" }
    frame.deviceuiConfig?.let { return "deviceuiConfig" }
    frame.lockdown_status?.let { return "lockdown_status" }
    frame.region_presets?.let { return "region_presets" }
    return "id  ${frame.id}"
}
```

Ветвление через `?.let { return … }`, а не через `when`: поля приходят из
отдельного модуля сгенерированных Wire-моделей, и Kotlin не делает для них
умного приведения типа после проверки на null. Так же сделано в удаляемом
`PacketFormatter.kt`.

- [ ] **Шаг 4: переписать экран ленты**

`app/src/main/kotlin/com/cerocoder/meshtest/ui/PacketLogScreen.kt` — заменить
сигнатуру и тело списка, автопрокрутку оставить как есть:

```kotlin
@Composable
fun PacketLogScreen(
    packets: List<FrameRecord>,
    onSelect: (FrameRecord) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    // …существующая автопрокрутка без изменений…

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Button(onClick = onBack) {
            Text("К списку устройств")
        }

        // Два числа, а не одно: размер ленты насыщается на 500, и после
        // насыщения перестаёт показывать, идёт ли приём вообще.
        Text(
            "Принято за подключение: ${packets.lastOrNull()?.seq ?: 0}   в ленте: ${packets.size}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )

        LazyColumn(state = listState, modifier = Modifier.padding(top = 8.dp)) {
            items(packets, key = { it.seq }) { record ->
                Text(
                    frameSummary(record),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(record) }
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}
```

Добавить импорты: `androidx.activity.compose.BackHandler`,
`androidx.compose.foundation.clickable`,
`com.cerocoder.meshtest.frame.FrameRecord`,
`com.cerocoder.meshtest.frame.frameSummary`. Убрать импорт
`org.meshtastic.proto.FromRadio`.

Отступ по вертикали увеличен с 4 до 8 dp: строка стала целью нажатия, и по
рекомендациям Material минимальная высота такой цели — 48 dp.

- [ ] **Шаг 5: снять времянку в MainActivity и удалить старый форматтер**

В `MainActivity.kt` вернуть прямую передачу и добавить обработчик выбора —
временное состояние на один шаг, задача 7 заменит его на сохраняемое:

```kotlin
                        PacketLogScreen(
                            packets = packets,
                            onSelect = { },
                            onBack = { showLog = false },
                        )
```

```bash
git rm app/src/main/kotlin/com/cerocoder/meshtest/ui/PacketFormatter.kt
git rm app/src/test/kotlin/com/cerocoder/meshtest/ui/PacketFormatterTest.kt
```

- [ ] **Шаг 6: прогнать тесты**

Ожидание: шесть тестов `FrameSummaryTest` проходят, весь набор зелёный,
`PacketFormatterTest` исчез.

- [ ] **Шаг 7: коммит**

```bash
git add -A app/src/main/kotlin/com/cerocoder/meshtest app/src/test/kotlin/com/cerocoder/meshtest
git commit -m "feat: строка ленты с 16-ричными адресами, лента нажимается"
```

---

## Задача 7: экран кадра

**Файлы:**
- Создать: `app/src/main/kotlin/com/cerocoder/meshtest/ui/FrameDetailScreen.kt`
- Изменить: `app/src/main/kotlin/com/cerocoder/meshtest/MainActivity.kt`

**Интерфейсы:**
- Потребляет: `FrameRecord`, `frameRecordToList`, `frameRecordFromList`,
  `FrameDecoder`, `FrameDetail`, `Section`, `DetailField`.
- Отдаёт: `FrameDetailScreen(record: FrameRecord, onBack: () -> Unit)`.

Тестов Compose в проекте нет и в этом этапе не заводится: формат снимка уже
проверен в задаче 4, остальное — вёрстка, и проверяется ручной приёмкой.

- [ ] **Шаг 1: написать экран**

`app/src/main/kotlin/com/cerocoder/meshtest/ui/FrameDetailScreen.kt`:

```kotlin
package com.cerocoder.meshtest.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cerocoder.meshtest.frame.DetailField
import com.cerocoder.meshtest.frame.FrameDecoder
import com.cerocoder.meshtest.frame.FrameRecord

/**
 * Содержимое одного кадра.
 *
 * Экран отдельный, а не окно поверх ленты: у `MeshPacket` двадцать два
 * собственных поля плюс разобранная нагрузка плюс дамп байтов — в окне это
 * пришлось бы резать. Побочно это выполняет требование «одновременно открыт
 * только один кадр»: ленты на экране нет, нажимать не по чему.
 */
@Composable
fun FrameDetailScreen(record: FrameRecord, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    var showDefaults by remember { mutableStateOf(false) }
    val decoder = remember { FrameDecoder() }
    val detail = remember(record, showDefaults) { decoder.decode(record, showDefaults) }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Button(onClick = onBack) {
            Text("К ленте кадров")
        }

        Text(
            detail.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 8.dp),
        )

        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Switch(checked = showDefaults, onCheckedChange = { showDefaults = it })
            Text(
                "Показывать поля со значением по умолчанию",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
            items(detail.common) { field -> FieldRow(field) }

            for (section in detail.sections) {
                item(key = section.title) {
                    Text(
                        section.title,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                items(section.fields) { field -> FieldRow(field) }
            }
        }
    }
}

@Composable
private fun FieldRow(field: DetailField) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            field.name,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            field.value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.58f),
        )
    }
}
```

- [ ] **Шаг 2: связать в MainActivity**

Добавить импорты: `androidx.compose.runtime.saveable.listSaver`,
`com.cerocoder.meshtest.frame.FrameRecord`,
`com.cerocoder.meshtest.frame.frameRecordFromList`,
`com.cerocoder.meshtest.frame.frameRecordToList`,
`com.cerocoder.meshtest.ui.FrameDetailScreen`.

Рядом с `showLog` добавить состояние выбора:

```kotlin
                    // Выбор хранится записью, а не позицией в ленте: позиция
                    // после вытеснения указала бы на другой кадр, а после
                    // очистки ленты — ни на что.
                    //
                    // rememberSaveable, потому что ориентация в манифесте не
                    // зафиксирована: без него поворот телефона закрывал бы
                    // открытый кадр.
                    var selected by rememberSaveable(stateSaver = FrameRecordSaver) {
                        mutableStateOf<FrameRecord?>(null)
                    }
```

Ниже, в `MainActivity.kt`, вне класса:

```kotlin
private val FrameRecordSaver = listSaver<FrameRecord?, Any>(
    save = { record -> if (record == null) emptyList() else frameRecordToList(record) },
    restore = { saved -> frameRecordFromList(saved) },
)
```

Заменить ветвление экранов:

```kotlin
                    val opened = selected
                    if (opened != null) {
                        FrameDetailScreen(record = opened, onBack = { selected = null })
                    } else if (showLog) {
                        PacketLogScreen(
                            packets = packets,
                            onSelect = { selected = it },
                            onBack = { showLog = false },
                        )
                    } else {
                        DeviceListScreen(
                            // …без изменений…
                        )
                    }
```

Проверка на `opened` идёт первой: пока кадр открыт, лента не рисуется, и второй
кадр открыть неоткуда.

- [ ] **Шаг 3: прогнать сборку и тесты**

Ожидание: весь набор зелёный, `assembleDebug` и `assembleRelease` проходят.

- [ ] **Шаг 4: коммит**

```bash
git add app/src/main/kotlin/com/cerocoder/meshtest/
git commit -m "feat: экран кадра с разбором по схеме"
```

---

## Ручная приёмка

Тестов Compose в проекте нет, а на прошлом этапе шесть дефектов из шести нашла
именно ручная приёмка, а не CI и не три ревью. Поэтому она обязательна.
Выполняется человеком на настоящей ноде, сборка ставится через adb.

- [ ] 1. Открыть ленту при подключённой ноде. Строки содержат номер `#N`, время,
      тип кадра, а у пакетов — адреса вида `!a1b2c3d4`. Десятичных номеров нод
      на экране нет.
- [ ] 2. Счётчик показывает два числа. Дождаться, пока «в ленте» дойдёт до 500
      и остановится, и убедиться, что «принято за подключение» продолжает расти.
- [ ] 3. Щёлкнуть по строке `MeshPacket`. Открывается экран кадра. В общей части
      есть номер, размер, часы телефона, источник; если у кадра было `rx_time` —
      строка часов ноды с указанием, из какого поля взято.
- [ ] 4. На том же экране включить «показывать поля со значением по умолчанию».
      Полей становится заметно больше. Выключить — возвращается прежний вид.
- [ ] 5. Нажать аппаратную «Назад». Возврат в ленту, а не выход из приложения.
      Нажать её ещё раз — возврат к списку устройств.
- [ ] 6. Открыть кадр и повернуть телефон. Кадр остаётся открытым и показывает
      те же данные.
- [ ] 7. Открыть кадр с малым номером и оставить экран открытым, пока лента не
      наберёт 500 новых кадров. Открытый кадр не изменился.
- [ ] 8. Открыть кадр, затем разорвать связь с нодой (унести телефон или
      выключить ноду) и дождаться переподключения. Открытый кадр цел, строка
      «Источник» по-прежнему называет исходное устройство.
- [ ] 9. Найти в ленте кадры разных типов и открыть по одному каждого: `config`,
      `channel`, `node_info`, `my_info`, `metadata`. Ни один не показывает
      пустой экран и ни один не роняет приложение.
- [ ] 10. Если в ленте попался кадр со строкой `unknown_fields` — записать это:
      значит прошивка ноды новее протобуфов приложения, и это стоит отдельного
      разговора.
- [ ] 11. Поставить release-сборку. Экран кадра показывает имена полей
      (`battery_level`, `rx_snr`), а не однобуквенные. Если однобуквенные —
      включилась обфускация, нужны keep-правила из спеки §13.

## Что план не делает

- Дискового хранения и истории между запусками.
- Поиска и фильтрации по ленте.
- Расшифровки `MeshPacket` по ключам канала.
- Распаковки Unishox2 и zstd.
- Отправки кадров.
