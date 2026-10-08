/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Wire-format tests for the hand-rolled Listen Together protobuf layer. These pin the encoding
 * against app/src/main/proto/listentogether.proto so a future edit to ProtoWire/MessageCodec cannot
 * silently change what the servers receive.
 */



package moe.rukamori.archivetune.listentogether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtoWireTest {
    @Test
    fun `string field encodes as tag length bytes`() {
        val writer = ProtoWriter()
        writer.writeString(1, "a")
        assertEquals(listOf(0x0A, 0x01, 0x61), writer.toByteArray().map { it.toInt() and 0xFF })
    }

    @Test
    fun `int64 field encodes as varint`() {
        val writer = ProtoWriter()
        writer.writeInt64(5, 300)
        assertEquals(listOf(0x28, 0xAC, 0x02), writer.toByteArray().map { it.toInt() and 0xFF })
    }

    @Test
    fun `default valued scalars are omitted like proto3`() {
        val writer = ProtoWriter()
        writer.writeString(1, "")
        writer.writeInt64(2, 0)
        writer.writeBool(3, false)
        writer.writeFloat(4, 0f)
        assertEquals(0, writer.size)
    }

    @Test
    fun `float field is little endian fixed32`() {
        val writer = ProtoWriter()
        writer.writeFloat(8, 1.0f)
        assertEquals(
            listOf(0x45, 0x00, 0x00, 0x80, 0x3F),
            writer.toByteArray().map { it.toInt() and 0xFF }
        )
    }

    @Test
    fun `reader round trips every scalar type`() {
        val writer = ProtoWriter()
        writer.writeString(1, "hello")
        writer.writeInt64(2, 1234567890123L)
        writer.writeBool(3, true)
        writer.writeFloat(4, 0.5f)
        writer.writeMessage(5) { it.writeString(1, "nested") }

        val reader = ProtoReader(writer.toByteArray())
        assertEquals(1, reader.nextField())
        assertEquals("hello", reader.readString())
        assertEquals(2, reader.nextField())
        assertEquals(1234567890123L, reader.readInt64())
        assertEquals(3, reader.nextField())
        assertTrue(reader.readBool())
        assertEquals(4, reader.nextField())
        assertEquals(0.5f, reader.readFloat(), 0f)
        assertEquals(5, reader.nextField())
        val nested = reader.readMessage()
        assertEquals(1, nested.nextField())
        assertEquals("nested", nested.readString())
        assertEquals(-1, reader.nextField())
    }

    @Test
    fun `unknown fields are skipped and known ones still parse`() {
        val writer = ProtoWriter()
        writer.writeString(1, "first")
        writer.writeString(99, "added by a newer server")
        writer.writeInt64(100, 42)
        writer.writeString(2, "second")

        val reader = ProtoReader(writer.toByteArray())
        var first = ""
        var second = ""
        while (true) {
            when (val field = reader.nextField()) {
                -1 -> break
                1 -> first = reader.readString()
                2 -> second = reader.readString()
                else -> reader.skip()
            }
        }
        assertEquals("first", first)
        assertEquals("second", second)
    }

    @Test
    fun `empty nested message still marks the field present`() {
        val writer = ProtoWriter()
        writer.writeMessage(4) { /* nothing: TrackInfo with all defaults */ }
        val reader = ProtoReader(writer.toByteArray())
        assertEquals(4, reader.nextField())
        val nested = reader.readMessage()
        assertEquals(-1, nested.nextField())
    }

    @Test
    fun `create room payload round trips through the codec`() {
        val codec = MessageCodec(format = MessageFormat.PROTOBUF)
        val encoded = codec.encode(MessageTypes.CREATE_ROOM, CreateRoomPayload("alice"))

        val (type, payload) = codec.decode(encoded)
        assertEquals(MessageTypes.CREATE_ROOM, type)

        val decoded = codec.decodePayload(type, payload, MessageFormat.PROTOBUF)
        assertEquals(CreateRoomPayload("alice"), decoded)
    }

    @Test
    fun `playback action payload round trips with every field`() {
        val codec = MessageCodec(format = MessageFormat.PROTOBUF)
        val payload = PlaybackActionPayload(
            action = PlaybackActions.PLAY,
            trackId = "track-1",
            position = 1234L,
            trackInfo = TrackInfo(
                id = "track-1",
                title = "Song",
                artist = "Artist",
                album = "Album",
                duration = 210_000L,
                thumbnail = "https://example.test/a.jpg",
                suggestedBy = "bob"
            ),
            insertNext = true,
            queue = listOf(TrackInfo(id = "q1", title = "Queued", artist = "A", duration = 1000L)),
            queueTitle = "Up next",
            volume = 0.5f,
            serverTime = 9999L
        )

        val encoded = codec.encode(MessageTypes.PLAYBACK_ACTION, payload)
        val (type, payloadBytes) = codec.decode(encoded)
        val decoded = codec.decodePayload(type, payloadBytes, MessageFormat.PROTOBUF) as PlaybackActionPayload

        assertEquals(payload, decoded)
    }

    @Test
    fun `suggest track payload keeps an all defaults track present`() {
        val codec = MessageCodec(format = MessageFormat.PROTOBUF)
        val encoded = codec.encode(
            MessageTypes.SUGGEST_TRACK,
            SuggestTrackPayload(TrackInfo(id = "", title = "", artist = "", duration = 0L))
        )
        val (type, payloadBytes) = codec.decode(encoded)
        val decoded = codec.decodePayload(type, payloadBytes, MessageFormat.PROTOBUF) as SuggestTrackPayload
        assertEquals("", decoded.trackInfo.id)
    }

    @Test
    fun `server sync state decodes into the domain model`() {
        // Bytes a server would send for SYNC_STATE: current_track(1) message, is_playing(2), position(3),
        // last_update(4), queue(5) repeated, volume(6). Field numbers from listentogether.proto.
        val writer = ProtoWriter()
        writer.writeMessage(1) { track ->
            track.writeString(1, "track-9")
            track.writeString(2, "Song")
            track.writeString(3, "Artist")
            track.writeInt64(5, 180_000L)
        }
        writer.writeBool(2, true)
        writer.writeInt64(3, 4200L)
        writer.writeInt64(4, 1_700_000_000_000L)
        writer.writeMessage(5) { track ->
            track.writeString(1, "queued-1")
            track.writeString(2, "Next")
            track.writeString(3, "Someone")
            track.writeInt64(5, 90_000L)
        }
        writer.writeFloat(6, 0.8f)

        val codec = MessageCodec(format = MessageFormat.PROTOBUF)
        val decoded = codec.decodePayload(
            MessageTypes.SYNC_STATE,
            writer.toByteArray(),
            MessageFormat.PROTOBUF
        ) as SyncStatePayload

        assertEquals("track-9", decoded.currentTrack?.id)
        assertEquals(180_000L, decoded.currentTrack?.duration)
        assertTrue(decoded.isPlaying)
        assertEquals(4200L, decoded.position)
        assertEquals(1_700_000_000_000L, decoded.lastUpdate)
        assertEquals(listOf("queued-1"), decoded.queue?.map { it.id })
        assertEquals(0.8f, decoded.volume ?: 0f, 0f)
    }

    @Test
    fun `room state decodes users and tracks`() {
        val codec = MessageCodec(format = MessageFormat.PROTOBUF)
        // JOIN_APPROVED carries RoomState at field 4.
        val joinApproved = ProtoWriter()
        joinApproved.writeString(1, "ROOM")
        joinApproved.writeString(2, "guest-1")
        joinApproved.writeString(3, "token")
        joinApproved.writeMessage(4) { state ->
            state.writeString(1, "ROOM")
            state.writeString(2, "host-1")
            state.writeMessage(3) { user ->
                user.writeString(1, "host-1")
                user.writeString(2, "alice")
                user.writeBool(3, true)
                user.writeBool(4, true)
            }
            state.writeMessage(4) { track ->
                track.writeString(1, "t-1")
                track.writeString(2, "Song")
                track.writeString(3, "Artist")
                track.writeInt64(5, 1000L)
            }
            state.writeBool(5, true)
            state.writeInt64(6, 2000L)
            state.writeInt64(7, 3L)
        }

        val decoded = codec.decodePayload(
            MessageTypes.JOIN_APPROVED,
            joinApproved.toByteArray(),
            MessageFormat.PROTOBUF
        ) as JoinApprovedPayload

        assertEquals("ROOM", decoded.roomCode)
        assertEquals("token", decoded.sessionToken)
        assertEquals("host-1", decoded.state.hostId)
        assertEquals(listOf("alice"), decoded.state.users.map { it.username })
        assertEquals("t-1", decoded.state.currentTrack?.id)
        assertEquals(2000L, decoded.state.position)
    }

    @Test
    fun `no payload decodes as null`() {
        val codec = MessageCodec(format = MessageFormat.PROTOBUF)
        assertNull(codec.decodePayload(MessageTypes.BUFFER_READY, ByteArray(0), MessageFormat.PROTOBUF))
    }

    @Test
    fun `envelope round trips compression flag`() {
        val codec = MessageCodec(format = MessageFormat.PROTOBUF)
        val (type, payload) = codec.decode(codec.encode(MessageTypes.BUFFER_READY, BufferReadyPayload("t-7")))
        assertEquals(MessageTypes.BUFFER_READY, type)
        assertEquals(BufferReadyPayload("t-7"), codec.decodePayload(type, payload, MessageFormat.PROTOBUF))
    }
}
