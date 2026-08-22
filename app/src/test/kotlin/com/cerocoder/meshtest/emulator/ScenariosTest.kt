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

    @Test
    fun `кадры стадии 1 идут в порядке настоящей прошивки`() {
        val frames = scenario.configStageFrames(MeshProtocol.CONFIG_NONCE)

        val myInfo = frames.indexOfFirst { it.my_info != null }
        val metadata = frames.indexOfFirst { it.metadata != null }
        val firstConfig = frames.indexOfFirst { it.config != null }
        val firstModuleConfig = frames.indexOfFirst { it.moduleConfig != null }
        val firstChannel = frames.indexOfFirst { it.channel != null }

        assertTrue("MyNodeInfo должен быть раньше метаданных", myInfo < metadata)
        assertTrue("метаданные раньше конфигурации", metadata < firstConfig)
        assertTrue("конфигурация раньше конфигурации модулей", firstConfig < firstModuleConfig)
        assertTrue("конфигурация модулей раньше каналов", firstModuleConfig < firstChannel)
        assertTrue("подтверждение замыкает стадию", firstChannel < frames.lastIndex)
    }
}
