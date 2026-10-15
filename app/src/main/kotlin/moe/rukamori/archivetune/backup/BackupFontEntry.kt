/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.backup

internal fun isBackupFontEntry(name: String): Boolean {
    val prefix = "${BackupArchiveRepository.FONTS_ZIP_PREFIX}/"
    if (!name.startsWith(prefix)) return false
    val fileName = name.removePrefix(prefix)
    return fileName.endsWith(".ttf", ignoreCase = true) &&
        fileName.none { it == '/' || it == '\\' || it == '\u0000' }
}
