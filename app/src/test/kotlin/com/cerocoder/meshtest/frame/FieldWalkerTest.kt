package com.cerocoder.meshtest.frame

import okio.ByteString.Companion.decodeHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.Compressed
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.RouteDiscovery

class FieldWalkerTest {

    @Test
    fun `значение приватного поля читается рефлексией`() {
        val fields = FieldWalker.rawFields(MeshPacket(from = 5))

        assertEquals(1, fields.size)
        assertEquals("from", fields[0].name)
        assertEquals(5, fields[0].value)
    }

    @Test
    fun `имя берётся из схемы, а не из свойства Kotlin`() {
        // В .proto поле называется `data`, Wire переименовал свойство в `data_`
        // и положил исходное имя в declaredName. Показывать надо имя из схемы:
        // по нему человек сверяется с документацией протокола.
        val fields = FieldWalker.rawFields(
            Compressed(portnum = PortNum.TEXT_MESSAGE_APP, data_ = "01020304".decodeHex()),
        )

        assertTrue(fields.any { it.name == "data" })
        assertTrue(fields.none { it.name == "data_" })
    }

    @Test
    fun `поля идут в порядке объявления в схеме`() {
        val packet = MeshPacket(from = 1, rx_time = 1_700_000_000, rx_snr = 6.25f)

        val names = FieldWalker.rawFields(packet).map { it.name }

        assertEquals(listOf("from", "rx_time", "rx_snr"), names)
    }

    @Test
    fun `значения по умолчанию скрыты`() {
        // Без этого ModuleConfig покажет 140 нулей и утопит содержательное.
        val fields = FieldWalker.rawFields(MeshPacket(from = 0, to = 7, want_ack = false))

        assertEquals(listOf("to"), fields.map { it.name })
    }

    @Test
    fun `значения по умолчанию возвращаются по требованию`() {
        val fields = FieldWalker.rawFields(MeshPacket(to = 7), includeDefaults = true)

        assertTrue(fields.any { it.name == "from" })
        assertTrue(fields.any { it.name == "want_ack" })
    }

    @Test
    fun `перечисление в нулевом значении считается умолчанием`() {
        val fields = FieldWalker.rawFields(MyNodeInfo(my_node_num = 7))

        assertEquals(listOf("my_node_num"), fields.map { it.name })
    }

    @Test
    fun `неизвестные схеме поля видны`() {
        // FromRadio { id = 42; неизвестный тег 900 = 42 }. Непустое значение —
        // прямой признак, что прошивка ноды новее протобуфов приложения.
        val bytes = byteArrayOf(0x08, 0x2A, 0xA0.toByte(), 0x38, 0x2A)

        val frame = FromRadio.ADAPTER.decode(bytes)

        assertEquals(42, frame.id)
        assertTrue(FieldWalker.unknownFields(frame)!!.size > 0)
    }

    @Test
    fun `у чистого сообщения неизвестных полей нет`() {
        assertNull(FieldWalker.unknownFields(MyNodeInfo(my_node_num = 7)))
    }

    @Test
    fun `присутствующий ноль в поле с явным присутствием виден`() {
        // hops_away = 0 означает «нода в прямой видимости». Спрятать его как
        // умолчание — значит показать «неизвестно» вместо «напрямую».
        val fields = FieldWalker.rawFields(NodeInfo(num = 7, hops_away = 0))

        assertTrue(fields.any { it.name == "hops_away" && it.value == 0 })
    }

    @Test
    fun `отсутствующее поле с явным присутствием скрыто`() {
        val fields = FieldWalker.rawFields(NodeInfo(num = 7))

        assertEquals(listOf("num"), fields.map { it.name })
    }

    @Test
    fun `пустое повторяющееся поле скрыто`() {
        // У повторяющихся полей явного присутствия нет: пустой список и
        // отсутствие неотличимы, как и ноль у обычного скаляра.
        val fields = FieldWalker.rawFields(RouteDiscovery())

        assertTrue(fields.isEmpty())
    }
}
