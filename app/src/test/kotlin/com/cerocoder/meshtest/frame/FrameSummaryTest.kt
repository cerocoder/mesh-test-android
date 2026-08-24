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
