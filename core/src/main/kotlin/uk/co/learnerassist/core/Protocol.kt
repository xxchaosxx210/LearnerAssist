package uk.co.learnerassist.core

import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException

/** Closed vocabulary. No user-defined commands or persistent adapter changes. */
enum class SafeCommand(val wire: String, val pid: Int? = null, val bytes: Int = 0) {
    SUPPORTED("0100", 0, 4), RPM("010C", 12, 2), SPEED("010D", 13, 1), LOAD("0104", 4, 1),
    IDENTITY("ATI"), ECHO_OFF("ATE0"), LINEFEEDS_OFF("ATL0"), HEADERS_OFF("ATH0"),
    // TP tries a protocol without saving it to EEPROM (unlike SP).
    TRY_AUTO_PROTOCOL("ATTP0")
}

object CommandPolicy {
    private val approved = setOf("0100", "010C", "010D", "0104", "ATI", "ATE0", "ATL0", "ATH0", "ATTP0")
    fun allows(wire: String): Boolean = wire in approved
    fun encode(command: SafeCommand): ByteArray {
        check(allows(command.wire)) { "Command blocked by read-only policy" }
        return (command.wire + "\r").toByteArray(Charsets.US_ASCII)
    }
}

/** A typed interface is the only transmission API exposed to features. */
interface DiagnosticSession : Closeable { fun request(command: SafeCommand): String }

/** Owns prompt framing across fragmented TCP reads. A failed exchange poisons the session. */
class ElmSession(
    private val input: InputStream,
    private val output: OutputStream,
    private val closeTransport: () -> Unit,
    private val now: () -> Long = System::nanoTime,
) : DiagnosticSession {
    private var closed = false
    @Synchronized override fun request(command: SafeCommand): String {
        check(!closed) { "Session closed" }
        val payload = CommandPolicy.encode(command)
        try {
            output.write(payload)
            output.flush()
            val response = StringBuilder()
            val deadline = now() + 8_000_000_000L
            while (response.length < 4096) {
                if (now() > deadline) throw SocketTimeoutException("ELM prompt deadline exceeded")
                val next = input.read()
                if (next == -1) throw EOFException("Adapter disconnected before prompt")
                if (next == '>'.code) return response.toString()
                if (next !in 9..126) throw ProtocolException("Non-ASCII adapter response")
                response.append(next.toChar())
            }
            throw ProtocolException("Adapter response too large")
        } catch (error: Exception) {
            close()
            throw error
        }
    }
    override fun close() { if (!closed) { closed = true; closeTransport() } }
}

class ProtocolException(message: String) : java.io.IOException(message)

object ObdDecoder {
    /** Multiple ECUs must agree. Headers are deliberately disabled before polling. */
    fun bytes(command: SafeCommand, raw: String): List<Int>? {
        require(command.pid != null)
        val lines = raw.uppercase().replace(">", "").split('\r', '\n').map { it.trim() }
            .filter { it.isNotEmpty() && it.replace(" ", "") != command.wire && it != "SEARCHING..." }
        if (lines.size == 1 && lines.single() == "NO DATA") return null
        if (lines.isEmpty()) throw ProtocolException("Empty response")
        val values = lines.map { line ->
            val compact = line.filterNot { it.isWhitespace() }
            if (!compact.matches(Regex("[0-9A-F]+")) || compact.length != (command.bytes + 2) * 2)
                throw ProtocolException("Unexpected response format for ${command.wire}")
            val bytes = compact.chunked(2).map { it.toInt(16) }
            if (bytes[0] != 0x41 || bytes[1] != command.pid) throw ProtocolException("Response PID mismatch")
            bytes.drop(2)
        }
        if (values.distinct().size != 1) throw ProtocolException("Ambiguous ECU responses")
        return values.first()
    }
    fun value(command: SafeCommand, raw: String): Double? {
        val bytes = bytes(command, raw) ?: return null
        return when (command) {
            SafeCommand.RPM -> (bytes[0] * 256 + bytes[1]) / 4.0
            SafeCommand.SPEED -> bytes[0].toDouble()
            SafeCommand.LOAD -> bytes[0] * 100.0 / 255
            else -> throw IllegalArgumentException("Not a scalar PID")
        }
    }
    fun supported(raw: String): Set<SafeCommand> {
        val bytes = bytes(SafeCommand.SUPPORTED, raw) ?: throw ProtocolException("PID support unavailable")
        val mask = bytes.fold(0L) { result, byte -> (result shl 8) or byte.toLong() }
        return listOf(SafeCommand.RPM, SafeCommand.SPEED, SafeCommand.LOAD).filter {
            mask and (1L shl (32 - it.pid!!)) != 0L
        }.toSet()
    }
}

class ReadOnlyMonitor(private val session: DiagnosticSession) {
    var identity = "Unverified"
        private set
    private var supported = emptySet<SafeCommand>()
    fun initialize() {
        identity = session.request(SafeCommand.IDENTITY).trim().take(120)
        if (!identity.contains("ELM", ignoreCase = true)) throw ProtocolException("ELM-compatible identity not confirmed")
        listOf(SafeCommand.ECHO_OFF, SafeCommand.LINEFEEDS_OFF, SafeCommand.HEADERS_OFF, SafeCommand.TRY_AUTO_PROTOCOL).forEach {
            val reply = session.request(it).replace(it.wire, "", ignoreCase = true).trim()
            if (reply != "OK") throw ProtocolException("Adapter rejected ${it.wire}")
        }
        supported = ObdDecoder.supported(session.request(SafeCommand.SUPPORTED))
        if (!supported.containsAll(setOf(SafeCommand.RPM, SafeCommand.SPEED)))
            throw ProtocolException("Required RPM/speed PIDs unsupported")
    }
    fun read(nowMs: () -> Long, pause: () -> Unit): Reading {
        fun read(command: SafeCommand): Double? {
            if (command !in supported) return null
            val result = ObdDecoder.value(command, session.request(command))
            pause()
            return result
        }
        val started = nowMs()
        val rpm = read(SafeCommand.RPM)
        val speed = read(SafeCommand.SPEED)
        val load = read(SafeCommand.LOAD)
        val ended = nowMs()
        return Reading(rpm, speed, load, started, Source.LIVE, synchronized = ended - started <= 1500)
    }
}
