/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * Portions © 4nx3b — github.com/4nx3b
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Minimal hand-rolled codec for the two Spotify extended-metadata messages the
 * Echo resolver needs, following the wire schema published in
 * echo-spotify-extension (ext/src/main/proto/extendedmetadata.proto and
 * audio_files_extension.proto). Only the fields the resolver consumes are read.
 */

package moe.rukamori.archivetune.spotify.echo

import java.io.ByteArrayOutputStream

internal object SpotifyEchoProto {
    const val EXTENSION_KIND_AUDIO_FILES = 5
    const val EXTENSION_KIND_TRACK_V4 = 10

    const val FORMAT_OGG_VORBIS_96 = 0
    const val FORMAT_OGG_VORBIS_160 = 1
    const val FORMAT_OGG_VORBIS_320 = 2
    const val FORMAT_FLAC_FLAC = 16
    const val FORMAT_FLAC_FLAC_24BIT = 22

    class Reader(private val data: ByteArray, private var pos: Int = 0, private val limit: Int = data.size) {
        fun atEnd(): Boolean = pos >= limit

        fun readTag(): Long {
            val value = readVarint()
            return value
        }

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift <= 63) {
                if (pos >= limit) throw IllegalArgumentException("Truncated varint")
                val byte = data[pos++].toInt() and 0xFF
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
            throw IllegalArgumentException("Varint overflow")
        }

        fun readBytes(): ByteArray {
            val length = readVarint().toInt()
            if (length < 0 || pos + length > limit) throw IllegalArgumentException("Truncated bytes field")
            val out = data.copyOfRange(pos, pos + length)
            pos += length
            return out
        }

