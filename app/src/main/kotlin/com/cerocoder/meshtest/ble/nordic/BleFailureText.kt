package com.cerocoder.meshtest.ble.nordic

import com.cerocoder.meshtest.ble.protocol.BleFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
    is CancellationException -> "операция BLE прервана"
    else -> "сбой BLE: ${e::class.simpleName ?: "неизвестная ошибка"}"
}

/**
 * Расшифровка кода отказа.
 *
 * Кодов две разные таблицы, и их легко перепутать. Отрицательные значения — это
 * причины самой библиотеки (`FailCallback.REASON_*`): почему запрос не удался с
 * её точки зрения. Неотрицательные — статусы GATT от стека Android. Показывать
 * человеку голое число бессмысленно в обоих случаях, но особенно в первом:
 * «статус -5» на экране не говорит ничего, тогда как за ним стоит внятное
 * «нода не ответила на попытку подключения».
 */
private fun gattStatusText(status: Int): String = when (status) {
    -1 -> "связь с нодой потеряна"
    -2 -> "у ноды нет нужного сервиса Meshtastic"
    -3 -> "характеристика не найдена"
    -4 -> "нода отклонила запрос"
    -5 -> "нода не ответила на попытку подключения"
    -6 -> "нода прислала неожиданный ответ"
    -7 -> "запрос отменён"
    -8 -> "уведомления не включены"
    -9 -> "нода не поддерживает такую настройку"
    -100 -> "Bluetooth выключен"

    5 -> "статус 5, нужна аутентификация — нода не спарена"
    8 -> "статус 8, нода вне зоны действия"
    15 -> "статус 15, нужен шифрованный канал"
    19 -> "статус 19, нода разорвала связь сама"
    22 -> "статус 22, зависание радио ноды"
    62 -> "статус 62, связь не установилась"
    129, 133 -> "статус $status, устаревшее соединение Android"
    else -> "статус $status"
}

/**
 * Выполнить вызов Nordic, не дав библиотеке подделать отмену корутины.
 *
 * Ловушка, стоившая живого разбора на телефоне: ktx-обёртка превращает
 * `FailCallback.REASON_CANCELLED` в самый настоящий [CancellationException] —
 * хотя отменён всего лишь запрос, например по нашему же таймауту подключения.
 * Код вокруг честно пробрасывает отмену дальше, как и положено со структурной
 * конкурентностью, и в результате умирает весь цикл переподключения: попытка
 * подключения к недоступной ноде тихо убивала транспорт целиком.
 *
 * Различать можно только по одному признаку — жива ли наша собственная корутина.
 * Если жива, отмена пришла изнутри библиотеки и является обычным отказом.
 */
suspend fun <T> nordicCall(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    if (currentCoroutineContext().isActive) throw BleFailure(describeBleFailure(e), e) else throw e
} catch (e: Throwable) {
    throw BleFailure(describeBleFailure(e), e)
}
