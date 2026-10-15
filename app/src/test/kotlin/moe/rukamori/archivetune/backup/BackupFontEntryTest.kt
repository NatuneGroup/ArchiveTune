/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFontEntryTest {
    @Test
    fun `accepts direct custom font files`() {
        assertTrue(isBackupFontEntry("fonts/font.ttf"))
        assertTrue(isBackupFontEntry("fonts/Custom Font.TTF"))
    }

    @Test
    fun `rejects traversal and nested paths`() {
        assertFalse(isBackupFontEntry("fonts/../../outside.ttf"))
        assertFalse(isBackupFontEntry("fonts/folder/font.ttf"))
        assertFalse(isBackupFontEntry("fonts/..\\outside.ttf"))
    }

    @Test
    fun `rejects absolute and null byte paths`() {
        assertFalse(isBackupFontEntry("fonts//tmp/outside.ttf"))
        assertFalse(isBackupFontEntry("fonts/\\outside.ttf"))
        assertFalse(isBackupFontEntry("fonts/font\u0000.ttf"))
    }

    @Test
    fun `rejects directories and unrelated archive entries`() {
        assertFalse(isBackupFontEntry("fonts/"))
        assertFalse(isBackupFontEntry("font.ttf"))
        assertFalse(isBackupFontEntry("fonts/file.txt"))
    }
}
