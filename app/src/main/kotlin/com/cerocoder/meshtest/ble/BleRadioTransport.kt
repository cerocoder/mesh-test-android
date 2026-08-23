package com.cerocoder.meshtest.ble

import android.util.Log
import com.cerocoder.meshtest.ble.protocol.BleSession
import com.cerocoder.meshtest.ble.protocol.MeshRadioProfile
import com.cerocoder.meshtest.transport.RadioTransport
import com.cerocoder.meshtest.transport.RadioTransportCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
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
                val failures = policy.onOutcome(wasStable = stable)
                callback.onDisconnect(isPermanent = false)
                delay(policy.backoffFor(failures))
            }
        }
    }

    /** Одна попытка «подключиться и жить до разрыва». */
    private suspend fun runSession() {
        val session = try {
            openSession(mac)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "не удалось открыть сессию с $mac", e)
            return
        }

        try {
            val radio = MeshRadioProfile(session.client)
            profile = radio
            callback.onConnect()
            coroutineScope {
                val pump = launch { radio.fromRadio.collect { callback.onDataReceived(it) } }
                // Насос кадров сам по себе бесконечен: он ждёт триггеров, а у
                // мёртвой ноды триггеров не бывает. Сессию завершает либо
                // фатальная ошибка чтения, либо сообщение стека о разрыве —
                // второе и есть единственный сигнал, когда связь умирает в тишине.
                val watcher = launch {
                    session.awaitDisconnect()
                    Log.i(TAG, "стек сообщил о разрыве, завершаем сессию")
                    pump.cancel()
                }
                pump.join()
                watcher.cancel()
            }
        } catch (e: CancellationException) {
            // Отмена — это наш собственный close(), а не разрыв связи. Проглотив
            // её, мы вернулись бы в цикл и успели бы записать неудачу в политику и
            // доложить наверх о разрыве, которого не было: пользователь отключился
            // сам. Пробрасываем — цикл завершится, а сессию всё равно закроет
            // finally под NonCancellable.
            throw e
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
            } catch (e: CancellationException) {
                throw e
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
