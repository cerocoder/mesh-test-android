package com.cerocoder.meshtest.frame

import com.squareup.wire.WireEnum
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import okio.ByteString

/**
 * Как показать значение поля.
 *
 * Обходчик знает имя и тип, но не знает смысла: что `latitude_i` — это градусы
 * ×1e-7, что `rx_time` — секунды эпохи, а `from` — номер ноды, который принято
 * писать 16-рично. Таблица добавляет ровно это знание — порядка восьмидесяти
 * записей вместо шестисот ручных подписей.
 *
 * Часовой пояс — параметр, а не `systemDefault()` внутри: иначе тест на
 * форматирование даты зависел бы от машины, на которой запущен.
 */
class FieldHints(private val zone: ZoneId = ZoneId.systemDefault()) {

    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun format(message: String, field: String, value: Any): String {
        // Тип важнее таблицы: байты и перечисления показываются одинаково
        // всюду, где встретятся, и заводить на них записи бессмысленно.
        if (value is ByteString) return hex(value)
        if (value is WireEnum) return value.toString()
        if (value is Collection<*>) {
            return value.joinToString(", ") { item ->
                if (item == null) "null" else format(message, field, item)
            }
        }

        val hint = hints["$message.$field"]
        val text = when (hint?.render ?: Render.PLAIN) {
            Render.NODE_ID -> nodeId(value as Int)
            Render.NODE_ID_LAST_BYTE -> "0x%02x".format(value as Int)
            Render.EPOCH_SECONDS -> epoch(value as Int)
            Render.SCALED_1E7 -> "%.7f".format((value as Int) / 1e7)
            Render.SCALED_1E2 -> "%.2f".format((value as Int) / 100.0)
            Render.PLAIN -> value.toString()
        }
        return if (hint?.unit == null) text else "$text ${hint.unit}"
    }

    private fun epoch(seconds: Int): String =
        // Ноль здесь означает «нода времени не знает»: у неё может не быть ни
        // GPS, ни связи с сетью. Показать 1970 год — сбить с толку.
        if (seconds == 0) {
            "0 (не задано)"
        } else {
            time.format(Instant.ofEpochSecond(seconds.toLong()).atZone(zone))
        }

    private enum class Render { PLAIN, NODE_ID, NODE_ID_LAST_BYTE, EPOCH_SECONDS, SCALED_1E7, SCALED_1E2 }

    private data class Hint(val render: Render = Render.PLAIN, val unit: String? = null)

