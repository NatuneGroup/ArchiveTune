/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Minimal protobuf wire writer/reader for the Listen Together protocol.
 * The generated protobuf classes this protocol used to rely on are gone: protobuf-gradle-plugin
 * cannot apply to this project's AGP 9 (it casts the app extension to the removed BaseExtension),
 * and a pre-generated copy collided with the protobuf-java runtime another dependency already ships
 * (same `com.google.protobuf` package). The wire format itself is simple — proto3 with only
 * string/int64/bool/float/bytes, nested messages and repeated messages/strings, no oneof, no maps,
 * no enums — so encoding it here keeps app/src/main/proto/listentogether.proto as the single source
 * of truth with no runtime dependency at all.
 * proto3 rules implemented: default-valued scalars are not written (0, false, ""), message fields
 * are length-delimited and their presence is tracked on read, repeated fields repeat their tag, and
 * unknown fields are skipped by wire type so a newer server cannot break an older client.
 */



package moe.rukamori.archivetune.listentogether

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Protobuf wire types. */
internal object WireType {
    const val VARINT = 0
    const val FIXED64 = 1
    const val LENGTH_DELIMITED = 2
    const val FIXED32 = 5
}

/** Appends protobuf fields to a growable buffer. */
internal class ProtoWriter(initialCapacity: Int = 64) {
    private val out = java.io.ByteArrayOutputStream(initialCapacity)

    val size: Int get() = out.size()

    fun toByteArray(): ByteArray = out.toByteArray()

    /** Writes `string` at [field], skipping the empty string exactly as proto3 does. */
    fun writeString(field: Int, value: String?) {
        if (value.isNullOrEmpty()) return
        writeTag(field, WireType.LENGTH_DELIMITED)
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarint(bytes.size.toLong())
        out.write(bytes)
    }

    /** Writes `bytes` at [field]. An empty array is still written (messages are explicit). */
    fun writeBytes(field: Int, value: ByteArray) {
        writeTag(field, WireType.LENGTH_DELIMITED)
        writeVarint(value.size.toLong())
        out.write(value)
    }

    /** Writes `int64` at [field], skipping zero exactly as proto3 does. */
    fun writeInt64(field: Int, value: Long) {
        if (value == 0L) return
        writeTag(field, WireType.VARINT)
        writeVarint(value)
    }

    /** Writes `bool` at [field], skipping false exactly as proto3 does. */
    fun writeBool(field: Int, value: Boolean) {
        if (!value) return
        writeTag(field, WireType.VARINT)
        writeVarint(1L)
    }

    /** Writes `float` at [field], skipping zero exactly as proto3 does. */
    fun writeFloat(field: Int, value: Float) {
        if (value == 0f) return
        writeTag(field, WireType.FIXED32)
        val raw = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
        out.write(raw)
    }

    /** Writes a nested message at [field]. [build] receives a fresh writer for the submessage. */
    fun writeMessage(field: Int, build: (ProtoWriter) -> Unit) {
        val nested = ProtoWriter()
        build(nested)
        writeTag(field, WireType.LENGTH_DELIMITED)
        val bytes = nested.toByteArray()
        writeVarint(bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeTag(field: Int, wireType: Int) {
        writeVarint(((field shl 3) or wireType).toLong())
    }

    private fun writeVarint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }
}

/**
 * Reads protobuf fields from a byte array.
 *
 * Callers walk the message with [nextField] and switch on the returned field number, reading the
 * value that matches the field's wire type. Unknown fields are consumed by [skip], which keeps the
 * reader aligned so a server that adds fields stays readable.
 */
internal class ProtoReader(
    private val data: ByteArray,
    private val start: Int = 0,
    private val end: Int = data.size,
) {
    private var pos = start

    /** The wire type of the field [nextField] last returned, for [skip]. */
    var lastWireType: Int = -1
        private set

    fun hasMore(): Boolean = pos < end

    /** Reads the next tag; returns -1 at the end of the message. */
    fun nextField(): Int {
        if (!hasMore()) return -1
        val tag = readVarint()
        val field = (tag ushr 3).toInt()
        lastWireType = (tag and 0x7).toInt()
        return field
    }

    fun readString(): String {
        val length = readVarint().toInt()
        val bytes = slice(length)
        return String(bytes, Charsets.UTF_8)
    }

    fun readBytes(): ByteArray {
        val length = readVarint().toInt()
        return slice(length)
    }

    fun readInt64(): Long = readVarint()

    fun readBool(): Boolean = readVarint() != 0L

    fun readFloat(): Float {
        requireAvailable(4)
        val value = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN).float
        pos += 4
        return value
    }

    /** Reads a nested message into a reader bounded to its own bytes. */
    fun readMessage(): ProtoReader {
        val length = readVarint().toInt()
        requireAvailable(length)
        val messageStart = pos
        pos += length
        return ProtoReader(data, messageStart, messageStart + length)
    }

    /** Consumes the current field's value using its wire type. */
    fun skip() {
        when (lastWireType) {
            WireType.VARINT -> readVarint()
            WireType.FIXED64 -> {
                requireAvailable(8)
                pos += 8
            }
            WireType.LENGTH_DELIMITED -> {
                val length = readVarint().toInt()
                requireAvailable(length)
                pos += length
            }
            WireType.FIXED32 -> {
                requireAvailable(4)
                pos += 4
            }
            else -> throw IllegalArgumentException("Unsupported protobuf wire type ${lastWireType}")
        }
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            requireAvailable(1)
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("Malformed varint")
    }

    private fun slice(length: Int): ByteArray {
        requireAvailable(length)
        val copy = data.copyOfRange(pos, pos + length)
        pos += length
        return copy
    }

    private fun requireAvailable(count: Int) {
        if (pos + count > end) throw IllegalArgumentException("Truncated protobuf message")
    }
}
