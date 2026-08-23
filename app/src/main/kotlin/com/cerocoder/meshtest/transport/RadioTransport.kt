package com.cerocoder.meshtest.transport

/**
 * Транспорт до ноды: сырые байты в обе стороны.
 *
 * Реализации: [FakeRadioTransport] (демо-устройство) и BleRadioTransport (живая нода).
 * Ничто выше этого интерфейса не знает, что именно подключено.
 */
interface RadioTransport {

    /** Начать установление связи. Результат придёт через колбэк. */
    fun start()

    /** Отправить закодированный ToRadio. Вызов не блокирует. */
    fun send(bytes: ByteArray)

    /** Разорвать связь и освободить ресурсы. Повторный вызов безопасен. */
    suspend fun close()
}

/** Узкий колбэк транспорт -> приложение. */
interface RadioTransportCallback {

    fun onConnect()

    /**
     * @param isPermanent true — попытки подключения прекращены (пользователь отключился,
     *   устройство недоступно); false — связь может восстановиться сама.
     * @param reason человекочитаемая причина для интерфейса, или null, если разрыв
     *   штатный и объяснять нечего. Транспорт обязан присылать сюда уже пригодный
     *   к показу текст, а не сообщение исключения: сырой текст исключения на экране
     *   бесполезен пользователю и утекает подробности реализации.
     */
    fun onDisconnect(isPermanent: Boolean, reason: String? = null)

    /** Пришёл закодированный FromRadio. */
    fun onDataReceived(bytes: ByteArray)
}

/** Создаёт транспорт по внутреннему адресу устройства. */
interface RadioTransportFactory {
    fun create(address: String, callback: RadioTransportCallback): RadioTransport
}
