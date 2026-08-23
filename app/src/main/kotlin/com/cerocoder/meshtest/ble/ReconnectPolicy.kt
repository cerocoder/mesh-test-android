package com.cerocoder.meshtest.ble

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Политика переподключения с экспоненциальным откатом.
 *
 * Пауза в 3 секунды перед каждой попыткой взята не с потолка, но и не измерена
 * здесь: это перенос из Meshtastic-Android v2.8.0, где отмечено, что при 1,5
 * секунды прошивка не успевает освободить свою GATT-сессию и подключение
 * срывается посреди handshake. Исходник лежит в
 * `Research/connection/code/10-BleReconnectPolicy.kt`. Наш стек другой, поэтому
 * значение считается обоснованной отправной точкой, а не установленным фактом:
 * подтвердить или опровергнуть его может только ручная приёмка на живой ноде.
 */
class ReconnectPolicy(
    /** Короче этого соединение считается неудачной попыткой, а не разрывом. */
    val minStableConnection: Duration = 5.seconds,
) {

    /** Пауза перед каждой попыткой, включая первую. */
    val settleDelay: Duration = 3.seconds

    var consecutiveFailures: Int = 0
        private set

    /**
     * Учесть исход попытки и вернуть текущее число неудач подряд.
     *
     * Флага «разрыв намеренный» здесь нет намеренно. Намеренное отключение
     * отменяет корутину транспорта, и цикл переподключения завершается, не дойдя
     * до этого вызова, — то есть учитывать такой исход просто некому.
     */
    fun onOutcome(wasStable: Boolean): Int {
        consecutiveFailures = if (wasStable) 0 else consecutiveFailures + 1
        return consecutiveFailures
    }

    /** Задержка перед следующей попыткой: 5, 10, 20, 40, далее 60 секунд. */
    fun backoffFor(consecutiveFailures: Int): Duration {
        if (consecutiveFailures <= 0) return BASE_DELAY
        val multiplier = 1 shl (consecutiveFailures - 1).coerceAtMost(MAX_EXPONENT)
        return minOf(BASE_DELAY * multiplier, MAX_DELAY)
    }

    private companion object {
        val BASE_DELAY = 5.seconds
        val MAX_DELAY = 60.seconds
        const val MAX_EXPONENT = 4
    }
}
