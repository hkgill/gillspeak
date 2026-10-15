package dev.hkgill.gillspeak.wear

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Small, versioned, ephemeral messages. No prompts or answers are stored in DataItems. */
object Protocol {
    const val PHONE = "gillspeak_phone_v1"
    const val WATCH = "gillspeak_watch_v1"
    const val REQUEST_PATH = "/gillspeak/v1/request"
    const val REPLY_PATH = "/gillspeak/v1/reply"
    const val MAX_QUESTION = 1500
    const val MAX_TEXT = 6000
    const val MAX_PACKET = 25_000
    const val TIMEOUT_MS = 90_000L
    const val CONNECT_TIMEOUT_MS = 10_000L
    private const val MAGIC = 0x47535731
    enum class Kind { ASK, CANCEL, PING, STATUS, RESULT, ERROR }
    data class Packet(val id: String, val kind: Kind, val text: String = "", val createdAt: Long = System.currentTimeMillis())
    fun id(): String = UUID.randomUUID().toString()

    fun encode(p: Packet): ByteArray {
        validate(p)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeByte(1)
                out.writeUTF(p.id)
                out.writeByte(p.kind.ordinal)
                out.writeLong(p.createdAt)
                val text = p.text.toByteArray(Charsets.UTF_8)
                out.writeInt(text.size)
                out.write(text)
            }
        }.toByteArray().also { require(it.size <= MAX_PACKET) }
    }

    fun decode(bytes: ByteArray): Packet? = runCatching {
        require(bytes.size <= MAX_PACKET)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readUnsignedByte() == 1)
            val id = input.readUTF()
            val kind = Kind.entries[input.readUnsignedByte()]
            val created = input.readLong()
            val length = input.readInt()
            require(length in 0..MAX_PACKET && length == input.available())
            val body = ByteArray(length).also { input.readFully(it) }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString()
            Packet(id, kind, text, created).also(::validate)
        }
    }.getOrNull()

    fun fresh(p: Packet, now: Long): Boolean = p.createdAt >= now - TIMEOUT_MS && p.createdAt <= now + 60_000L

    private fun validate(p: Packet) {
        require(p.id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        require(p.createdAt > 0)
        require(p.text.length <= if (p.kind == Kind.ASK) MAX_QUESTION else MAX_TEXT)
        if (p.kind == Kind.ASK) require(p.text.isNotBlank())
        if (p.kind == Kind.CANCEL || p.kind == Kind.PING) require(p.text.isEmpty())
    }
}
