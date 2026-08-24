package com.cerocoder.meshtest.frame

import com.squareup.wire.ProtoAdapter
import okio.ByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Compressed
import org.meshtastic.proto.HardwareMessage
import org.meshtastic.proto.KeyVerification
import org.meshtastic.proto.MapReport
import org.meshtastic.proto.MeshBeacon
import org.meshtastic.proto.NeighborInfo
import org.meshtastic.proto.Paxcount
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.PowerStressMessage
import org.meshtastic.proto.RemoteShell
import org.meshtastic.proto.RouteDiscovery
import org.meshtastic.proto.Routing
import org.meshtastic.proto.StatusMessage
import org.meshtastic.proto.StoreAndForward
import org.meshtastic.proto.StoreForwardPlusPlus
import org.meshtastic.proto.TAKPacket
import org.meshtastic.proto.TAKPacketV2
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.User
import org.meshtastic.proto.Waypoint

/** Что удалось сделать с нагрузкой пакета. */
sealed interface Payload {

    /** Разобрано протобуфом. */
    data class Decoded(val typeName: String, val message: Any) : Payload

    /** По схеме это текст. */
    data class Text(val text: String) : Payload

    /**
     * По схеме это не протобуф. `note` называет объявленный формат, `fields` —
     * то, что удалось выделить из него без полноценного декодера.
     */
    data class Raw(
        val note: String,
        val fields: List<DetailField>,
        val bytes: ByteString,
    ) : Payload

    /** Схема обещала протобуф, но разбор не удался. */
    data class Failed(val reason: String, val bytes: ByteString) : Payload
}

/**
 * Как читать `Data.payload` для каждого portnum.
 *
 * Нагрузка приходит байтами, её тип задан числом. Без этой таблицы детальный
 * вид показывал бы «TELEMETRY_APP, 34 байта» и ничего больше.
 *
 * Не у всех портов протобуфный тип: часть несёт текст, часть — кадры codec2,
 * IP-пакет или Cayenne LPP. Для них честное отображение — назвать формат и
 * показать байты, а не притворяться, что разобрано.
 */
object PayloadCodecs {

