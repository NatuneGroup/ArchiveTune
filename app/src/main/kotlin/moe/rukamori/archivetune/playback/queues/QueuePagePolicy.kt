/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.queues

internal fun <T> appendableQueuePage(items: List<T>, repeatsCurrentItem: Boolean): List<T> =
    if (repeatsCurrentItem) items.drop(1) else items
