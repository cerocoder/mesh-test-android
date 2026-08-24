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
        // прошивка новее протобуфов. Экран от этого падать не имеет права, а
        // байты обязаны сохраниться — по ним человек и разбирается.
        val bytes = "ffffffffffffffff".decodeHex()

        val result = PayloadCodecs.read(PortNum.POSITION_APP, bytes)

        assertTrue(result is Payload.Failed)
        assertEquals(bytes, (result as Payload.Failed).bytes)
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
