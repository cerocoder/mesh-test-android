package com.cerocoder.meshtest.ui

import org.meshtastic.proto.Config
import org.meshtastic.proto.FromRadio

/**
 * Краткое человекочитаемое описание кадра для диагностической ленты.
 *
 * Ветвление сделано через `?.let { return … }`, а не через `when (frame.x != null)`:
 * поля приходят из отдельного модуля — сгенерированных Wire-моделей, — и Kotlin не
 * выполняет для них умное приведение типа после проверки на null.
 */
fun formatPacket(frame: FromRadio): String {
    frame.my_info?.let { return "MyNodeInfo: узел ${it.my_node_num}, нод в базе ${it.nodedb_count}" }
    frame.metadata?.let { return "Metadata: прошивка ${it.firmware_version}" }
    frame.config?.let { return "Config: ${configKind(it)}" }
    if (frame.moduleConfig != null) return "ModuleConfig"
    frame.channel?.let { return "Channel[${it.index}] ${it.settings?.name.orEmpty()}" }
    frame.node_info?.let { return "NodeInfo: ${it.user?.short_name.orEmpty()} (${it.num})" }
    frame.config_complete_id?.let { return "ConfigComplete: нонс $it" }
    frame.queueStatus?.let { return "QueueStatus: свободно ${it.free}" }
    frame.packet?.let { return "MeshPacket от ${it.from}" }
    return "Кадр #${frame.id}"
}

private fun configKind(config: Config): String = when {
    config.device != null -> "device"
    config.position != null -> "position"
    config.power != null -> "power"
    config.network != null -> "network"
    config.display != null -> "display"
    config.lora != null -> "lora"
    config.bluetooth != null -> "bluetooth"
    else -> "прочее"
}
