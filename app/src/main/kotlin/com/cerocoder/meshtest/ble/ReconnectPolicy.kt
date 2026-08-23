package com.cerocoder.meshtest.ble

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Политика переподключения с экспоненциальным откатом.
 *
 * Значения не подобраны на глаз: пауза в 3 секунды перед каждой попыткой нужна
 * потому, что при 1,5 секунды прошивка не успевает освободить свою GATT-сессию,
 * и подключение срывается уже посреди handshake.
 */
class ReconnectPolicy(
    /** Короче этого соединение считается неудачной попыткой, а не разрывом. */
    val minStableConnection: Duration = 5.seconds,
) {

    /** Пауза перед каждой попыткой, включая первую. */
    val settleDelay: Duration = 3.seconds

    var consecutiveFailures: Int = 0
        private set

    /** Учесть исход попытки и вернуть текущее число неудач подряд. */
    fun onOutcome(wasStable: Boolean, wasIntentional: Boolean): Int {
        consecutiveFailures = if (wasIntentional || wasStable) 0 else consecutiveFailures + 1
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
