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
import org.meshtastic.proto.Neighbor
import org.meshtastic.proto.NeighborInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.QueueStatus
import org.meshtastic.proto.Telemetry

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
    fun `повторяющееся поле сообщений даёт секцию на каждый элемент`() {
        val frame = FromRadio(
            packet = MeshPacket(
                from = 7,
                decoded = Data(
                    portnum = PortNum.NEIGHBORINFO_APP,
                    payload = NeighborInfo(
                        node_id = 7,
                        neighbors = listOf(Neighbor(node_id = 11), Neighbor(node_id = 12)),
                    ).encodeByteString(),
                ),
            ),
        )

        val detail = decoder.decode(record(frame))

        val titles = detail.sections.map { it.title }
        assertTrue(titles.any { it.endsWith("neighbors[0]") })
        assertTrue(titles.any { it.endsWith("neighbors[1]") })
    }

    @Test
    fun `кадр без заполненных полей не роняет разбор`() {
        val detail = decoder.decode(record(FromRadio()))

        assertEquals("пустой кадр", detail.title)
        assertTrue(detail.common.isNotEmpty())
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

    @Test
    fun `снимок с неверными типами восстанавливается как отсутствие выбора`() {
        assertEquals(null, frameRecordFromList(listOf(1, 2L, "m:тест", 3, ByteArray(0))))
    }

    @Test
    fun `снимок с испорченными байтами кадра не роняет восстановление`() {
        // 0x0A — заголовок поля без тела: разбор обязан оборваться.
        val saved = listOf(1L, 2L, "m:тест", 3, byteArrayOf(0x0A))

        assertEquals(null, frameRecordFromList(saved))
    }
}
