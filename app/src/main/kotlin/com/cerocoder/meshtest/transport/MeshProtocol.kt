package com.cerocoder.meshtest.transport

/**
 * Константы протокола Meshtastic и разбор внутренних адресов устройств.
 *
 * Адрес — одна строка, первый символ задаёт транспорт: "m:" — демо-устройство,
 * "x" — BLE. Такая же конвенция используется в официальном приложении.
 */
object MeshProtocol {

    /** Нонс запроса конфигурации (стадия 1 handshake). */
    const val CONFIG_NONCE = 69420

    /** Нонс запроса базы нод (стадия 2 handshake). */
    const val NODE_INFO_NONCE = 69421

    /** Потолок размера кадра: всё, что больше, отбрасывается как мусор. */
    const val MAX_FRAME_BYTES = 512

    const val DEMO_PREFIX = "m:"
    const val BLE_PREFIX = "x"

    /** Идентификатор сценария из демо-адреса, или null если адрес не демо. */
    fun scenarioIdOrNull(address: String): String? =
        if (address.startsWith(DEMO_PREFIX)) {
            address.removePrefix(DEMO_PREFIX).takeIf { it.isNotEmpty() }
        } else {
            null
        }

    /** MAC-адрес из BLE-адреса, или null если адрес не BLE. */
    fun bleMacOrNull(address: String): String? =
        if (address.startsWith(BLE_PREFIX) && !address.startsWith(DEMO_PREFIX)) {
            address.removePrefix(BLE_PREFIX).takeIf { it.isNotEmpty() }
        } else {
            null
        }
}
