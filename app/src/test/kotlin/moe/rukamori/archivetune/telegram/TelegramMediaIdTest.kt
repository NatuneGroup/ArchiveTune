/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramMediaIdTest {
    @Test
    fun roundTripsWithUniqueId() {
        val id =
            TelegramMediaId(
                chatId = -1001234567890L,
                messageId = 52428800L,
                fileId = 4711,
                fileUniqueId = "AgADBQADr6cxGw",
            )
        assertEquals(id, TelegramMediaId.decode(id.encode()))
    }

    @Test
    fun roundTripsWithoutUniqueId() {
        val id = TelegramMediaId(chatId = -100987L, messageId = 12L, fileId = 3)
        val encoded = id.encode()
        assertEquals("telegram://track/-100987/12/3", encoded)
        assertEquals(id, TelegramMediaId.decode(encoded))
    }

    @Test
    fun recognisesTelegramMediaIds() {
        assertTrue("telegram://track/-100987/12/3".isTelegramMediaId())
        assertTrue(
            TelegramMediaId(-1L, 2L, 3, "u").encode().isTelegramMediaId(),
        )
    }

    @Test
    fun rejectsForeignIds() {
        assertFalse("dQw4w9WgXcQ".isTelegramMediaId())
        assertFalse("content://media/external/audio/1".isTelegramMediaId())
        assertFalse("https://t.me/somechannel".isTelegramMediaId())
        assertFalse("telegram://track/notanumber/12/3".isTelegramMediaId())
        assertFalse("telegram://track/1/2".isTelegramMediaId())
    }

    @Test
    fun decodeRejectsMalformedIds() {
        assertNull(TelegramMediaId.decode("telegram://track/1/2"))
        assertNull(TelegramMediaId.decode("telegram://chat/1/2/3"))
        assertNull(TelegramMediaId.decode(""))
    }
}
