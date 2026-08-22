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
