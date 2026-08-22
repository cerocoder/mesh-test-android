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
