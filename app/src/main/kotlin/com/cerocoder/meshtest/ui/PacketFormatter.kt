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
