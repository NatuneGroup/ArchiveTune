/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * Portions © 4nx3b — github.com/4nx3b
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Spotify PlayPlay audio-key pipeline, ported from echo-spotify-extension
 * (github.com/brahmkshatriya/echo-spotify-extension). Pure Kotlin, no native
 * runtime: the license request protobuf, the obfuscated-key protobuf parser and
 * the response mixing / key transform live here.
 */

package moe.rukamori.archivetune.spotify.echo

import java.io.ByteArrayOutputStream

object SpotifyPlayPlayLicenseRequest {
    private val requestToken = byteArrayOf(
        0x02, 0x19, 0x83.toByte(), 0x16, 0xbe.toByte(), 0xae.toByte(), 0x6d, 0x4a,
        0xf4.toByte(), 0x35, 0x24, 0xd0.toByte(), 0x2c, 0x37, 0x2b, 0x7e,
    )

    fun encode(timestampSeconds: Long = System.currentTimeMillis() / 1000L): ByteArray {
        require(timestampSeconds >= 0) { "Invalid PlayPlay timestamp" }
        return ByteArrayOutputStream(32).apply {
            fun varint(number: Long) {
                var value = number
                while (value and -128L != 0L) {
                    write(((value and 127L) or 128L).toInt())
                    value = value ushr 7
                }
                write(value.toInt())
            }
            write(0x08); varint(5)
            write(0x12); varint(requestToken.size.toLong()); write(requestToken)
            write(0x20); varint(1)
            write(0x28); varint(1)
            write(0x30); varint(timestampSeconds)
        }.toByteArray()
    }
}
