package uk.co.learnerassist.core

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.SocketTimeoutException

class ProtocolTest {
    @Test fun `diagnostic allowlist contains exactly four Mode 01 PIDs`() {
        val permitted = setOf("0100", "0104", "010C", "010D")
        for (value in 0..65535) {
            val wire = "%04X".format(value)
            assertEquals(wire, wire in permitted, CommandPolicy.allows(wire))
        }
    }
    @Test fun `unsafe adapter commands and injection are blocked`() {
        listOf("04", "0400", "ATZ", "ATWS", "ATSP0", "ATPP00ON", "ATSH7E0", "2701", "2E123400", "3101", "1101",
            "010C\r04", "010C\n04", "010C ", "01 0C", "010c", "", ";010C").forEach { assertFalse(it, CommandPolicy.allows(it)) }
    }
    @Test fun `typed transport has no string transmission method`() {
        val request = DiagnosticSession::class.java.methods.single { it.name == "request" }
        assertEquals(listOf(SafeCommand::class.java), request.parameterTypes.toList())
        SafeCommand.entries.forEach { assertTrue(CommandPolicy.allows(it.wire)) }
    }
    @Test fun `standard values decode correctly`() {
        assertEquals(1726.0, ObdDecoder.value(SafeCommand.RPM, "41 0C 1A F8")!!, 0.001)
        assertEquals(48.0, ObdDecoder.value(SafeCommand.SPEED, "410D30")!!, 0.001)
        assertEquals(100.0, ObdDecoder.value(SafeCommand.LOAD, "41 04 FF")!!, 0.001)
        assertEquals(29.8258, Reading(1000.0, 48.0, null, 0, Source.LIVE).mph!!, 0.001)
    }
    @Test fun `echo searching whitespace and agreeing ECUs decode`() {
        assertEquals(1726.0, ObdDecoder.value(SafeCommand.RPM, "010C\rSEARCHING...\r41 0C 1A F8\r41 0C 1A F8\r>")!!, 0.001)
    }
    @Test fun `unsupported PID returns no reading`() { assertNull(ObdDecoder.value(SafeCommand.LOAD, "NO DATA\r>")) }
    @Test fun `support bitmap discovers only approved sensors`() {
        assertEquals(setOf(SafeCommand.RPM, SafeCommand.SPEED, SafeCommand.LOAD), ObdDecoder.supported("41 00 18 18 00 00"))
        assertTrue(ObdDecoder.supported("41 00 00 00 00 00").isEmpty())
    }
    @Test fun `malformed mismatched negative and ambiguous replies fail closed`() {
        listOf("", "OK", "?", "UNABLE TO CONNECT", "STOPPED", "7F 01 12", "41 0D 20", "41 0C FF", "41 0C GG FF",
            "41 0C 00 01 FF", "7E8 04 41 0C 1A F8", "41 0C 1A F8\r41 0C 1B F8").forEach { raw ->
            assertThrows(raw, ProtocolException::class.java) { ObdDecoder.value(SafeCommand.RPM, raw) }
        }
    }
    @Test fun `partial input reads continue to prompt with one terminated command`() {
        val output = ByteArrayOutputStream()
        val input = ByteArrayInputStream("41 0C 1A F8\r>41 0D 30\r>".toByteArray())
        val session = ElmSession(input, output, {})
        assertEquals("41 0C 1A F8\r", session.request(SafeCommand.RPM))
        assertEquals("41 0D 30\r", session.request(SafeCommand.SPEED))
        assertEquals("010C\r010D\r", output.toString("US-ASCII"))
    }
    @Test fun `disconnect closes poisoned session`() {
        var closed = false
        val session = ElmSession(ByteArrayInputStream("41 0C".toByteArray()), ByteArrayOutputStream(), { closed = true })
        assertThrows(EOFException::class.java) { session.request(SafeCommand.RPM) }
        assertTrue(closed)
        assertThrows(IllegalStateException::class.java) { session.request(SafeCommand.SPEED) }
    }
    @Test fun `timeout and oversize responses close transport`() {
        var closed = false
        val input = object : InputStream() { override fun read(): Int = throw SocketTimeoutException("simulated") }
        assertThrows(SocketTimeoutException::class.java) { ElmSession(input, ByteArrayOutputStream(), { closed = true }).request(SafeCommand.RPM) }
        assertTrue(closed)
        assertThrows(ProtocolException::class.java) {
            ElmSession(ByteArrayInputStream("0".repeat(4097).toByteArray()), ByteArrayOutputStream(), {}).request(SafeCommand.RPM)
        }
    }
    @Test fun `slow trickle has an overall deadline`() {
        var time = 0L
        assertThrows(SocketTimeoutException::class.java) {
            ElmSession(ByteArrayInputStream("41 0C 1A F8>".toByteArray()), ByteArrayOutputStream(), {}, { time += 5_000_000_000; time }).request(SafeCommand.RPM)
        }
    }
    private class FakeSession(private val load: Boolean = true) : DiagnosticSession {
        val requests = mutableListOf<SafeCommand>()
        override fun request(command: SafeCommand): String {
            requests += command
            return when (command) {
                SafeCommand.IDENTITY -> "ELM327 v1.5"
                SafeCommand.SUPPORTED -> if (load) "41 00 18 18 00 00" else "41 00 00 18 00 00"
                SafeCommand.RPM -> "41 0C 1A F8"
                SafeCommand.SPEED -> "41 0D 30"
                SafeCommand.LOAD -> "41 04 80"
                else -> "OK"
            }
        }
        override fun close() = Unit
    }
    @Test fun `monitor simulation uses only allowlisted traffic`() {
        val fake = FakeSession()
        val monitor = ReadOnlyMonitor(fake)
        monitor.initialize()
        val reading = monitor.read({ 1000 }, {})
        assertEquals(1726.0, reading.rpm!!, 0.001)
        assertTrue(fake.requests.all { CommandPolicy.allows(it.wire) })
        assertFalse(fake.requests.any { it.wire == "04" })
    }
    @Test fun `optional load is never polled when unsupported`() {
        val fake = FakeSession(false)
        val monitor = ReadOnlyMonitor(fake)
        monitor.initialize()
        assertNull(monitor.read({ 1000 }, {}).load)
        assertFalse(SafeCommand.LOAD in fake.requests)
    }
    @Test fun `long polling cycle is marked unsynchronized`() {
        val monitor = ReadOnlyMonitor(FakeSession()).also { it.initialize() }
        var clock = 0L
        assertFalse(monitor.read({ clock += 2000; clock }, {}).synchronized)
    }
    @Test fun `unknown adapter or rejected setup fails before PID polling`() {
        val requests = mutableListOf<SafeCommand>()
        val fake = object : DiagnosticSession {
            override fun request(command: SafeCommand): String { requests += command; return "?" }
            override fun close() = Unit
        }
        assertThrows(ProtocolException::class.java) { ReadOnlyMonitor(fake).initialize() }
        assertEquals(listOf(SafeCommand.IDENTITY), requests)
    }
}
