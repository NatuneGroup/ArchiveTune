/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Listen Together message codec — ported from vivi-music (beta branch),
 * vivi-music's listentogether.MessageCodec (GPL-3.0).
 * The protobuf path is hand-encoded through [ProtoWriter]/[ProtoReader] against the field numbers in
 * app/src/main/proto/listentogether.proto (which remains the source of truth). See ProtoWire.kt for
 * why the generated classes were dropped: neither protobuf-gradle-plugin (AGP 9) nor a pre-generated
 * copy (a `com.google.protobuf` runtime clash) is usable in this build.
 */



package moe.rukamori.archivetune.listentogether

import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Message format for encoding/decoding
 */
enum class MessageFormat {
    JSON,      // DEPRECATED - will be removed in future versions
    PROTOBUF
}

/**
 * Codec for encoding and decoding messages in different formats
 */
class MessageCodec(
    var format: MessageFormat = MessageFormat.JSON,
    var compressionEnabled: Boolean = false
) {
    companion object {
        private const val TAG = "MessageCodec"
        private const val COMPRESSION_THRESHOLD = 100 // Only compress if > 100 bytes

        // Field numbers, straight from app/src/main/proto/listentogether.proto.
        const val FIELD_ENVELOPE_TYPE = 1
        const val FIELD_ENVELOPE_PAYLOAD = 2
        const val FIELD_ENVELOPE_COMPRESSED = 3

        const val TRACK_INFO_ID = 1
        const val TRACK_INFO_TITLE = 2
        const val TRACK_INFO_ARTIST = 3
        const val TRACK_INFO_ALBUM = 4
        const val TRACK_INFO_DURATION = 5
        const val TRACK_INFO_THUMBNAIL = 6
        const val TRACK_INFO_SUGGESTED_BY = 7

        const val USER_INFO_USER_ID = 1
        const val USER_INFO_USERNAME = 2
        const val USER_INFO_IS_HOST = 3
        const val USER_INFO_IS_CONNECTED = 4

        const val ROOM_STATE_ROOM_CODE = 1
        const val ROOM_STATE_HOST_ID = 2
        const val ROOM_STATE_USERS = 3
        const val ROOM_STATE_CURRENT_TRACK = 4
        const val ROOM_STATE_IS_PLAYING = 5
        const val ROOM_STATE_POSITION = 6
        const val ROOM_STATE_LAST_UPDATE = 7
        const val ROOM_STATE_VOLUME = 8
        const val ROOM_STATE_QUEUE = 9

        const val FIELD_CREATE_ROOM_USERNAME = 1

        const val FIELD_JOIN_ROOM_CODE = 1
        const val FIELD_JOIN_ROOM_USERNAME = 2

        const val FIELD_APPROVE_JOIN_USER_ID = 1

        const val FIELD_REJECT_JOIN_USER_ID = 1
        const val FIELD_REJECT_JOIN_REASON = 2

        const val PLAYBACK_ACTION = 1
        const val PLAYBACK_TRACK_ID = 2
        const val PLAYBACK_POSITION = 3
        const val PLAYBACK_TRACK_INFO = 4
        const val PLAYBACK_INSERT_NEXT = 5
        const val PLAYBACK_QUEUE = 6
        const val PLAYBACK_QUEUE_TITLE = 7
        const val PLAYBACK_VOLUME = 8
        const val PLAYBACK_SERVER_TIME = 9

        const val FIELD_BUFFER_READY_TRACK_ID = 1

        const val FIELD_KICK_USER_ID = 1
        const val FIELD_KICK_REASON = 2

        const val FIELD_SUGGEST_TRACK_INFO = 1

        const val FIELD_APPROVE_SUGGESTION_ID = 1

        const val FIELD_REJECT_SUGGESTION_ID = 1
        const val FIELD_REJECT_SUGGESTION_REASON = 2

        const val FIELD_RECONNECT_SESSION_TOKEN = 1

        const val FIELD_TRANSFER_HOST_NEW_HOST_ID = 1

        const val ROOM_CREATED_ROOM_CODE = 1
        const val ROOM_CREATED_USER_ID = 2
        const val ROOM_CREATED_SESSION_TOKEN = 3

        const val JOIN_REQUEST_USER_ID = 1
        const val JOIN_REQUEST_USERNAME = 2

        const val JOIN_APPROVED_ROOM_CODE = 1
        const val JOIN_APPROVED_USER_ID = 2
        const val JOIN_APPROVED_SESSION_TOKEN = 3
        const val JOIN_APPROVED_STATE = 4

        const val JOIN_REJECTED_REASON = 1

        const val USER_JOINED_USER_ID = 1
        const val USER_JOINED_USERNAME = 2

        const val USER_LEFT_USER_ID = 1
        const val USER_LEFT_USERNAME = 2

        const val BUFFER_WAIT_TRACK_ID = 1
        const val BUFFER_WAIT_WAITING_FOR = 2

        const val BUFFER_COMPLETE_TRACK_ID = 1

        const val ERROR_CODE = 1
        const val ERROR_MESSAGE = 2

        const val HOST_CHANGED_NEW_HOST_ID = 1
        const val HOST_CHANGED_NEW_HOST_NAME = 2

        const val KICKED_REASON = 1

        const val SYNC_STATE_CURRENT_TRACK = 1
        const val SYNC_STATE_IS_PLAYING = 2
        const val SYNC_STATE_POSITION = 3
        const val SYNC_STATE_LAST_UPDATE = 4
        const val SYNC_STATE_QUEUE = 5
        const val SYNC_STATE_VOLUME = 6

        const val RECONNECTED_ROOM_CODE = 1
        const val RECONNECTED_USER_ID = 2
        const val RECONNECTED_STATE = 3
        const val RECONNECTED_IS_HOST = 4

        const val USER_RECONNECTED_USER_ID = 1
        const val USER_RECONNECTED_USERNAME = 2

        const val USER_DISCONNECTED_USER_ID = 1
        const val USER_DISCONNECTED_USERNAME = 2

        const val SUGGESTION_RECEIVED_ID = 1
        const val SUGGESTION_RECEIVED_FROM_USER_ID = 2
        const val SUGGESTION_RECEIVED_FROM_USERNAME = 3
        const val SUGGESTION_RECEIVED_TRACK_INFO = 4

        const val SUGGESTION_APPROVED_ID = 1
        const val SUGGESTION_APPROVED_TRACK_INFO = 2

        const val SUGGESTION_REJECTED_ID = 1
        const val SUGGESTION_REJECTED_REASON = 2

        /**
         * Detect message format by inspecting first byte
         */
        fun detectMessageFormat(data: ByteArray): MessageFormat {
            if (data.isEmpty()) return MessageFormat.JSON
            // JSON messages start with '{'
            if (data[0] == '{'.code.toByte()) return MessageFormat.JSON
            // Protobuf messages have field tags
            return MessageFormat.PROTOBUF
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Encode a message with the codec's format and compression settings
     */
    fun encode(msgType: String, payload: Any?): ByteArray {
        return if (format == MessageFormat.PROTOBUF) {
            encodeProtobuf(msgType, payload)
        } else {
            encodeJson(msgType, payload)
        }
    }

    /**
     * Decode a message, automatically detecting format
     */
    fun decode(data: ByteArray): Pair<String, ByteArray> {
        val detectedFormat = detectMessageFormat(data)

        return if (detectedFormat == MessageFormat.PROTOBUF) {
            decodeProtobuf(data)
        } else {
            decodeJson(data)
        }
    }

    /**
     * Encode message as JSON (DEPRECATED - will be removed in future versions)
     */
    private fun encodeJson(msgType: String, payload: Any?): ByteArray {
        val msg = Message(
            type = msgType,
            payload = if (payload != null) json.encodeToJsonElement(serializer(payload), payload) else null
        )

        var data = json.encodeToString(msg).toByteArray()

        if (compressionEnabled && data.size > COMPRESSION_THRESHOLD) {
            val compressed = compressData(data)
            if (compressed.size < data.size) {
                data = compressed
            }
        }

        return data
    }

    /**
     * Decode JSON message (DEPRECATED - will be removed in future versions)
     */
    private fun decodeJson(data: ByteArray): Pair<String, ByteArray> {
        // Try to decompress if it looks compressed (gzip magic bytes)
        val actualData = if (compressionEnabled && data.size > 2 &&
                             data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
            decompressData(data) ?: data
        } else {
            data
        }

        val msg = json.decodeFromString<Message>(actualData.decodeToString())
        val payloadBytes = msg.payload?.toString()?.toByteArray() ?: byteArrayOf()

        return Pair(msg.type, payloadBytes)
    }

    /**
     * Encode message using Protocol Buffers
     */
    private fun encodeProtobuf(msgType: String, payload: Any?): ByteArray {
        var payloadBytes = byteArrayOf()
        var compressed = false

        if (payload != null) {
            payloadBytes = encodePayload(payload)

            // Compress if enabled and payload is large enough
            if (compressionEnabled && payloadBytes.size > COMPRESSION_THRESHOLD) {
                val compressedBytes = compressData(payloadBytes)
                if (compressedBytes.size < payloadBytes.size) {
                    payloadBytes = compressedBytes
                    compressed = true
                }
            }
        }

        val writer = ProtoWriter(payloadBytes.size + 16)
        writer.writeString(FIELD_ENVELOPE_TYPE, msgType)
        // proto3 does not write an empty bytes field; an absent payload decodes back to an empty
        // array, which [decodePayload] treats as "no payload" — same as the generated classes did.
        if (payloadBytes.isNotEmpty()) {
            writer.writeBytes(FIELD_ENVELOPE_PAYLOAD, payloadBytes)
        }
        writer.writeBool(FIELD_ENVELOPE_COMPRESSED, compressed)
        return writer.toByteArray()
    }

    /**
     * Decode protobuf message
     */
    private fun decodeProtobuf(data: ByteArray): Pair<String, ByteArray> {
        val reader = ProtoReader(data)
        var type = ""
        var payloadBytes = byteArrayOf()
        var compressed = false

        while (true) {
            when (val field = reader.nextField()) {
                -1 -> break
                FIELD_ENVELOPE_TYPE -> type = reader.readString()
                FIELD_ENVELOPE_PAYLOAD -> payloadBytes = reader.readBytes()
                FIELD_ENVELOPE_COMPRESSED -> compressed = reader.readBool()
                else -> reader.skip()
            }
        }

        if (compressed) {
            payloadBytes = decompressData(payloadBytes) ?: payloadBytes
        }

        return Pair(type, payloadBytes)
    }

    /**
     * Compress data using GZIP
     */
    private fun compressData(data: ByteArray): ByteArray {
        val outputStream = ByteArrayOutputStream()
        GZIPOutputStream(outputStream).use { gzip ->
            gzip.write(data)
        }
        return outputStream.toByteArray()
    }

    /**
     * Decompress GZIP data
     */
    private fun decompressData(data: ByteArray): ByteArray? {
        return try {
            val inputStream = ByteArrayInputStream(data)
            GZIPInputStream(inputStream).use { gzip ->
                gzip.readBytes()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to decompress data")
            null
        }
    }

    /**
     * Convert Kotlin objects to a protobuf payload.
     *
     * Field numbers are those of the matching messages in listentogether.proto; nullable strings
     * are written as empty and therefore omitted, exactly as `setField(x ?: "")` did.
     */
    private fun encodePayload(payload: Any): ByteArray {
        val writer = ProtoWriter()
        when (payload) {
            is CreateRoomPayload ->
                writer.writeString(FIELD_CREATE_ROOM_USERNAME, payload.username)

            is JoinRoomPayload -> {
                writer.writeString(FIELD_JOIN_ROOM_CODE, payload.roomCode)
                writer.writeString(FIELD_JOIN_ROOM_USERNAME, payload.username)
            }

            is ApproveJoinPayload ->
                writer.writeString(FIELD_APPROVE_JOIN_USER_ID, payload.userId)

            is RejectJoinPayload -> {
                writer.writeString(FIELD_REJECT_JOIN_USER_ID, payload.userId)
                writer.writeString(FIELD_REJECT_JOIN_REASON, payload.reason ?: "")
            }

            is PlaybackActionPayload -> {
                writer.writeString(PLAYBACK_ACTION, payload.action)
                payload.trackId?.let { writer.writeString(PLAYBACK_TRACK_ID, it) }
                payload.position?.let { writer.writeInt64(PLAYBACK_POSITION, it) }
                // Written even when empty: proto3 presence is what tell the reader the field exists.
                payload.trackInfo?.let { track ->
                    writer.writeMessage(PLAYBACK_TRACK_INFO) { trackInfoToProto(track, it) }
                }
                payload.insertNext?.let { writer.writeBool(PLAYBACK_INSERT_NEXT, it) }
                payload.queue?.forEach { track ->
                    writer.writeMessage(PLAYBACK_QUEUE) { trackInfoToProto(track, it) }
                }
                payload.queueTitle?.let { writer.writeString(PLAYBACK_QUEUE_TITLE, it) }
                payload.volume?.let { writer.writeFloat(PLAYBACK_VOLUME, it) }
                payload.serverTime?.let { writer.writeInt64(PLAYBACK_SERVER_TIME, it) }
            }

            is BufferReadyPayload ->
                writer.writeString(FIELD_BUFFER_READY_TRACK_ID, payload.trackId)

            is KickUserPayload -> {
                writer.writeString(FIELD_KICK_USER_ID, payload.userId)
                writer.writeString(FIELD_KICK_REASON, payload.reason ?: "")
            }

            is SuggestTrackPayload ->
                writer.writeMessage(FIELD_SUGGEST_TRACK_INFO) { trackInfoToProto(payload.trackInfo, it) }

            is ApproveSuggestionPayload ->
                writer.writeString(FIELD_APPROVE_SUGGESTION_ID, payload.suggestionId)

            is RejectSuggestionPayload -> {
                writer.writeString(FIELD_REJECT_SUGGESTION_ID, payload.suggestionId)
                writer.writeString(FIELD_REJECT_SUGGESTION_REASON, payload.reason ?: "")
            }

            is ReconnectPayload ->
                writer.writeString(FIELD_RECONNECT_SESSION_TOKEN, payload.sessionToken)

            is TransferHostPayload ->
                writer.writeString(FIELD_TRANSFER_HOST_NEW_HOST_ID, payload.newHostId)

            else -> throw IllegalArgumentException("Unsupported payload type: ${payload::class.simpleName}")
        }
        return writer.toByteArray()
    }

    /**
     * Decode protobuf payload to Kotlin objects
     */
    fun decodePayload(msgType: String, payloadBytes: ByteArray, format: MessageFormat): Any? {
        if (payloadBytes.isEmpty()) return null

        return if (format == MessageFormat.PROTOBUF) {
            decodeProtobufPayload(msgType, payloadBytes)
        } else {
            decodeJsonPayload(msgType, payloadBytes)
        }
    }

    /**
     * Decode JSON payload (DEPRECATED - will be removed in future versions)
     */
    private fun decodeJsonPayload(msgType: String, payloadBytes: ByteArray): Any? {
        val payloadString = payloadBytes.decodeToString()

        return when (msgType) {
            MessageTypes.ROOM_CREATED -> json.decodeFromString<RoomCreatedPayload>(payloadString)
            MessageTypes.JOIN_REQUEST -> json.decodeFromString<JoinRequestPayload>(payloadString)
            MessageTypes.JOIN_APPROVED -> json.decodeFromString<JoinApprovedPayload>(payloadString)
            MessageTypes.JOIN_REJECTED -> json.decodeFromString<JoinRejectedPayload>(payloadString)
            MessageTypes.USER_JOINED -> json.decodeFromString<UserJoinedPayload>(payloadString)
            MessageTypes.USER_LEFT -> json.decodeFromString<UserLeftPayload>(payloadString)
            MessageTypes.SYNC_PLAYBACK -> json.decodeFromString<PlaybackActionPayload>(payloadString)
            MessageTypes.BUFFER_WAIT -> json.decodeFromString<BufferWaitPayload>(payloadString)
            MessageTypes.BUFFER_COMPLETE -> json.decodeFromString<BufferCompletePayload>(payloadString)
            MessageTypes.ERROR -> json.decodeFromString<ErrorPayload>(payloadString)
            MessageTypes.HOST_CHANGED -> json.decodeFromString<HostChangedPayload>(payloadString)
            MessageTypes.KICKED -> json.decodeFromString<KickedPayload>(payloadString)
            MessageTypes.SYNC_STATE -> json.decodeFromString<SyncStatePayload>(payloadString)
            MessageTypes.RECONNECTED -> json.decodeFromString<ReconnectedPayload>(payloadString)
            MessageTypes.USER_RECONNECTED -> json.decodeFromString<UserReconnectedPayload>(payloadString)
            MessageTypes.USER_DISCONNECTED -> json.decodeFromString<UserDisconnectedPayload>(payloadString)
            MessageTypes.SUGGESTION_RECEIVED -> json.decodeFromString<SuggestionReceivedPayload>(payloadString)
            MessageTypes.SUGGESTION_APPROVED -> json.decodeFromString<SuggestionApprovedPayload>(payloadString)
            MessageTypes.SUGGESTION_REJECTED -> json.decodeFromString<SuggestionRejectedPayload>(payloadString)
            MessageTypes.CHAT -> json.decodeFromString<ChatMessagePayload>(payloadString)
            else -> null
        }
    }

    /**
     * Decode a protobuf payload for [msgType].
     *
     * Unknown fields are skipped, and a message field that never appeared decodes to null — the same
     * contract the generated `hasX()` accessors provided.
     */
    private fun decodeProtobufPayload(msgType: String, payloadBytes: ByteArray): Any? {
        val reader = ProtoReader(payloadBytes)

        return when (msgType) {
            MessageTypes.ROOM_CREATED -> {
                var roomCode = ""
                var userId = ""
                var sessionToken = ""
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        ROOM_CREATED_ROOM_CODE -> roomCode = reader.readString()
                        ROOM_CREATED_USER_ID -> userId = reader.readString()
                        ROOM_CREATED_SESSION_TOKEN -> sessionToken = reader.readString()
                        else -> reader.skip()
                    }
                }
                RoomCreatedPayload(roomCode, userId, sessionToken)
            }

            MessageTypes.JOIN_REQUEST -> {
                var userId = ""
                var username = ""
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        JOIN_REQUEST_USER_ID -> userId = reader.readString()
                        JOIN_REQUEST_USERNAME -> username = reader.readString()
                        else -> reader.skip()
                    }
                }
                JoinRequestPayload(userId, username)
            }

            MessageTypes.JOIN_APPROVED -> {
                var roomCode = ""
                var userId = ""
                var sessionToken = ""
                var state: RoomState? = null
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        JOIN_APPROVED_ROOM_CODE -> roomCode = reader.readString()
                        JOIN_APPROVED_USER_ID -> userId = reader.readString()
                        JOIN_APPROVED_SESSION_TOKEN -> sessionToken = reader.readString()
                        JOIN_APPROVED_STATE -> state = protoToRoomState(reader.readMessage())
                        else -> reader.skip()
                    }
                }
                JoinApprovedPayload(roomCode, userId, sessionToken, state ?: emptyRoomState())
            }

            MessageTypes.JOIN_REJECTED -> {
                var reason = ""
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        JOIN_REJECTED_REASON -> reason = reader.readString()
                        else -> reader.skip()
                    }
                }
                JoinRejectedPayload(reason)
            }

            MessageTypes.USER_JOINED -> {
                var userId = ""
                var username = ""
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        USER_JOINED_USER_ID -> userId = reader.readString()
                        USER_JOINED_USERNAME -> username = reader.readString()
                        else -> reader.skip()
                    }
                }
                UserJoinedPayload(userId, username)
            }

            MessageTypes.USER_LEFT -> {
                var userId = ""
                var username = ""
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        USER_LEFT_USER_ID -> userId = reader.readString()
                        USER_LEFT_USERNAME -> username = reader.readString()
                        else -> reader.skip()
                    }
                }
                UserLeftPayload(userId, username)
            }

            MessageTypes.SYNC_PLAYBACK -> {
                var action = ""
                var trackId: String? = null
                var position: Long? = null
                var trackInfo: TrackInfo? = null
                var insertNext: Boolean? = null
                val queue = mutableListOf<TrackInfo>()
                var queueTitle: String? = null
                var volume: Float? = null
                var serverTime: Long? = null
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        PLAYBACK_ACTION -> action = reader.readString()
                        PLAYBACK_TRACK_ID -> trackId = reader.readString().ifEmpty { null }
                        PLAYBACK_POSITION -> position = reader.readInt64().let { if (it <= 0) null else it }
                        PLAYBACK_TRACK_INFO -> trackInfo = protoToTrackInfo(reader.readMessage())
                        PLAYBACK_INSERT_NEXT -> insertNext = reader.readBool()
                        PLAYBACK_QUEUE -> queue += protoToTrackInfo(reader.readMessage())
                        PLAYBACK_QUEUE_TITLE -> queueTitle = reader.readString().ifEmpty { null }
                        PLAYBACK_VOLUME -> volume = reader.readFloat().let { if (it <= 0) null else it }
                        PLAYBACK_SERVER_TIME -> serverTime = reader.readInt64().let { if (it <= 0) null else it }
                        else -> reader.skip()
                    }
                }
                PlaybackActionPayload(
                    action = action,
                    trackId = trackId,
                    position = position,
                    trackInfo = trackInfo,
                    insertNext = insertNext,
                    queue = queue,
                    queueTitle = queueTitle,
                    volume = volume,
                    serverTime = serverTime
                )
            }

            MessageTypes.BUFFER_WAIT -> {
                var trackId = ""
                val waitingFor = mutableListOf<String>()
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        BUFFER_WAIT_TRACK_ID -> trackId = reader.readString()
                        BUFFER_WAIT_WAITING_FOR -> waitingFor += reader.readString()
                        else -> reader.skip()
                    }
                }
                BufferWaitPayload(trackId, waitingFor)
            }

            MessageTypes.BUFFER_COMPLETE -> {
                var trackId = ""
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        BUFFER_COMPLETE_TRACK_ID -> trackId = reader.readString()
                        else -> reader.skip()
                    }
                }
                BufferCompletePayload(trackId)
            }

            MessageTypes.ERROR -> {
                var code = ""
                var message = ""
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        ERROR_CODE -> code = reader.readString()
                        ERROR_MESSAGE -> message = reader.readString()
                        else -> reader.skip()
                    }
                }
                ErrorPayload(code, message)
            }

            MessageTypes.HOST_CHANGED -> {
                var newHostId = ""
                var newHostName = ""
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        HOST_CHANGED_NEW_HOST_ID -> newHostId = reader.readString()
                        HOST_CHANGED_NEW_HOST_NAME -> newHostName = reader.readString()
                        else -> reader.skip()
                    }
                }
                HostChangedPayload(newHostId, newHostName)
            }

            MessageTypes.KICKED -> {
                var reason = ""
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        KICKED_REASON -> reason = reader.readString()
                        else -> reader.skip()
                    }
                }
                KickedPayload(reason)
            }

            MessageTypes.SYNC_STATE -> {
                var currentTrack: TrackInfo? = null
                var isPlaying = false
                var position = 0L
                var lastUpdate = 0L
                val queue = mutableListOf<TrackInfo>()
                var volume: Float? = null
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        SYNC_STATE_CURRENT_TRACK -> currentTrack = protoToTrackInfo(reader.readMessage())
                        SYNC_STATE_IS_PLAYING -> isPlaying = reader.readBool()
                        SYNC_STATE_POSITION -> position = reader.readInt64()
                        SYNC_STATE_LAST_UPDATE -> lastUpdate = reader.readInt64()
                        SYNC_STATE_QUEUE -> queue += protoToTrackInfo(reader.readMessage())
                        SYNC_STATE_VOLUME -> volume = reader.readFloat().let { if (it <= 0) null else it }
                        else -> reader.skip()
                    }
                }
                SyncStatePayload(
                    currentTrack = currentTrack,
                    isPlaying = isPlaying,
                    position = position,
                    lastUpdate = lastUpdate,
                    queue = queue,
                    volume = volume
                )
            }

            MessageTypes.RECONNECTED -> {
                var roomCode = ""
                var userId = ""
                var state: RoomState? = null
                var isHost = false
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        RECONNECTED_ROOM_CODE -> roomCode = reader.readString()
                        RECONNECTED_USER_ID -> userId = reader.readString()
                        RECONNECTED_STATE -> state = protoToRoomState(reader.readMessage())
                        RECONNECTED_IS_HOST -> isHost = reader.readBool()
                        else -> reader.skip()
                    }
                }
                ReconnectedPayload(roomCode, userId, state ?: emptyRoomState(), isHost)
            }

            MessageTypes.USER_RECONNECTED -> {
                var userId = ""
                var username = ""
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        USER_RECONNECTED_USER_ID -> userId = reader.readString()
                        USER_RECONNECTED_USERNAME -> username = reader.readString()
                        else -> reader.skip()
                    }
                }
                UserReconnectedPayload(userId, username)
            }

            MessageTypes.USER_DISCONNECTED -> {
                var userId = ""
                var username = ""
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        USER_DISCONNECTED_USER_ID -> userId = reader.readString()
                        USER_DISCONNECTED_USERNAME -> username = reader.readString()
                        else -> reader.skip()
                    }
                }
                UserDisconnectedPayload(userId, username)
            }

            MessageTypes.SUGGESTION_RECEIVED -> {
                var suggestionId = ""
                var fromUserId = ""
                var fromUsername = ""
                var trackInfo: TrackInfo? = null
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        SUGGESTION_RECEIVED_ID -> suggestionId = reader.readString()
                        SUGGESTION_RECEIVED_FROM_USER_ID -> fromUserId = reader.readString()
                        SUGGESTION_RECEIVED_FROM_USERNAME -> fromUsername = reader.readString()
                        SUGGESTION_RECEIVED_TRACK_INFO -> trackInfo = protoToTrackInfo(reader.readMessage())
                        else -> reader.skip()
                    }
                }
                SuggestionReceivedPayload(suggestionId, fromUserId, fromUsername, trackInfo ?: emptyTrackInfo())
            }

            MessageTypes.SUGGESTION_APPROVED -> {
                var suggestionId = ""
                var trackInfo: TrackInfo? = null
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        SUGGESTION_APPROVED_ID -> suggestionId = reader.readString()
                        SUGGESTION_APPROVED_TRACK_INFO -> trackInfo = protoToTrackInfo(reader.readMessage())
                        else -> reader.skip()
                    }
                }
                SuggestionApprovedPayload(suggestionId, trackInfo ?: emptyTrackInfo())
            }

            MessageTypes.SUGGESTION_REJECTED -> {
                var suggestionId = ""
                var reason: String? = null
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        SUGGESTION_REJECTED_ID -> suggestionId = reader.readString()
                        SUGGESTION_REJECTED_REASON -> reason = reader.readString().ifEmpty { null }
                        else -> reader.skip()
                    }
                }
                SuggestionRejectedPayload(suggestionId, reason)
            }

            // Client -> server messages. The client encodes these and never reads them back off the
            // wire, but the codec is the bidirectional mirror of listentogether.proto and the
            // round-trip tests pin the wire format through this path, so every payload the encoder
            // can produce must decode back to the value it was built from.
            MessageTypes.CREATE_ROOM -> {
                var username = ""
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        FIELD_CREATE_ROOM_USERNAME -> username = reader.readString()
                        else -> reader.skip()
                    }
                }
                CreateRoomPayload(username)
            }

            MessageTypes.JOIN_ROOM -> {
                var roomCode = ""
                var username = ""
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        FIELD_JOIN_ROOM_CODE -> roomCode = reader.readString()
                        FIELD_JOIN_ROOM_USERNAME -> username = reader.readString()
                        else -> reader.skip()
                    }
                }
                JoinRoomPayload(roomCode, username)
            }

            MessageTypes.APPROVE_JOIN -> {
                var userId = ""
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        FIELD_APPROVE_JOIN_USER_ID -> userId = reader.readString()
                        else -> reader.skip()
                    }
                }
                ApproveJoinPayload(userId)
            }

            MessageTypes.REJECT_JOIN -> {
                var userId = ""
                var reason: String? = null
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        FIELD_REJECT_JOIN_USER_ID -> userId = reader.readString()
                        FIELD_REJECT_JOIN_REASON -> reason = reader.readString().ifEmpty { null }
                        else -> reader.skip()
                    }
                }
                RejectJoinPayload(userId, reason)
            }

            MessageTypes.PLAYBACK_ACTION -> {
                var action = ""
                var trackId: String? = null
                var position: Long? = null
                var trackInfo: TrackInfo? = null
                var insertNext: Boolean? = null
                val queue = mutableListOf<TrackInfo>()
                var queueTitle: String? = null
                var volume: Float? = null
                var serverTime: Long? = null
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        PLAYBACK_ACTION -> action = reader.readString()
                        PLAYBACK_TRACK_ID -> trackId = reader.readString().ifEmpty { null }
                        PLAYBACK_POSITION -> position = reader.readInt64().let { if (it <= 0) null else it }
                        PLAYBACK_TRACK_INFO -> trackInfo = protoToTrackInfo(reader.readMessage())
                        PLAYBACK_INSERT_NEXT -> insertNext = reader.readBool()
                        PLAYBACK_QUEUE -> queue += protoToTrackInfo(reader.readMessage())
                        PLAYBACK_QUEUE_TITLE -> queueTitle = reader.readString().ifEmpty { null }
                        PLAYBACK_VOLUME -> volume = reader.readFloat().let { if (it <= 0) null else it }
                        PLAYBACK_SERVER_TIME -> serverTime = reader.readInt64().let { if (it <= 0) null else it }
                        else -> reader.skip()
                    }
                }
                PlaybackActionPayload(
                    action = action,
                    trackId = trackId,
                    position = position,
                    trackInfo = trackInfo,
                    insertNext = insertNext,
                    queue = queue,
                    queueTitle = queueTitle,
                    volume = volume,
                    serverTime = serverTime
                )
            }

            MessageTypes.BUFFER_READY -> {
                var trackId = ""
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        FIELD_BUFFER_READY_TRACK_ID -> trackId = reader.readString()
                        else -> reader.skip()
                    }
                }
                BufferReadyPayload(trackId)
            }

            MessageTypes.KICK_USER -> {
                var userId = ""
                var reason: String? = null
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        FIELD_KICK_USER_ID -> userId = reader.readString()
                        FIELD_KICK_REASON -> reason = reader.readString().ifEmpty { null }
                        else -> reader.skip()
                    }
                }
                KickUserPayload(userId, reason)
            }

            MessageTypes.SUGGEST_TRACK -> {
                var trackInfo: TrackInfo? = null
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        FIELD_SUGGEST_TRACK_INFO -> trackInfo = protoToTrackInfo(reader.readMessage())
                        else -> reader.skip()
                    }
                }
                SuggestTrackPayload(trackInfo ?: emptyTrackInfo())
            }

            MessageTypes.APPROVE_SUGGESTION -> {
                var suggestionId = ""
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        FIELD_APPROVE_SUGGESTION_ID -> suggestionId = reader.readString()
                        else -> reader.skip()
                    }
                }
                ApproveSuggestionPayload(suggestionId)
            }

            MessageTypes.REJECT_SUGGESTION -> {
                var suggestionId = ""
                var reason: String? = null
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        FIELD_REJECT_SUGGESTION_ID -> suggestionId = reader.readString()
                        FIELD_REJECT_SUGGESTION_REASON -> reason = reader.readString().ifEmpty { null }
                        else -> reader.skip()
                    }
                }
                RejectSuggestionPayload(suggestionId, reason)
            }

            MessageTypes.RECONNECT -> {
                var sessionToken = ""
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        FIELD_RECONNECT_SESSION_TOKEN -> sessionToken = reader.readString()
                        else -> reader.skip()
                    }
                }
                ReconnectPayload(sessionToken)
            }

            MessageTypes.TRANSFER_HOST -> {
                var newHostId = ""
                while (true) {
                    when (val field = reader.nextField()) {
                        -1 -> break
                        FIELD_TRANSFER_HOST_NEW_HOST_ID -> newHostId = reader.readString()
                        else -> reader.skip()
                    }
                }
                TransferHostPayload(newHostId)
            }

            else -> null
        }
    }

    /**
     * The default instances the generated protobuf accessors returned for an absent message field.
     * [TrackInfo] and [RoomState] have required constructor parameters, so the "absent" case has to
     * be spelled out rather than defaulted.
     */
    private fun emptyTrackInfo(): TrackInfo =
        TrackInfo(id = "", title = "", artist = "", duration = 0L)

    private fun emptyRoomState(): RoomState =
        RoomState(
            roomCode = "",
            hostId = "",
            users = emptyList(),
            currentTrack = null,
            isPlaying = false,
            position = 0L,
            lastUpdate = 0L,
            volume = 0f,
            queue = emptyList()
        )

    // Helper conversion functions

    private fun trackInfoToProto(track: TrackInfo, writer: ProtoWriter) {
        writer.writeString(TRACK_INFO_ID, track.id)
        writer.writeString(TRACK_INFO_TITLE, track.title)
        writer.writeString(TRACK_INFO_ARTIST, track.artist)
        writer.writeString(TRACK_INFO_ALBUM, track.album ?: "")
        writer.writeInt64(TRACK_INFO_DURATION, track.duration)
        writer.writeString(TRACK_INFO_THUMBNAIL, track.thumbnail ?: "")
        writer.writeString(TRACK_INFO_SUGGESTED_BY, track.suggestedBy ?: "")
    }

    private fun protoToTrackInfo(reader: ProtoReader): TrackInfo {
        var id = ""
        var title = ""
        var artist = ""
        var album: String? = null
        var duration = 0L
        var thumbnail: String? = null
        var suggestedBy: String? = null
        while (true) {
            when (val field = reader.nextField()) {
                -1 -> break
                TRACK_INFO_ID -> id = reader.readString()
                TRACK_INFO_TITLE -> title = reader.readString()
                TRACK_INFO_ARTIST -> artist = reader.readString()
                TRACK_INFO_ALBUM -> album = reader.readString().ifEmpty { null }
                TRACK_INFO_DURATION -> duration = reader.readInt64()
                TRACK_INFO_THUMBNAIL -> thumbnail = reader.readString().ifEmpty { null }
                TRACK_INFO_SUGGESTED_BY -> suggestedBy = reader.readString().ifEmpty { null }
                else -> reader.skip()
            }
        }
        return TrackInfo(
            id = id,
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            thumbnail = thumbnail,
            suggestedBy = suggestedBy
        )
    }

    private fun protoToUserInfo(reader: ProtoReader): UserInfo {
        var userId = ""
        var username = ""
        var isHost = false
        var isConnected = false
        while (true) {
            when (val field = reader.nextField()) {
                -1 -> break
                USER_INFO_USER_ID -> userId = reader.readString()
                USER_INFO_USERNAME -> username = reader.readString()
                USER_INFO_IS_HOST -> isHost = reader.readBool()
                USER_INFO_IS_CONNECTED -> isConnected = reader.readBool()
                else -> reader.skip()
            }
        }
        return UserInfo(userId, username, isHost, isConnected)
    }

    private fun protoToRoomState(reader: ProtoReader): RoomState {
        var roomCode = ""
        var hostId = ""
        val users = mutableListOf<UserInfo>()
        var currentTrack: TrackInfo? = null
        var isPlaying = false
        var position = 0L
        var lastUpdate = 0L
        var volume = 0f
        val queue = mutableListOf<TrackInfo>()
        while (true) {
            when (val field = reader.nextField()) {
                -1 -> break
                ROOM_STATE_ROOM_CODE -> roomCode = reader.readString()
                ROOM_STATE_HOST_ID -> hostId = reader.readString()
                ROOM_STATE_USERS -> users += protoToUserInfo(reader.readMessage())
                ROOM_STATE_CURRENT_TRACK -> currentTrack = protoToTrackInfo(reader.readMessage())
                ROOM_STATE_IS_PLAYING -> isPlaying = reader.readBool()
                ROOM_STATE_POSITION -> position = reader.readInt64()
                ROOM_STATE_LAST_UPDATE -> lastUpdate = reader.readInt64()
                ROOM_STATE_VOLUME -> volume = reader.readFloat()
                ROOM_STATE_QUEUE -> queue += protoToTrackInfo(reader.readMessage())
                else -> reader.skip()
            }
        }
        return RoomState(
            roomCode = roomCode,
            hostId = hostId,
            users = users,
            currentTrack = currentTrack,
            isPlaying = isPlaying,
            position = position,
            lastUpdate = lastUpdate,
            volume = volume,
            queue = queue
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> serializer(value: T): kotlinx.serialization.KSerializer<T> {
        return when (value) {
            is CreateRoomPayload -> CreateRoomPayload.serializer()
            is JoinRoomPayload -> JoinRoomPayload.serializer()
            is ApproveJoinPayload -> ApproveJoinPayload.serializer()
            is RejectJoinPayload -> RejectJoinPayload.serializer()
            is PlaybackActionPayload -> PlaybackActionPayload.serializer()
            is BufferReadyPayload -> BufferReadyPayload.serializer()
            is KickUserPayload -> KickUserPayload.serializer()
            is SuggestTrackPayload -> SuggestTrackPayload.serializer()
            is ApproveSuggestionPayload -> ApproveSuggestionPayload.serializer()
            is RejectSuggestionPayload -> RejectSuggestionPayload.serializer()
            is ReconnectPayload -> ReconnectPayload.serializer()
            is TransferHostPayload -> TransferHostPayload.serializer()
            is ChatPayload -> ChatPayload.serializer()
            else -> throw IllegalArgumentException("Unknown type: ${value!!::class.simpleName}")
        } as kotlinx.serialization.KSerializer<T>
    }

}
