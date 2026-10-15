/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.acoustics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import moe.rukamori.archivetune.LocalDownloadUtil
import moe.rukamori.archivetune.LocalPlayerConnection

@Composable
fun rememberAcousticAuditController(
    target: AcousticAuditTarget,
    active: Boolean = true,
): AcousticAuditController {
    val context = LocalContext.current.applicationContext
    val service = LocalPlayerConnection.current?.service
    val downloads = LocalDownloadUtil.current
    val controller = remember(target, context, service, downloads) {
        AcousticAuditController(
            target,
            AcousticAuditSourceResolver(
                context,
                listOf(downloads.downloadCache, downloads.playerCache),
                snapshot = { service?.acousticAuditSourceSnapshot(it) },
            ),
        )
    }
    DisposableEffect(controller, active) {
        controller.setActive(active)
        onDispose { controller.cancel() }
    }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
    return controller
}
