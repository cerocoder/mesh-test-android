package com.cerocoder.meshtest.frame

import org.meshtastic.proto.FromRadio

/**
 * Кадр вместе с тем, что известно о его приёме.
 *
 * Лента хранит записи, а не голые кадры: у самого `FromRadio` нет ни времени
 * приёма, ни размера, ни источника, а показать их надо.
 */
data class FrameRecord(
    /** Номер внутри подключения, с единицы. Обнуляется вместе с лентой. */
    val seq: Long,
    /** Часы телефона, момент приёма. */
    val receivedAtMillis: Long,
    /**
     * Адрес устройства, от которого пришёл кадр.
     *
     * Внутри одного подключения он у всех кадров одинаков, и ленте не нужен.
     * Нужен снимку: открытый детальный вид переживает разрыв и повторное
     * подключение, которое ленту очищает, — а к этому моменту приложение может
     * быть подключено уже к другому устройству.
     */
    val sourceAddress: String,
    /** Длина кадра, как он пришёл по эфиру. */
    val sizeBytes: Int,
    val frame: FromRadio,
)

/**
 * Разбор записи на значения, которые умеет хранить Bundle.
 *
 * Вынесено отдельными функциями, а не спрятано в `Saver`, чтобы проверяться
 * обычным JVM-тестом: `Saver` требует окружения Compose, а формат снимка
 * ошибиться может и без него.
 */
fun frameRecordToList(record: FrameRecord): List<Any> = listOf(
    record.seq,
    record.receivedAtMillis,
    record.sourceAddress,
    record.sizeBytes,
    record.frame.encode(),
)

fun frameRecordFromList(saved: List<Any>): FrameRecord? {
    if (saved.size != 5) return null
    return FrameRecord(
        seq = saved[0] as Long,
        receivedAtMillis = saved[1] as Long,
        sourceAddress = saved[2] as String,
        sizeBytes = saved[3] as Int,
        frame = FromRadio.ADAPTER.decode(saved[4] as ByteArray),
    )
}
