package com.cerocoder.meshtest.frame

import com.squareup.wire.Message
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio

/**
 * Сборка содержимого кадра для показа.
 *
 * Общая часть одинакова у всех вариантов, специфическая строится обходом полей.
 * Подписи общей части — русские: это строки самого приложения, а не поля
 * протокола. Там, где значение приходит из поля схемы, имя поля названо в
 * скобках, чтобы было видно, откуда взято.
 */
class FrameDecoder(
    private val hints: FieldHints = FieldHints(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun decode(record: FrameRecord, includeDefaults: Boolean = false): FrameDetail {
        val fields = FieldWalker.rawFields(record.frame, includeDefaults)
        // id не входит в oneof и вариантом кадра не является.
        val variant = fields.firstOrNull { it.name != "id" }
        val title = variant?.name ?: "пустой кадр"

        val sections = mutableListOf<Section>()
        when (val value = variant?.value) {
            null -> Unit
            is Message<*, *> -> collect(variant.name, value, includeDefaults, sections)
            else -> sections += Section(
                variant.name,
                listOf(DetailField(variant.name, hints.format("FromRadio", variant.name, value))),
            )
        }

        return FrameDetail(title, common(record, fields), sections)
    }

    private fun common(record: FrameRecord, fields: List<RawField>): List<DetailField> {
        val rows = mutableListOf(
            DetailField("Номер в ленте", "#${record.seq}"),
            DetailField("Размер", "${record.sizeBytes} B"),
        )
        fields.firstOrNull { it.name == "id" }?.let {
            rows += DetailField("Идентификатор кадра (id)", it.value.toString())
        }
        nodeClock(record.frame)?.let { (field, seconds) ->
            rows += DetailField(
                "Часы ноды ($field)",
                time.format(Instant.ofEpochSecond(seconds.toLong()).atZone(zone)),
            )
        }
        rows += DetailField(
            "Часы телефона",
            time.format(Instant.ofEpochMilli(record.receivedAtMillis).atZone(zone)),
        )
        rows += DetailField("Источник", record.sourceAddress)
        return rows
    }

    /**
     * Метка времени по часам ноды, если кадр её несёт.
     *
     * Несут только три варианта из восемнадцати. Подставлять вместо неё часы
     * телефона нельзя: это разные часы, расходящиеся на неизвестную величину,
     * и выдача одних за другие сделала бы ленту непригодной для разбора
     * задержек — того, ради чего время и показывается.
     */
    private fun nodeClock(frame: FromRadio): Pair<String, Int>? {
        frame.packet?.rx_time?.takeIf { it != 0 }?.let { return "rx_time" to it }
        frame.node_info?.last_heard?.takeIf { it != 0 }?.let { return "last_heard" to it }
        frame.log_record?.time?.takeIf { it != 0 }?.let { return "time" to it }
        return null
    }

    private fun collect(
        path: String,
        message: Any,
        includeDefaults: Boolean,
        out: MutableList<Section>,
    ) {
        val plain = mutableListOf<DetailField>()
        val nested = mutableListOf<Pair<String, Any>>()
        val type = message.javaClass.simpleName

        for (field in FieldWalker.rawFields(message, includeDefaults)) {
            val value = field.value
            when {
                value is Message<*, *> -> nested += "$path.${field.name}" to value
                value is List<*> && value.any { it is Message<*, *> } ->
                    value.forEachIndexed { i, item ->
                        if (item != null) nested += "$path.${field.name}[$i]" to item
                    }
                else -> plain += DetailField(field.name, hints.format(type, field.name, value))
            }
        }

        FieldWalker.unknownFields(message)?.let {
            plain += DetailField("unknown_fields", FieldHints.hex(it))
        }

        out += Section(path, plain)

        if (message is Data) payload(path, message, includeDefaults, out)
        for ((childPath, child) in nested) collect(childPath, child, includeDefaults, out)
    }

    private fun payload(
        path: String,
        data: Data,
        includeDefaults: Boolean,
        out: MutableList<Section>,
    ) {
        if (data.payload.size == 0) return
        when (val payload = PayloadCodecs.read(data.portnum, data.payload)) {
            is Payload.Decoded -> collect("$path.payload (${payload.typeName})", payload.message, includeDefaults, out)
            is Payload.Text -> out += Section(
                "$path.payload (text)",
                listOf(DetailField("text", payload.text)),
            )
            is Payload.Raw -> out += Section(
                "$path.payload (${payload.note})",
                payload.fields + DetailField("bytes", FieldHints.hex(payload.bytes)),
            )
            is Payload.Failed -> out += Section(
                "$path.payload (не разобрано)",
                listOf(
                    DetailField("reason", payload.reason),
                    DetailField("bytes", FieldHints.hex(payload.bytes)),
                ),
            )
        }
    }
}
