package com.cerocoder.meshtest.frame

import com.squareup.wire.WireField
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

    @Test
    fun `курс печатается градусами, а не сотыми долями`() {
        // По схеме ground_track хранится в 1/100 градуса: 18000 — это 180.00°.
        // Подпись "deg" без деления давала бы «18000 deg».
        assertEquals("180.00 deg", hints.format("Position", "ground_track", 18_000))
    }

    @Test
    fun `в таблице нет записей о несуществующих полях`() {
        val missing = mutableListOf<String>()
        for (key in FieldHints().hintKeys) {
            val message = key.substringBefore('.')
            val field = key.substringAfter('.')
            val type = runCatching { Class.forName("org.meshtastic.proto.$message") }.getOrNull()
            if (type == null) {
                missing += "$key: нет такого сообщения"
                continue
            }
            val exists = type.declaredFields.any { declared ->
                val wire = declared.getAnnotation(WireField::class.java)
                wire != null && wire.declaredName.ifEmpty { declared.name } == field
            }
            if (!exists) missing += "$key: нет такого поля"
        }

        assertEquals(emptyList<String>(), missing)
    }
}
