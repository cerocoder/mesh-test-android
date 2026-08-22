package com.cerocoder.meshtest.transport

import android.util.Log
import com.cerocoder.meshtest.emulator.MeshScenario
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.QueueStatus
import org.meshtastic.proto.ToRadio
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Эмулятор ноды: разбирает входящие ToRadio и отвечает кадрами из [scenario].
 *
 * Кадры отдаются с паузой [frameDelay], а не одним взрывом: мгновенная выдача
 * скрыла бы гонки, которые проявятся на настоящем асинхронном транспорте.
 * В тестах обе задержки выставляются в ноль.
 */
class FakeRadioTransport(
    private val scenario: MeshScenario,
    private val callback: RadioTransportCallback,
    parentScope: CoroutineScope,
    private val connectDelay: Duration = 300.milliseconds,
    private val frameDelay: Duration = 25.milliseconds,
) : RadioTransport {

    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)

    // Атомарный, а не обычный Int: emit() запускает корутины на parentScope, который в приложении
    // многопоточный (Dispatchers.Default). Обычный инкремент здесь — гонка, ломающая уникальность id.
    private val nextFrameId = AtomicInteger(1)

    override fun start() {
        scope.launch {
            delay(connectDelay)
            callback.onConnect()
        }
    }

    override fun send(bytes: ByteArray) {
        val message = try {
            ToRadio.ADAPTER.decode(bytes)
        } catch (e: IOException) {
            Log.w(TAG, "не удалось разобрать ToRadio (${bytes.size} байт)", e)
            return
        }

        // Локальная переменная, а не message.want_config_id напрямую: свойство объявлено
        // в другом модуле (артефакт protobufs), и компилятор не приводит его к non-null
        // по равенству — только локальная val поддаётся smart cast.
        val nonce = message.want_config_id
        when {
            nonce == MeshProtocol.CONFIG_NONCE ->
                emit(scenario.configStageFrames(nonce))

            nonce == MeshProtocol.NODE_INFO_NONCE ->
                emit(scenario.nodeStageFrames(nonce))

            // Настоящая прошивка отвечает на heartbeat статусом очереди — это
            // доказывает, что связь жива. Без ответа демо вело бы себя не как нода.
            message.heartbeat != null ->
                emit(listOf(FromRadio(queueStatus = QueueStatus(res = 0, free = 16, maxlen = 16))))

            message.disconnect == true -> {
                Log.i(TAG, "получено прощание, закрываем сессию")
                callback.onDisconnect(isPermanent = true)
            }

            else -> Log.d(TAG, "проигнорирован ToRadio: $message")
        }
    }

    private fun emit(frames: List<FromRadio>) {
        scope.launch {
            frames.forEach { frame ->
                delay(frameDelay)
                callback.onDataReceived(frame.copy(id = nextFrameId.getAndIncrement()).encode())
            }
        }
    }

    override suspend fun close() {
        job.cancelAndJoin()
    }

    private companion object {
        const val TAG = "FakeRadioTransport"
    }
}
