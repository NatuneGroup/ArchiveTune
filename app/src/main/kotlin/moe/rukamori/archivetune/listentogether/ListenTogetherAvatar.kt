/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.listentogether

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * Custom Listen Together profile pictures.
 *
 * The room protocol only carries a small `avatar_index` integer, so a custom picture
 * is stored locally (downscaled JPEG in filesDir) and shared with the other members
 * by piggybacking on the chat channel — the only client→everyone relay — with a
 * magic-prefixed base64 payload that clients intercept and never render as a chat
 * bubble.
 */
object ListenTogetherAvatar {
    /** Sentinel avatar index meaning "the user picked a custom picture". */
    const val CUSTOM_AVATAR_INDEX = 14

    private const val FILE_NAME = "listen_together_avatar.jpg"
    private const val MAX_DIMENSION = 192
    private const val JPEG_QUALITY = 70
    private const val MAX_BROADCAST_BYTES = 96 * 1024

    /** Wire format: "\u200B[LTA:<base64 jpeg>]\u200B" (mirrors the reply-embed pattern). */
    const val CUSTOM_AVATAR_PREFIX = "\u200B[LTA:"
    const val CUSTOM_AVATAR_SUFFIX = "]\u200B"

    fun customAvatarFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun saveCustomAvatar(
        context: Context,
        uri: Uri,
    ): Boolean =
        try {
            val source =
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    ?: return false
            val scale =
                minOf(1f, MAX_DIMENSION.toFloat() / maxOf(source.width, source.height).coerceAtLeast(1))
            val bitmap =
                if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        source,
                        (source.width * scale).toInt().coerceAtLeast(1),
                        (source.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                } else {
                    source
                }
            customAvatarFile(context).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (bitmap !== source) source.recycle()
            true
        } catch (e: Exception) {
            false
        }

    fun loadCustomAvatarBytes(context: Context): ByteArray? =
        try {
            val file = customAvatarFile(context)
            if (file.isFile && file.length() in 1..MAX_BROADCAST_BYTES) file.readBytes() else null
        } catch (e: Exception) {
            null
        }

    fun clearCustomAvatar(context: Context) {
        runCatching { customAvatarFile(context).delete() }
    }

    fun encodeAvatarBroadcast(bytes: ByteArray): String =
        CUSTOM_AVATAR_PREFIX + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) + CUSTOM_AVATAR_SUFFIX

    fun decodeAvatarBroadcast(message: String): ByteArray? =
        try {
            if (!message.startsWith(CUSTOM_AVATAR_PREFIX)) return null
            val endIdx = message.indexOf(CUSTOM_AVATAR_SUFFIX, CUSTOM_AVATAR_PREFIX.length)
            if (endIdx <= CUSTOM_AVATAR_PREFIX.length) return null
            val bytes =
                android.util.Base64.decode(
                    message.substring(CUSTOM_AVATAR_PREFIX.length, endIdx),
                    android.util.Base64.NO_WRAP,
                )
            if (bytes.isNotEmpty() && bytes.size <= MAX_BROADCAST_BYTES) bytes else null
        } catch (e: Exception) {
            null
        }
}
