package com.cerocoder.meshtest.ble

import android.util.Log
import com.cerocoder.meshtest.ble.protocol.BleFailure
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

    /**
     * Момент, когда сессия действительно открылась, или null, если открыть её не
     * удалось. Отдельное поле нужно потому, что «стабильность» обязана измерять
     * время living связи, а не длительность попытки: неудачное подключение
     * занимает пятнадцать секунд по таймауту, то есть больше порога стабильности,
     * и без этого различия каждая неудача засчитывалась бы за удачное соединение,
     * обнуляла счётчик и откат не рос бы никогда.
     */
    @Volatile
    private var sessionOpenedAt: Long? = null

    override fun start() {
        scope.launch {
            while (isActive) {
                // Пауза перед каждой попыткой, включая первую: прошивке нужно время
                // освободить свою GATT-сессию, иначе подключение срывается посреди
                // handshake.
                delay(policy.settleDelay)
                sessionOpenedAt = null
                val reason = runSession()
                val openedAt = sessionOpenedAt
                val stable = openedAt != null &&
                    now() - openedAt >= policy.minStableConnection.inWholeMilliseconds
                val failures = policy.onOutcome(wasStable = stable)
                callback.onDisconnect(isPermanent = false, reason = reason)
                delay(policy.backoffFor(failures))
            }
        }
    }

    /**
     * Одна попытка «подключиться и жить до разрыва».
     *
     * Возвращает причину окончания сессии — её увидит пользователь. Слой Nordic
     * присылает её уже описанной, здесь остаётся лишь запасной текст на случай
     * ошибки, которую он описать не успел.
     */
    private suspend fun runSession(): String? {
        val session = try {
            openSession(mac)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "не удалось открыть сессию с $mac", e)
            return (e as? BleFailure)?.description ?: "не удалось подключиться к ноде"
        }

        var reason: String? = null
        try {
            val radio = MeshRadioProfile(session.client)
            profile = radio
            sessionOpenedAt = now()
            callback.onConnect()
            coroutineScope {
                val pump = launch { radio.fromRadio.collect { callback.onDataReceived(it) } }
                // Насос кадров сам по себе бесконечен: он ждёт триггеров, а у
                // мёртвой ноды триггеров не бывает. Сессию завершает либо
                // фатальная ошибка чтения, либо сообщение стека о разрыве —
                // второе и есть единственный сигнал, когда связь умирает в тишине.
                val watcher = launch {
                    reason = session.awaitDisconnect()
                    Log.i(TAG, "стек сообщил о разрыве: $reason")
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
            reason = (e as? BleFailure)?.description ?: "сессия прервана"
        } finally {
            profile = null
            // Незакрытая сессия — это утёкшее GATT-соединение и статус 133 при
            // следующей попытке. Закрытие не должно срываться отменой.
            withContext(NonCancellable) { session.close() }
        }
        return reason
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
