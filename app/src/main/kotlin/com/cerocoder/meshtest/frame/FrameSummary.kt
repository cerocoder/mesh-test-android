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

/**
 * Подпись варианта берётся из имени поля `oneof payload_variant` в схеме
 * (`packet`, `node_info`, `my_info`, …), а не переводится: это то же имя, под
 * которым вариант ищут в схеме и в детальном виде, лента не должна вводить
 * второй словарь для того же самого.
 *
 * Ветвление через `?.let { return … }`, а не через `when`: поля приходят из
 * отдельного модуля сгенерированных Wire-моделей, и Kotlin не делает для них
 * умного приведения типа после проверки на null.
 */
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
