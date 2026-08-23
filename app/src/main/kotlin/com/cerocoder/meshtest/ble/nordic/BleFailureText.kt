package com.cerocoder.meshtest.ble.nordic

import no.nordicsemi.android.ble.exception.BluetoothDisabledException
import no.nordicsemi.android.ble.exception.DeviceDisconnectedException
import no.nordicsemi.android.ble.exception.InvalidRequestException
import no.nordicsemi.android.ble.exception.RequestFailedException

/**
 * Превратить отказ Nordic в строку, пригодную для показа на экране.
 *
 * Знание о типах библиотеки живёт только здесь: транспорт получает уже готовый
 * текст. Коды статусов расшифрованы по таблице из `Research/connection/
 * 03-ble-protocol.md` — без расшифровки «статус 133» пользователю ничего не
 * говорит, а именно он и означает самую частую беду Android.
 */
fun describeBleFailure(e: Throwable): String = when (e) {
    is RequestFailedException -> "операция BLE отклонена: ${gattStatusText(e.status)}"
    is DeviceDisconnectedException -> "нода разорвала связь во время операции"
    is BluetoothDisabledException -> "Bluetooth выключен"
    is InvalidRequestException -> "запрос BLE отвергнут: соединение уже потеряно"
    is SecurityException -> "нет разрешения Bluetooth"
    else -> "сбой BLE: ${e::class.simpleName ?: "неизвестная ошибка"}"
}

/** Расшифровка кода состояния разрыва, который сообщает стек. */
fun describeDisconnectReason(reason: String): String = "связь потеряна ($reason)"

private fun gattStatusText(status: Int): String = when (status) {
    5 -> "статус 5, нужна аутентификация — нода не спарена"
    8 -> "статус 8, нода вне зоны действия"
    15 -> "статус 15, нужен шифрованный канал"
    19 -> "статус 19, нода разорвала связь сама"
    22 -> "статус 22, зависание радио ноды"
    62 -> "статус 62, связь не установилась"
    129, 133 -> "статус $status, устаревшее соединение Android"
    else -> "статус $status"
}
