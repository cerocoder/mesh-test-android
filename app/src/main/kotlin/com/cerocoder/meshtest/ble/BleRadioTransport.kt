package com.cerocoder.meshtest.ble

import android.util.Log
import com.cerocoder.meshtest.ble.protocol.BleSession
import com.cerocoder.meshtest.ble.protocol.MeshRadioProfile
import com.cerocoder.meshtest.transport.RadioTransport
import com.cerocoder.meshtest.transport.RadioTransportCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Транспорт до реальной ноды по Bluetooth LE.
 *
 * Открытие сессии передаётся снаружи ([openSession]) — благодаря этому цикл
 * переподключения, порядок операций и освобождение ресурсов проверяются
 * обычными JVM-тестами, а всё, что знает про Android, живёт в `ble/nordic/`.
 */
class BleRadioTransport(
    private val mac: String,
    private val callback: RadioTransportCallback,
    parentScope: CoroutineScope,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val now: () -> Long = { System.currentTimeMillis() },
    private val openSession: suspend (mac: String) -> BleSession,
) : RadioTransport {

    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)

    @Volatile
    private var profile: MeshRadioProfile? = null

    override fun start() {
        scope.launch {
            while (isActive) {
                // Пауза перед каждой попыткой, включая первую: прошивке нужно время
                // освободить свою GATT-сессию, иначе подключение срывается посреди
                // handshake.
                delay(policy.settleDelay)
                val startedAt = now()
                runSession()
                val uptime = now() - startedAt
                val stable = uptime >= policy.minStableConnection.inWholeMilliseconds
                val failures = policy.onOutcome(wasStable = stable, wasIntentional = false)
                callback.onDisconnect(isPermanent = false)
                delay(policy.backoffFor(failures))
            }
        }
    }

    /** Одна попытка «подключиться и жить до разрыва». */
    private suspend fun runSession() {
        val session = try {
            openSession(mac)
        } catch (e: Throwable) {
            Log.w(TAG, "не удалось открыть сессию с $mac", e)
            return
        }

        try {
            val radio = MeshRadioProfile(session.client)
            profile = radio
            callback.onConnect()
            radio.fromRadio.collect { callback.onDataReceived(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "сессия завершилась ошибкой", e)
        } finally {
            profile = null
            // Незакрытая сессия — это утёкшее GATT-соединение и статус 133 при
            // следующей попытке. Закрытие не должно срываться отменой.
            withContext(NonCancellable) { session.close() }
        }
    }

    override fun send(bytes: ByteArray) {
        val radio = profile
        if (radio == null) {
            Log.w(TAG, "нет активного профиля, кадр отброшен")
            return
        }
        scope.launch {
            try {
                radio.send(bytes)
            } catch (e: Throwable) {
                Log.w(TAG, "запись в TORADIO не удалась", e)
            }
        }
    }

    override suspend fun close() {
        withContext(NonCancellable) { job.cancelAndJoin() }
    }

    private companion object {
        const val TAG = "BleRadioTransport"
    }
}
