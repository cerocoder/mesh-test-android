package com.cerocoder.meshtest.ble

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class ReconnectPolicyTest {

    private val policy = ReconnectPolicy()

    @Test
    fun `задержка растёт вдвое и упирается в потолок`() {
        assertEquals(5.seconds, policy.backoffFor(1))
        assertEquals(10.seconds, policy.backoffFor(2))
        assertEquals(20.seconds, policy.backoffFor(3))
        assertEquals(40.seconds, policy.backoffFor(4))
        assertEquals(60.seconds, policy.backoffFor(5))
        assertEquals("потолок держится и дальше", 60.seconds, policy.backoffFor(12))
    }

    @Test
    fun `стабильное соединение обнуляет счётчик неудач`() {
        policy.onOutcome(wasStable = false, wasIntentional = false)
        policy.onOutcome(wasStable = false, wasIntentional = false)

        assertEquals(0, policy.onOutcome(wasStable = true, wasIntentional = false))
    }

    @Test
    fun `намеренный разрыв обнуляет счётчик даже при коротком соединении`() {
        policy.onOutcome(wasStable = false, wasIntentional = false)

        assertEquals(0, policy.onOutcome(wasStable = false, wasIntentional = true))
    }

    @Test
    fun `нестабильные соединения накапливают счётчик`() {
        assertEquals(1, policy.onOutcome(wasStable = false, wasIntentional = false))
        assertEquals(2, policy.onOutcome(wasStable = false, wasIntentional = false))
    }

    @Test
    fun `пауза перед попыткой закреплена от случайной правки`() {
        // Тест не измеряет ничего: сравнение константы с собой падает ровно в
        // одном случае — если кто-то отредактирует литерал. Этого и достаточно.
        // Источник самого значения указан в документации класса, а проверить его
        // может только живая нода.
        assertEquals(3.seconds, policy.settleDelay)
    }

    @Test
    fun `нулевой счётчик даёт базовую задержку`() {
        // onOutcome возвращает 0 после каждого удачного соединения, и Task 8
        // подаёт этот ноль прямо в backoffFor: путь штатный, а не краевой.
        assertEquals(5.seconds, policy.backoffFor(0))
        assertEquals("отрицательное значение не должно ломать откат", 5.seconds, policy.backoffFor(-3))
    }
}
