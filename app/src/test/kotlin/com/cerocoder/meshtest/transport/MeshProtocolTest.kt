package com.cerocoder.meshtest.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeshProtocolTest {

    @Test
    fun `демо-адрес отдаёт идентификатор сценария`() {
        assertEquals("5nodes", MeshProtocol.scenarioIdOrNull("m:5nodes"))
    }

    @Test
    fun `BLE-адрес не считается демо-адресом`() {
        assertNull(MeshProtocol.scenarioIdOrNull("xAA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `демо-адрес без идентификатора отбрасывается`() {
        assertNull(MeshProtocol.scenarioIdOrNull("m:"))
    }

    @Test
    fun `BLE-адрес отдаёт MAC`() {
        assertEquals("AA:BB:CC:DD:EE:FF", MeshProtocol.bleMacOrNull("xAA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `демо-адрес не считается BLE-адресом`() {
        assertNull(MeshProtocol.bleMacOrNull("m:5nodes"))
    }

    @Test
    fun `адрес демо-устройства собирается из идентификатора сценария`() {
        assertEquals("m:5nodes", DeviceListEntry.Demo("5nodes", "Demo: 5 нод").address)
    }

    @Test
    fun `адрес BLE-устройства собирается из MAC`() {
        val entry = DeviceListEntry.Ble("Meshtastic_a1b2", "AA:BB:CC:DD:EE:FF", bonded = true, rssi = -60)
        assertEquals("xAA:BB:CC:DD:EE:FF", entry.address)
    }
}
