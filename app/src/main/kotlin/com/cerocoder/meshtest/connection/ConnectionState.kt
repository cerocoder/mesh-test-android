package com.cerocoder.meshtest.connection

/**
 * Прикладное состояние соединения.
 *
 * [Connecting] означает, что физическая связь есть, но конфигурация с ноды ещё
 * не выкачана. Пользователю нельзя показывать «подключено» до завершения второй
 * стадии handshake.
 */
sealed interface ConnectionState {

    /**
     * Связи нет.
     *
     * @param reason человекочитаемая причина, если разрыв произошёл не по воле
     *   пользователя: таймаут handshake, отказ в разрешении, выключенный адаптер.
     *   `null` означает намеренное отключение и не показывается как ошибка.
     */
    data class Disconnected(val reason: String? = null) : ConnectionState

    data object Connecting : ConnectionState

    data object Connected : ConnectionState
}
