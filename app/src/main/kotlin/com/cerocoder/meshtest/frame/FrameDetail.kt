package com.cerocoder.meshtest.frame

/**
 * Одна строка детального вида: имя поля из схемы и его значение.
 *
 * Имя намеренно английское, из `.proto`. Подпись, придуманная приложением,
 * разошлась бы с документацией протокола — а сверяться человек будет именно с
 * ней, и расхождение стоило бы дороже перевода.
 *
 * Класс называется `DetailField`, а не `Field`, чтобы в обходчике не
 * сталкиваться с `java.lang.reflect.Field`.
 */
data class DetailField(val name: String, val value: String)

/**
 * Группа строк. Заголовок несёт путь: `packet`, `packet.decoded`,
 * `packet.decoded.payload (Position)`.
 *
 * Список секций плоский, вложенных секций нет: на узком экране отступы,
 * растущие вправо, съедают ширину быстрее, чем добавляют ясности.
 */
data class Section(val title: String, val fields: List<DetailField>)

/** Готовое к отрисовке содержимое кадра. */
data class FrameDetail(
    val title: String,
    val common: List<DetailField>,
    val sections: List<Section>,
)
