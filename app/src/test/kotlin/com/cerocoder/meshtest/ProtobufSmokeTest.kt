package com.cerocoder.meshtest

import org.junit.Assert.assertEquals
import org.junit.Test
import org.meshtastic.proto.ToRadio

class ProtobufSmokeTest {

    @Test
    fun `ToRadio кодируется и декодируется без потерь`() {
        val original = ToRadio(want_config_id = 69420)

        val decoded = ToRadio.ADAPTER.decode(original.encode())

        assertEquals(69420, decoded.want_config_id)
    }
}
