package com.cerocoder.meshtest.frame

import com.squareup.wire.Message
import com.squareup.wire.WireEnum
import com.squareup.wire.WireField
import okio.ByteString

/** Поле сообщения protobuf, прочитанное по схеме. */
data class RawField(val name: String, val order: Int, val value: Any)

/**
 * Обход полей сообщения Wire через аннотацию `@WireField`.
 *
 * Подписи и порядок берутся из схемы во время выполнения. Альтернатива —
 * завести вручную порядка шестисот подписей на восемнадцать вариантов кадра и
 * все portnum, и поддерживать их при каждом обновлении протобуфов. Аннотация
 * это снимает: у неё retention RUNTIME, а `declaredName` несёт то самое имя,
 * что стоит в `.proto`.
 *
 * Цена решения: обход держится на именах полей и аннотациях, поэтому при
 * включении `minify` в release понадобятся keep-правила на
 * `org.meshtastic.proto.**`. Сейчас `isMinifyEnabled = false`.
 */
object FieldWalker {

    fun rawFields(message: Any, includeDefaults: Boolean = false): List<RawField> {
        val result = mutableListOf<RawField>()
        for (field in message.javaClass.declaredFields) {
            val wire = field.getAnnotation(WireField::class.java) ?: continue
            // Wire ставит аннотацию на backing-поле, а оно приватное.
            field.isAccessible = true
            val value = field.get(message) ?: continue
            // Явное присутствие Wire выражает nullable-типом, и проверка на null
            // выше его уже разобрала. Ноль в таком поле — настоящее значение:
            // NodeInfo.hops_away = 0 означает «нода в прямой видимости», и
            // спрятать его значит подменить смысл на «неизвестно».
            if (!includeDefaults && !hasExplicitPresence(wire.label) && isIdentity(value)) continue
            val name = wire.declaredName.ifEmpty { field.name }
            // schemaIndex — позиция в объявлении. Порядок declaredFields не
            // определён спецификацией JVM, поэтому сортировка обязательна:
            // без неё поля кадра выстроятся произвольно и по-разному на разных
            // машинах. Запасной ключ — номер тега, он есть всегда.
            val order = if (wire.schemaIndex >= 0) wire.schemaIndex else wire.tag
            result += RawField(name, order, value)
        }
        result.sortBy { it.order }
        return result
    }

    /**
     * Байты, которых нет в схеме приложения.
     *
     * Wire их сохраняет при разборе. Непустое значение означает, что прошивка
     * ноды знает поля, которых не знают протобуфы приложения, — сведение,
     * которое иначе увидеть негде.
     */
    fun unknownFields(message: Any): ByteString? =
        (message as? Message<*, *>)?.unknownFields?.takeIf { it.size > 0 }

    /**
     * У поля есть явное присутствие: proto3 `optional` и члены `oneof`.
     *
     * Wire помечает и то и другое меткой OPTIONAL — отдельной метки для oneof в
     * аннотации нет, там проставляется oneofName. ONE_OF в этой схеме не
     * встречается, но перечисление его содержит, и по смыслу он тоже про
     * присутствие.
     */
    private fun hasExplicitPresence(label: WireField.Label): Boolean =
        label == WireField.Label.OPTIONAL || label == WireField.Label.ONE_OF

    /**
     * Значение неотличимо от «поле не задано».
     *
     * Верно только для полей без явного присутствия — proto3 без `optional` и
     * повторяющихся: там ноль (или пустой список) и отсутствие — одно и то же,
     * различить их невозможно в принципе. Поэтому такие поля скрываются: иначе
     * `ModuleConfig` выдаёт 140 нулей, среди которых теряется заданное. Для
     * полей с явным присутствием эта функция не вызывается: там о присутствии
     * уже сказала проверка на null.
     */
    private fun isIdentity(value: Any): Boolean = when (value) {
        is Int -> value == 0
        is Long -> value == 0L
        is Float -> value == 0f
        is Double -> value == 0.0
        is Boolean -> !value
        is String -> value.isEmpty()
        is ByteString -> value.size == 0
        is Collection<*> -> value.isEmpty()
        is Map<*, *> -> value.isEmpty()
        is WireEnum -> value.value == 0
        else -> false
    }
}