        fun readString(): String = String(readBytes(), Charsets.UTF_8)

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> pos += 8
                2 -> {
                    val length = readVarint().toInt()
                    pos += length
                }
                5 -> pos += 4
                else -> throw IllegalArgumentException("Unsupported wire type $wireType")
            }
            if (pos > limit) throw IllegalArgumentException("Skipped past end of message")
        }
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and -128L != 0L) {
            out.write(((v and 127L) or 128L).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun writeTag(out: ByteArrayOutputStream, field: Int, wireType: Int) = writeVarint(out, (field shl 3 or wireType).toLong())

    private fun writeBytesField(out: ByteArrayOutputStream, field: Int, bytes: ByteArray) {
        writeTag(out, field, 2)
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeStringField(out: ByteArrayOutputStream, field: Int, value: String) = writeBytesField(out, field, value.toByteArray(Charsets.UTF_8))

    private fun writeVarintField(out: ByteArrayOutputStream, field: Int, value: Long) {
        writeTag(out, field, 0)
        writeVarint(out, value)
    }

    fun buildExtendedMetadataRequest(entityUri: String, kinds: List<Int>): ByteArray {
        val query = ByteArrayOutputStream()
        for (kind in kinds) {
            writeTag(query, 2, 2)
            val extensionQuery = ByteArrayOutputStream()
            writeVarintField(extensionQuery, 1, kind.toLong())
            writeVarint(query, extensionQuery.size().toLong())
            query.write(extensionQuery.toByteArray())
        }

        val entityRequest = ByteArrayOutputStream()
        writeStringField(entityRequest, 1, entityUri)
        entityRequest.write(query.toByteArray())

        val request = ByteArrayOutputStream()
        writeTag(request, 1, 2)
        writeVarint(request, 0)
        writeTag(request, 2, 2)
        writeVarint(request, entityRequest.size().toLong())
        request.write(entityRequest.toByteArray())
        return request.toByteArray()
    }

    class AudioFileEntry(
        val fileIdHex: String,
        val format: Int,
    )

    class TrackData(
        val gidHex: String,
        val files: List<AudioFileEntry>,
        val alternativeFiles: List<AudioFileEntry>,
        val restrictionCount: Int,
    )

    fun parseTrack(bytes: ByteArray): TrackData {
        val reader = Reader(bytes)
        var gidHex = ""
        val files = mutableListOf<AudioFileEntry>()
        val alternativeFiles = mutableListOf<AudioFileEntry>()
        var restrictionCount = 0
        while (!reader.atEnd()) {
            val tag = reader.readTag()
            val field = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                2 -> {
                    val payload = reader.readBytes()
                    when (field) {
                        1 -> gidHex = payload.toHex()
                        11 -> restrictionCount++
                        12 -> files += parseAudioFile(payload)
                        13 -> alternativeFiles += parseTrackFilesOnly(payload)
                    }
                }
                else -> reader.skip((tag and 7).toInt())
            }
        }
        return TrackData(gidHex, files, alternativeFiles, restrictionCount)
    }

    private fun parseTrackFilesOnly(bytes: ByteArray): List<AudioFileEntry> {
        val reader = Reader(bytes)
        val files = mutableListOf<AudioFileEntry>()
        while (!reader.atEnd()) {
            val tag = reader.readTag()
            val field = (tag ushr 3).toInt()
            if (field == 12 && (tag and 7).toInt() == 2) {
                files += parseAudioFile(reader.readBytes())
            } else {
                reader.skip((tag and 7).toInt())
            }
        }
        return files
    }

    private fun parseAudioFile(bytes: ByteArray): AudioFileEntry {
        val reader = Reader(bytes)
        var fileIdHex = ""
        var format = -1
        while (!reader.atEnd()) {
            val tag = reader.readTag()
            val field = (tag ushr 3).toInt()
            when (field) {
                1 -> if ((tag and 7).toInt() == 2) fileIdHex = reader.readBytes().toHex() else reader.skip((tag and 7).toInt())
                2 -> if ((tag and 7).toInt() == 0) format = reader.readVarint().toInt() else reader.skip((tag and 7).toInt())
                else -> reader.skip((tag and 7).toInt())
            }
        }
        return AudioFileEntry(fileIdHex, format)
    }

    fun parseAudioFilesExtension(bytes: ByteArray): List<AudioFileEntry> {
        val reader = Reader(bytes)
        val files = mutableListOf<AudioFileEntry>()
        while (!reader.atEnd()) {
            val tag = reader.readTag()
            val field = (tag ushr 3).toInt()
            if (field == 1 && (tag and 7).toInt() == 2) {
                val extended = Reader(reader.readBytes())
                while (!extended.atEnd()) {
                    val inner = extended.readTag()
                    if ((inner ushr 3).toInt() == 1 && (inner and 7).toInt() == 2) {
                        files += parseAudioFile(extended.readBytes())
                    } else {
                        extended.skip((inner and 7).toInt())
                    }
                }
            } else {
                reader.skip((tag and 7).toInt())
            }
        }
        return files
    }

    class ExtensionPayload(
        val kind: Int,
        val data: ByteArray,
    )

    fun parseBatchedExtensionResponse(bytes: ByteArray): List<ExtensionPayload> {
        val reader = Reader(bytes)
        val payloads = mutableListOf<ExtensionPayload>()
        while (!reader.atEnd()) {
            val tag = reader.readTag()
            val field = (tag ushr 3).toInt()
            if (field == 2 && (tag and 7).toInt() == 2) {
                val array = Reader(reader.readBytes())
                var kind = 0
                while (!array.atEnd()) {
                    val arrayTag = array.readTag()
                    val arrayField = (arrayTag ushr 3).toInt()
                    when {
                        arrayField == 2 && (arrayTag and 7).toInt() == 0 -> kind = array.readVarint().toInt()
                        arrayField == 3 && (arrayTag and 7).toInt() == 2 -> payloads += parseEntityExtensionData(array.readBytes(), kind)
                        else -> array.skip((arrayTag and 7).toInt())
                    }
                }
            } else {
                reader.skip((tag and 7).toInt())
            }
        }
        return payloads
    }

    private fun parseEntityExtensionData(bytes: ByteArray, kind: Int): ExtensionPayload {
        val reader = Reader(bytes)
        var payload: ByteArray? = null
        while (!reader.atEnd()) {
            val tag = reader.readTag()
            val field = (tag ushr 3).toInt()
            if (field == 3 && (tag and 7).toInt() == 2) {
                val any = Reader(reader.readBytes())
                while (!any.atEnd()) {
                    val anyTag = any.readTag()
                    if ((anyTag ushr 3).toInt() == 2 && (anyTag and 7).toInt() == 2) {
                        payload = any.readBytes()
                    } else {
                        any.skip((anyTag and 7).toInt())
                    }
                }
            } else {
                reader.skip((tag and 7).toInt())
            }
        }
        return ExtensionPayload(kind, payload ?: ByteArray(0))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
