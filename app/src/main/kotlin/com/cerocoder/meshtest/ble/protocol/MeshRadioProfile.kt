package com.cerocoder.meshtest.ble.protocol

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Протокол обмена с нодой поверх характеристик.
 *
 * Устройство FROMNUM — не канал данных, а «звонок в дверь»: оно лишь сообщает,
 * что данные есть. Сами кадры вычитываются циклическими чтениями FROMRADIO, пока
 * не вернётся пустой массив.
 *
 * Триггеров у цикла три: нотификация FROMNUM, старт подписки и каждая запись в
 * TORADIO. Затравочный триггер обязателен — прошивка не шлёт FROMNUM, пока не
 * перешла в состояние отправки пакетов, то есть ровно во время handshake
 * нотификаций нет и читать нужно самим.
 */
class MeshRadioProfile(private val client: MeshGattClient) {

    // Один слот с вытеснением старого: пачка триггеров схлопывается в один
    // проход, писатели никогда не блокируются, устаревшие запросы не копятся.
    private val drainTriggers = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val fromRadio: Flow<ByteArray> = channelFlow {
        client.awaitSubscriptionReady()

        launch {
            client.fromNumNotifications.collect { drainTriggers.tryEmit(Unit) }
        }

        drainTriggers.tryEmit(Unit)

        drainTriggers.collect {
            var keepReading = true
            while (keepReading) {
                val packet = try {
                    client.readFromRadio()
                } catch (e: IOException) {
                    // Транзиентный сбой: прекращаем текущий проход, но поток
                    // остаётся живым и продолжит со следующего триггера.
                    Log.w(TAG, "ошибка чтения FROMRADIO, ждём следующего триггера", e)
                    keepReading = false
                    continue
                }
                if (packet.isEmpty()) keepReading = false else send(packet)
            }
        }
    }

    /** Отправить кадр и сразу запросить вычитывание: ответ обычно уже готов. */
    suspend fun send(bytes: ByteArray) {
        client.writeToRadio(bytes)
        drainTriggers.tryEmit(Unit)
    }

    private companion object {
        const val TAG = "MeshRadioProfile"
    }
}
