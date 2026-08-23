package com.cerocoder.meshtest.ble.nordic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Спаривание с нодой — обязательно **до** GATT-подключения.
 *
 * Прошивка Meshtastic требует шифрованного канала. Если подключиться первым, то
 * запись CCCD, которую библиотека выполняет внутри инициализации, уйдёт по
 * нешифрованному каналу, прошивка её отклонит (GATT status 5 или 15), подписка
 * не состоится, и сессия умрёт, не получив ни одного кадра. Диалог спаривания
 * при этом всё равно появится — Android поднимает бондинг сам, наткнувшись на
 * защищённую характеристику, — но к тому моменту сессия уже потеряна.
 *
 * Состояние опрашивается, а не берётся из широковещания: `ACTION_BOND_STATE_CHANGED`
 * на части устройств приходит с опозданием или не приходит вовсе.
 */
@SuppressLint("MissingPermission")
suspend fun ensureBondedBeforeConnect(device: BluetoothDevice) {
    if (device.bondState == BluetoothDevice.BOND_BONDED) return

    var sawBonding = device.bondState == BluetoothDevice.BOND_BONDING
    if (!sawBonding && !device.createBond()) {
        // false здесь не обязательно отказ: бондинг мог уже начаться сам, от
        // GATT-операции по защищённой характеристике. Верим состоянию, а не
        // возвращённому значению.
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> return
            BluetoothDevice.BOND_BONDING -> sawBonding = true
            else -> error("не удалось начать спаривание с ${device.address}")
        }
    }

    // createBond() возвращает true раньше, чем Android сообщает BOND_BONDING.
    // Терпим несколько первых замеров BOND_NONE и только потом считаем отказом.
    var graceLeft = BOND_NONE_GRACE_POLLS

    val bonded = withTimeoutOrNull(BOND_TIMEOUT_MS) {
        var outcome: Boolean? = null
        while (outcome == null) {
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> outcome = true

                BluetoothDevice.BOND_BONDING -> {
                    // Увидев бондинг в процессе, дальше считаем BOND_NONE отказом:
                    // это уже не задержка, а пользователь нажал «отмена» или
                    // ввёл неверный код.
                    sawBonding = true
                    graceLeft = 0
                }

                else -> if (sawBonding || graceLeft-- <= 0) outcome = false
            }
            if (outcome == null) delay(BOND_POLL_INTERVAL_MS)
        }
        outcome
    } ?: false

    if (!bonded) {
        error("спаривание с ${device.address} не завершилось: состояние ${device.bondState}")
    }
    Log.i(TAG, "спаривание с ${device.address} подтверждено")
}

private const val TAG = "BleBonding"
private const val BOND_TIMEOUT_MS = 30_000L
private const val BOND_POLL_INTERVAL_MS = 500L
private const val BOND_NONE_GRACE_POLLS = 2
