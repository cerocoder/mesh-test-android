package com.cerocoder.meshtest.connection

/**
 * Прикладное состояние соединения.
 *
 * [Connecting] означает, что физическая связь есть, но конфигурация с ноды ещё
 * не выкачана. Пользователю нельзя показывать «подключено» до завершения второй
 * стадии handshake.
 */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
}