    fun read(portnum: PortNum, payload: ByteString): Payload = when (portnum) {
        PortNum.TEXT_MESSAGE_APP,
        PortNum.DETECTION_SENSOR_APP,
        PortNum.ALERT_APP,
        PortNum.REPLY_APP,
        PortNum.RANGE_TEST_APP,
        -> Payload.Text(payload.utf8())

        PortNum.REMOTE_HARDWARE_APP -> decode("HardwareMessage", HardwareMessage.ADAPTER, payload)
        PortNum.POSITION_APP -> decode("Position", Position.ADAPTER, payload)
        PortNum.NODEINFO_APP -> decode("User", User.ADAPTER, payload)
        PortNum.ROUTING_APP -> decode("Routing", Routing.ADAPTER, payload)
        PortNum.ADMIN_APP -> decode("AdminMessage", AdminMessage.ADAPTER, payload)
        PortNum.WAYPOINT_APP -> decode("Waypoint", Waypoint.ADAPTER, payload)
        PortNum.KEY_VERIFICATION_APP -> decode("KeyVerification", KeyVerification.ADAPTER, payload)
        PortNum.REMOTE_SHELL_APP -> decode("RemoteShell", RemoteShell.ADAPTER, payload)
        PortNum.PAXCOUNTER_APP -> decode("Paxcount", Paxcount.ADAPTER, payload)
        PortNum.STORE_FORWARD_PLUSPLUS_APP ->
            decode("StoreForwardPlusPlus", StoreForwardPlusPlus.ADAPTER, payload)
        PortNum.NODE_STATUS_APP -> decode("StatusMessage", StatusMessage.ADAPTER, payload)
        PortNum.MESH_BEACON_APP -> decode("MeshBeacon", MeshBeacon.ADAPTER, payload)
        PortNum.STORE_FORWARD_APP -> decode("StoreAndForward", StoreAndForward.ADAPTER, payload)
        PortNum.TELEMETRY_APP -> decode("Telemetry", Telemetry.ADAPTER, payload)
        PortNum.TRACEROUTE_APP -> decode("RouteDiscovery", RouteDiscovery.ADAPTER, payload)
        PortNum.NEIGHBORINFO_APP -> decode("NeighborInfo", NeighborInfo.ADAPTER, payload)
        PortNum.ATAK_PLUGIN -> decode("TAKPacket", TAKPacket.ADAPTER, payload)
        PortNum.MAP_REPORT_APP -> decode("MapReport", MapReport.ADAPTER, payload)
        PortNum.POWERSTRESS_APP -> decode("PowerStressMessage", PowerStressMessage.ADAPTER, payload)

        // Точное представление в эфире по одной схеме не устанавливается:
        // пробуем объявленный тип, при отказе показываем байты.
        PortNum.TEXT_MESSAGE_COMPRESSED_APP,
        PortNum.SIMULATOR_APP,
        -> attempt("Compressed", Compressed.ADAPTER, payload, "Unishox2-compressed")
        PortNum.ATAK_PLUGIN_V2 ->
            attempt("TAKPacketV2", TAKPacketV2.ADAPTER, payload, "zstd dictionary compressed")

        PortNum.AUDIO_APP -> codec2(payload)
        PortNum.LORAWAN_BRIDGE -> loraWan(payload)

        PortNum.UNKNOWN_APP -> raw("opaque, not understood by the mesh", payload)
        PortNum.IP_TUNNEL_APP -> raw("IP packet", payload)
        PortNum.SERIAL_APP -> raw("serial bytes", payload)
        PortNum.ZPS_APP -> raw("arrays of int64", payload)
        PortNum.RETICULUM_TUNNEL_APP -> raw("fragmented RNS packet", payload)
        PortNum.CAYENNE_APP -> raw("Cayenne LPP", payload)
        PortNum.LORA_OTA_APP -> raw("ota-common transport frames", payload)
        PortNum.GROUPALARM_APP -> raw("GroupAlarm message", payload)
        PortNum.PRIVATE_APP -> raw("private application", payload)
        PortNum.ATAK_FORWARDER -> raw("libcotshrink", payload)
        PortNum.MAX -> raw("port range limit, not a real port", payload)
    }

    private fun <T : Any> decode(name: String, adapter: ProtoAdapter<T>, bytes: ByteString): Payload =
        try {
            Payload.Decoded(name, adapter.decode(bytes))
        } catch (e: Exception) {
            // Ловим Exception, а не IOException: адаптер на мусоре способен
            // бросить и IllegalStateException, и ошибку выхода за границы.
            // Уронить здесь — значит потерять весь кадр из-за одного поля.
            Payload.Failed("$name: ${e.message ?: e.javaClass.simpleName}", bytes)
        }

    private fun <T : Any> attempt(
        name: String,
        adapter: ProtoAdapter<T>,
        bytes: ByteString,
        note: String,
    ): Payload = when (val result = decode(name, adapter, bytes)) {
        is Payload.Failed -> raw(note, bytes)
        else -> result
    }

    private fun codec2(bytes: ByteString): Payload {
        // По схеме кадр начинается с c0 de c2 и байта-маркера битрейта.
        val fields = if (bytes.size >= 4) {
            listOf(
                DetailField("codec2_header", bytes.substring(0, 3).hex()),
                DetailField("bitrate_marker", "0x%02x".format(bytes[3])),
            )
        } else {
            emptyList()
        }
        return Payload.Raw("codec2 audio frames", fields, bytes)
    }

    private fun loraWan(bytes: ByteString): Payload {
        // По схеме: 10 байт метаданных РЧ, дальше PHY-нагрузка.
        val fields = if (bytes.size > 10) {
            listOf(
                DetailField("rf_metadata", bytes.substring(0, 10).hex()),
                DetailField("phy_payload", bytes.substring(10).hex()),
            )
        } else {
            emptyList()
        }
        return Payload.Raw("LoRaWAN uplink", fields, bytes)
    }

    private fun raw(note: String, bytes: ByteString): Payload =
        Payload.Raw(note, emptyList(), bytes)
}