    private val hints: Map<String, Hint> = mapOf(
        // Номера нод.
        "MeshPacket.from" to Hint(Render.NODE_ID),
        "MeshPacket.to" to Hint(Render.NODE_ID),
        "Data.dest" to Hint(Render.NODE_ID),
        "Data.source" to Hint(Render.NODE_ID),
        "MyNodeInfo.my_node_num" to Hint(Render.NODE_ID),
        "NodeInfo.num" to Hint(Render.NODE_ID),
        "NodeInfoLite.num" to Hint(Render.NODE_ID),
        "NeighborInfo.node_id" to Hint(Render.NODE_ID),
        "NeighborInfo.last_sent_by_id" to Hint(Render.NODE_ID),
        "Neighbor.node_id" to Hint(Render.NODE_ID),
        "RouteDiscovery.route" to Hint(Render.NODE_ID),
        "RouteDiscovery.route_back" to Hint(Render.NODE_ID),
        "StoreForwardPlusPlus.encapsulated_to" to Hint(Render.NODE_ID),
        "StoreForwardPlusPlus.encapsulated_from" to Hint(Render.NODE_ID),

        // Последний байт номера, а не номер.
        "MeshPacket.next_hop" to Hint(Render.NODE_ID_LAST_BYTE),
        "MeshPacket.relay_node" to Hint(Render.NODE_ID_LAST_BYTE),

        // Секунды эпохи.
        "MeshPacket.rx_time" to Hint(Render.EPOCH_SECONDS),
        "NodeInfo.last_heard" to Hint(Render.EPOCH_SECONDS),
        "NodeInfoLite.last_heard" to Hint(Render.EPOCH_SECONDS),
        "Position.time" to Hint(Render.EPOCH_SECONDS),
        "Position.timestamp" to Hint(Render.EPOCH_SECONDS),
        "Telemetry.time" to Hint(Render.EPOCH_SECONDS),
        "LogRecord.time" to Hint(Render.EPOCH_SECONDS),
        "Waypoint.expire" to Hint(Render.EPOCH_SECONDS),
        "StoreForwardPlusPlus.encapsulated_rxtime" to Hint(Render.EPOCH_SECONDS),

        // Градусы ×1e-7.
        "Position.latitude_i" to Hint(Render.SCALED_1E7),
        "Position.longitude_i" to Hint(Render.SCALED_1E7),
        "PositionLite.latitude_i" to Hint(Render.SCALED_1E7),
        "PositionLite.longitude_i" to Hint(Render.SCALED_1E7),
        "MapReport.latitude_i" to Hint(Render.SCALED_1E7),
        "MapReport.longitude_i" to Hint(Render.SCALED_1E7),
        "PLI.latitude_i" to Hint(Render.SCALED_1E7),
        "PLI.longitude_i" to Hint(Render.SCALED_1E7),
        "TAKPacketV2.latitude_i" to Hint(Render.SCALED_1E7),
        "TAKPacketV2.longitude_i" to Hint(Render.SCALED_1E7),
        "Waypoint.latitude_i" to Hint(Render.SCALED_1E7),
        "Waypoint.longitude_i" to Hint(Render.SCALED_1E7),

        // Единицы измерения из комментариев схемы.
        "MeshPacket.rx_snr" to Hint(unit = "dB"),
        "MeshPacket.rx_rssi" to Hint(unit = "dBm"),
        "NodeInfo.snr" to Hint(unit = "dB"),
        "Neighbor.snr" to Hint(unit = "dB"),
        "DeviceMetrics.battery_level" to Hint(unit = "%"),
        "DeviceMetrics.voltage" to Hint(unit = "V"),
        "DeviceMetrics.channel_utilization" to Hint(unit = "%"),
        "DeviceMetrics.air_util_tx" to Hint(unit = "%"),
        "DeviceMetrics.uptime_seconds" to Hint(unit = "s"),
        "Position.altitude" to Hint(unit = "m"),
        "Position.altitude_hae" to Hint(unit = "m"),
        "Position.altitude_geoidal_separation" to Hint(unit = "m"),
        "Position.ground_speed" to Hint(unit = "m/s"),
        "Position.ground_track" to Hint(Render.SCALED_1E2, unit = "deg"),
        "EnvironmentMetrics.temperature" to Hint(unit = "°C"),
        "EnvironmentMetrics.relative_humidity" to Hint(unit = "%"),
        "EnvironmentMetrics.barometric_pressure" to Hint(unit = "hPa"),
        "EnvironmentMetrics.voltage" to Hint(unit = "V"),
        "EnvironmentMetrics.wind_speed" to Hint(unit = "m/s"),
        "EnvironmentMetrics.wind_direction" to Hint(unit = "deg"),
        "PowerMetrics.ch1_voltage" to Hint(unit = "V"),
        "PowerMetrics.ch2_voltage" to Hint(unit = "V"),
        "PowerMetrics.ch3_voltage" to Hint(unit = "V"),
        "LocalStats.uptime_seconds" to Hint(unit = "s"),
        "LocalStats.channel_utilization" to Hint(unit = "%"),
        "LocalStats.air_util_tx" to Hint(unit = "%"),
        "HostMetrics.uptime_seconds" to Hint(unit = "s"),
        "Paxcount.uptime" to Hint(unit = "s"),
    )

    /** Ключи таблицы — для теста, который сверяет её со схемой. */
    internal val hintKeys: Set<String> get() = hints.keys

    companion object {

        /**
         * Номер ноды в принятом у Meshtastic виде: `!` и восемь строчных
         * 16-ричных цифр. Десятичный номер в протоколе нигде не встречается —
         * ни в интерфейсе ноды, ни в документации, — поэтому показывать его
         * десятичным значит заставлять человека переводить вручную.
         */
        fun nodeId(num: Int): String {
            val hex = "!%08x".format(num)
            return when (num) {
                0xFFFFFFFF.toInt() -> "$hex (broadcast)"
                0 -> "$hex (не задан)"
                else -> hex
            }
        }

        fun hex(bytes: ByteString): String = "${bytes.hex()} (${bytes.size} B)"
    }
}
