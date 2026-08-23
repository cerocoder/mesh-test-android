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
     */
    fun onDisconnect(isPermanent: Boolean)

    /** Пришёл закодированный FromRadio. */
    fun onDataReceived(bytes: ByteArray)
}

/** Создаёт транспорт по внутреннему адресу устройства. */
interface RadioTransportFactory {
    fun create(address: String, callback: RadioTransportCallback): RadioTransport
}
