package com.cerocoder.meshtest.ble.protocol

import kotlinx.coroutines.flow.Flow

/**
 * Нижний уровень BLE: ровно те операции над характеристиками Meshtastic,
 * которые нужны протоколу, и ничего больше.
 *
 * Реализаций две: [com.cerocoder.meshtest.ble.nordic.NordicMeshGattClient] поверх
 * настоящего GATT и тестовый двойник. Благодаря этому drain-цикл — самая хрупкая
 * часть протокола — проверяется обычными JVM-тестами без Android и без ноды.
 */
interface MeshGattClient {

    /**
     * Уведомления характеристики FROMNUM.
     *
     * Значение не несёт смысла: прошивка сообщает лишь «есть данные», а сами
     * пакеты вычитываются через [readFromRadio].
     */
    val fromNumNotifications: Flow<Unit>

    /**
     * Приостанавливается до фактической записи CCCD, то есть до момента, когда
     * нотификации действительно включены.
     *
     * Без этого ожидания запрос конфигурации уходит в пустоту: прошивка ответит
     * нотификацией, которую некому принять, и приложение зависнет в Connecting.
     */
    suspend fun awaitSubscriptionReady()

    /** Одно чтение FROMRADIO. Пустой массив означает, что очередь пуста. */
    suspend fun readFromRadio(): ByteArray

    /** Запись одного закодированного ToRadio в TORADIO. */
    suspend fun writeToRadio(bytes: ByteArray)
}
